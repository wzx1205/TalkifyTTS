// qnn_vocoder —— Qwen3-TTS tok_decoder(声码器)的 HTP 运行器(生产路径)
//
// 模型:onnx-community tok_decoder.onnx(cpu_fp16)→ QAIRT A8W8(输出 preserve_io_datatype
// 保持 fp32)→ context binary,输入 audio_codes [1,25,16] int32(25 帧 = 2s 音频),
// 输出 waveform [1,1,48000] fp32。无状态按块解码,与官方 25 帧块推理语义一致;
// 不足 25 帧补 0 码后裁剪。
//
// 与 qnn_probe3 同款 dlopen 链:libQnnHtp → getProviders(三重指针) →
// SystemContext 解析元数据 → contextCreateFromBinary → graphRetrieve → execute。
// graph 由 contextRetrieve 获得、归 context 所有,释放只需 contextFree。

#include "qnn_vocoder.h"

#include <android/log.h>
#include <dlfcn.h>
#include <cstdlib>
#include <ctime>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>
#include <cstring>
#include <string>
#include <vector>

#include "QnnInterface.h"
#include "QnnTypes.h"
#include "System/QnnSystemContext.h"
#include "QnnGraph.h"
#include "System/QnnSystemInterface.h"
#include "QnnTensor.h"

#define TAG "QnnVocoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int CHUNK_FRAMES  = 25;
constexpr int FRAME_CODES   = 16;
constexpr int SR_PER_FRAME  = 1920; // 24000Hz / 12.5fps

size_t dtype_bytes(Qnn_DataType_t t) {
    switch (t) {
        case QNN_DATATYPE_FLOAT_16:
        case QNN_DATATYPE_INT_16:
        case QNN_DATATYPE_UINT_16: return 2;
        case QNN_DATATYPE_FLOAT_32:
        case QNN_DATATYPE_INT_32:
        case QNN_DATATYPE_UINT_32: return 4;
        case QNN_DATATYPE_INT_64:
        case QNN_DATATYPE_UINT_64: return 8;
        case QNN_DATATYPE_INT_8:
        case QNN_DATATYPE_UINT_8:
        case QNN_DATATYPE_UFIXED_POINT_8: return 1;
        default: return 4;
    }
}

size_t tensor_bytes(const Qnn_Tensor_t & t) {
    size_t elems = 1;
    for (uint32_t d = 0; d < t.v2.rank; ++d) elems *= t.v2.dimensions[d];
    return elems * dtype_bytes(t.v2.dataType);
}

} // namespace

namespace talkify {

static double now_ms_voc() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

struct QnnVocoder::Impl {
    std::mutex lock;
    bool ready = false;
    void * hBackend = nullptr;   // dlopen 句柄,dlclose 于 cleanup
    void * hSystem = nullptr;
    QNN_INTERFACE_VER_TYPE iface{};
    Qnn_LogHandle_t     logger  = nullptr;
    Qnn_DeviceHandle_t  device  = nullptr;
    Qnn_BackendHandle_t backend = nullptr;
    Qnn_ContextHandle_t context = nullptr;
    Qnn_GraphHandle_t   graph   = nullptr; // 归 context 所有,不单独释放

    std::vector<Qnn_Tensor_t> inT, outT;
    // 元数据深拷贝:SystemContext 释放后其 name/dimensions 指针失效,
    // 这里持有自有副本并把张量结构体的指针回填到本存储
    std::vector<uint32_t> inDims, outDims;
    std::string inName, outName;
    std::vector<uint8_t> inBuf, outBuf;
    size_t inBytes = 0, outBytes = 0;

    // 输出反量化参数(输出层若被量化则需转 float;preserve_io 时为 FP32 直通)
    Qnn_DataType_t outDtype = QNN_DATATYPE_FLOAT_32;
    float outScale = 1.0f;
    int32_t outOffset = 0;

    // ---- 异步流水线:单工作线程消费 FIFO,与 CPU 帧生成并行 ----
    struct VocTask {
        int32_t nFrames = 0;
        std::vector<int32_t> codes;
        std::vector<float> pcm;
        bool done = false;
        bool failed = false;
    };
    std::thread worker;
    bool quitting = false;
    std::deque<VocTask> queue;
    std::condition_variable cv;

    // 核心解码(持锁调用,decode 与 worker 共用;QNN 单 context 串行由本锁保证)
    int32_t decodeImpl(const int32_t * codes, int32_t nFrames, float * pcmOut);

    void workerLoop() {
        std::unique_lock<std::mutex> lk(lock);
        for (;;) {
            cv.wait(lk, [this] { return quitting || (!queue.empty() && !queue.front().done); });
            if (quitting) return;
            VocTask & t = queue.front();
            t.failed = decodeImpl(t.codes.data(), t.nFrames, t.pcm.data()) != 0;
            t.codes.clear();
            t.codes.shrink_to_fit();
            t.done = true;
            cv.notify_all();
            // front 处于 done:下一轮 predicate 阻塞,直到消费者 wait() pop 掉它
        }
    }

    void stopWorker() {
        // 先置退出标志并清队(在锁内),再在锁外 join——worker 解码时持有锁,
        // 锁内 join 会死锁
        quitting = true;
        queue.clear();
        cv.notify_all();
    }

    // 释放全部 QNN 资源;持锁调用。失败路径与析构共用,可重复调用。
    void cleanupLocked() {
        ready = false;
        if (context && iface.contextFree) {
            iface.contextFree(context, nullptr);
            context = nullptr;
        }
        graph = nullptr;
        if (backend && iface.backendFree) {
            iface.backendFree(backend);
            backend = nullptr;
        }
        if (device && iface.deviceFree) {
            iface.deviceFree(device);
            device = nullptr;
        }
        if (logger && iface.logFree) {
            iface.logFree(logger);
            logger = nullptr;
        }
        if (hSystem) { dlclose(hSystem); hSystem = nullptr; }
        if (hBackend) { dlclose(hBackend); hBackend = nullptr; }
        inT.clear(); outT.clear();
        inBuf.clear(); outBuf.clear();
        inBytes = outBytes = 0;
    }
};

QnnVocoder & QnnVocoder::instance() {
    static QnnVocoder inst;
    return inst;
}

QnnVocoder::QnnVocoder() : impl(new Impl()) {}
QnnVocoder::~QnnVocoder() {
    if (impl) {
        {
            std::lock_guard<std::mutex> g(impl->lock);
            impl->stopWorker();
        }
        if (impl->worker.joinable()) impl->worker.join();
        std::lock_guard<std::mutex> g(impl->lock);
        impl->cleanupLocked();
        delete impl;
    }
}

bool QnnVocoder::ready() const {
    return impl->ready;
}

bool QnnVocoder::init(const char * nativeDir, const char * ctxPath) {
    std::lock_guard<std::mutex> g(impl->lock);
    if (impl->ready) return true;
    // 上次 init 失败可能遗留部分句柄,先清干净再重来
    impl->cleanupLocked();

    std::string adsp = std::string(nativeDir) + ";/system/lib/rfsa/adsp;/vendor/dsp/cdsp;/dsp";
    setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);

    FILE * f = fopen(ctxPath, "rb");
    if (!f) {
        LOGE("ctx not found: %s", ctxPath);
        return false;
    }
    fseek(f, 0, SEEK_END);
    long fileSize = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::vector<uint8_t> bin(fileSize);
    size_t rd = fread(bin.data(), 1, fileSize, f);
    fclose(f);
    if (rd != (size_t) fileSize) {
        LOGE("ctx short read");
        return false;
    }
    LOGI("ctx loaded: %s (%ld bytes)", ctxPath, fileSize);

    impl->hBackend = dlopen("libQnnHtp.so", RTLD_NOW);
    if (!impl->hBackend) {
        LOGE("dlopen libQnnHtp failed: %s", dlerror());
        impl->cleanupLocked();
        return false;
    }
    using GetProvidersFn = Qnn_ErrorHandle_t (*)(const QnnInterface_t ***, uint32_t *);
    auto getProviders = reinterpret_cast<GetProvidersFn>(
        dlsym(impl->hBackend, "QnnInterface_getProviders"));
    const QnnInterface_t ** providerList = nullptr;
    uint32_t nProviders = 0;
    if (!getProviders || getProviders(&providerList, &nProviders) != QNN_SUCCESS ||
        !nProviders || !providerList) {
        LOGE("getProviders failed");
        impl->cleanupLocked();
        return false;
    }
    impl->iface = providerList[0][0].QNN_INTERFACE_VER_NAME;

    if (impl->iface.logCreate) {
        impl->iface.logCreate(nullptr, QNN_LOG_LEVEL_ERROR, &impl->logger);
    }
    if (impl->iface.deviceCreate) {
        if (impl->iface.deviceCreate(impl->logger, nullptr, &impl->device) != QNN_SUCCESS) {
            impl->device = nullptr; // 部分设备可空句柄运行,不视为致命
        }
    }
    if (impl->iface.backendCreate(impl->logger, nullptr, &impl->backend) != QNN_SUCCESS) {
        LOGE("backendCreate failed");
        impl->cleanupLocked();
        return false;
    }

    impl->hSystem = dlopen("libQnnSystem.so", RTLD_NOW);
    if (!impl->hSystem) {
        LOGE("dlopen libQnnSystem failed");
        impl->cleanupLocked();
        return false;
    }
    using SysGetFn = Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t ***, uint32_t *);
    auto sysGet = reinterpret_cast<SysGetFn>(dlsym(impl->hSystem, "QnnSystemInterface_getProviders"));
    const QnnSystemInterface_t ** sysList = nullptr;
    uint32_t nSys = 0;
    if (!sysGet || sysGet(&sysList, &nSys) != QNN_SUCCESS || !nSys || !sysList) {
        LOGE("sys getProviders failed");
        impl->cleanupLocked();
        return false;
    }
    auto sysIface = sysList[0][0].QNN_SYSTEM_INTERFACE_VER_NAME;

    QnnSystemContext_Handle_t sysCtx = nullptr;
    if (sysIface.systemContextCreate(&sysCtx) != QNN_SUCCESS) {
        LOGE("systemContextCreate failed");
        impl->cleanupLocked();
        return false;
    }
    const QnnSystemContext_BinaryInfo_t * binInfo = nullptr;
    Qnn_ContextBinarySize_t binInfoSize = 0;
    Qnn_ErrorHandle_t gerr = sysIface.systemContextGetBinaryInfo(
        sysCtx, bin.data(), bin.size(), &binInfo, &binInfoSize);
    if (gerr != QNN_SUCCESS || !binInfo) {
        LOGE("getBinaryInfo failed");
        sysIface.systemContextFree(sysCtx);
        impl->cleanupLocked();
        return false;
    }

    const char * graphNameC = nullptr;
    uint32_t nIn = 0, nOut = 0;
    Qnn_Tensor_t * inT = nullptr, * outT = nullptr;
    switch (binInfo->version) {
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3: {
            const auto & v3 = binInfo->contextBinaryInfoV3;
            if (v3.numGraphs == 0) { sysIface.systemContextFree(sysCtx); impl->cleanupLocked(); return false; }
            graphNameC = v3.graphs[0].graphInfoV3.graphName;
            nIn  = v3.graphs[0].graphInfoV3.numGraphInputs;
            inT  = v3.graphs[0].graphInfoV3.graphInputs;
            nOut = v3.graphs[0].graphInfoV3.numGraphOutputs;
            outT = v3.graphs[0].graphInfoV3.graphOutputs;
            break;
        }
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2: {
            const auto & v2 = binInfo->contextBinaryInfoV2;
            if (v2.numGraphs == 0) { sysIface.systemContextFree(sysCtx); impl->cleanupLocked(); return false; }
            graphNameC = v2.graphs[0].graphInfoV2.graphName;
            nIn  = v2.graphs[0].graphInfoV2.numGraphInputs;
            inT  = v2.graphs[0].graphInfoV2.graphInputs;
            nOut = v2.graphs[0].graphInfoV2.numGraphOutputs;
            outT = v2.graphs[0].graphInfoV2.graphOutputs;
            break;
        }
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1: {
            const auto & v1 = binInfo->contextBinaryInfoV1;
            if (v1.numGraphs == 0) { sysIface.systemContextFree(sysCtx); impl->cleanupLocked(); return false; }
            graphNameC = v1.graphs[0].graphInfoV1.graphName;
            nIn  = v1.graphs[0].graphInfoV1.numGraphInputs;
            inT  = v1.graphs[0].graphInfoV1.graphInputs;
            nOut = v1.graphs[0].graphInfoV1.numGraphOutputs;
            outT = v1.graphs[0].graphInfoV1.graphOutputs;
            break;
        }
        default:
            LOGE("binaryInfo version %d unsupported", (int) binInfo->version);
            sysIface.systemContextFree(sysCtx);
            impl->cleanupLocked();
            return false;
    }
    // 元数据张量数组由 binInfo/SystemContext 持有:先深拷贝 dims/name,
    // 再释放 system context,最后把张量结构体的指针回填到自有存储
    std::string graphName = graphNameC ? graphNameC : "";
    impl->inT.assign(inT, inT + nIn);
    impl->outT.assign(outT, outT + nOut);
    if (nIn == 1 && inT[0].v2.dimensions) {
        impl->inDims.assign(inT[0].v2.dimensions, inT[0].v2.dimensions + inT[0].v2.rank);
        impl->inName = inT[0].v2.name ? inT[0].v2.name : "";
    }
    if (nOut == 1 && outT[0].v2.dimensions) {
        impl->outDims.assign(outT[0].v2.dimensions, outT[0].v2.dimensions + outT[0].v2.rank);
        impl->outName = outT[0].v2.name ? outT[0].v2.name : "";
    }
    sysIface.systemContextFree(sysCtx);
    if (impl->inDims.size()) impl->inT[0].v2.dimensions = impl->inDims.data();
    if (impl->outDims.size()) impl->outT[0].v2.dimensions = impl->outDims.data();
    if (impl->inName.size()) impl->inT[0].v2.name = impl->inName.c_str();
    if (impl->outName.size()) impl->outT[0].v2.name = impl->outName.c_str();

    // 契约:单输入单输出(本模型 audio_codes → waveform);变化时显式拒绝而非静默错配
    if (impl->inT.size() != 1 || impl->outT.size() != 1) {
        LOGE("expected 1 in / 1 out, got %zu / %zu", impl->inT.size(), impl->outT.size());
        impl->cleanupLocked();
        return false;
    }
    if (graphName.empty()) {
        LOGE("empty graph name");
        impl->cleanupLocked();
        return false;
    }

    double t0 = now_ms_voc();
    if (impl->iface.contextCreateFromBinary(impl->backend, impl->device, nullptr,
                                            bin.data(), bin.size(),
                                            &impl->context, nullptr) != QNN_SUCCESS) {
        LOGE("contextCreateFromBinary failed");
        impl->cleanupLocked();
        return false;
    }
    LOGI("context from binary ok (%.0fms), graph=%s",
         now_ms_voc() - t0, graphName.c_str());

    if (impl->iface.graphRetrieve(impl->context, graphName.c_str(), &impl->graph) != QNN_SUCCESS) {
        LOGE("graphRetrieve failed");
        impl->cleanupLocked();
        return false;
    }

    // IO 缓冲按元数据规格分配并常驻
    impl->inBytes = tensor_bytes(impl->inT[0]);
    impl->inBuf.resize(impl->inBytes ? impl->inBytes : 1, 0);
    impl->inT[0].v2.clientBuf.data = impl->inBuf.data();
    impl->inT[0].v2.clientBuf.dataSize = impl->inBuf.size();
    impl->inT[0].v2.type = QNN_TENSOR_TYPE_APP_READ;

    impl->outBytes = tensor_bytes(impl->outT[0]);
    impl->outBuf.resize(impl->outBytes ? impl->outBytes : 1, 0);
    impl->outT[0].v2.clientBuf.data = impl->outBuf.data();
    impl->outT[0].v2.clientBuf.dataSize = impl->outBuf.size();
    impl->outT[0].v2.type = QNN_TENSOR_TYPE_APP_WRITE;

    impl->outDtype = impl->outT[0].v2.dataType;
    const auto & qp = impl->outT[0].v2.quantizeParams;
    if (qp.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
        impl->outScale  = qp.scaleOffsetEncoding.scale;
        impl->outOffset = qp.scaleOffsetEncoding.offset;
    }
    LOGI("in  %s dtype=0x%x bytes=%zu", impl->inT[0].v2.name,
         (unsigned) impl->inT[0].v2.dataType, impl->inBytes);
    LOGI("out %s dtype=0x%x bytes=%zu scale=%g offset=%d", impl->outT[0].v2.name,
         (unsigned) impl->outT[0].v2.dataType, impl->outBytes,
         impl->outScale, impl->outOffset);

    // 预热一次,失败即视为不可用
    if (impl->iface.graphExecute(impl->graph, impl->inT.data(), 1,
                                 impl->outT.data(), 1, nullptr, nullptr) != QNN_SUCCESS) {
        LOGE("warmup execute failed");
        impl->cleanupLocked();
        return false;
    }
    impl->ready = true;
    // 启动异步流水线工作线程(失败路径 cleanupLocked 前不会到这里)
    impl->quitting = false;
    impl->worker = std::thread([i = impl] { i->workerLoop(); });
    LOGI("qnn vocoder ready (chunk=%d frames -> %d samples, async pipeline on)",
         CHUNK_FRAMES, CHUNK_FRAMES * SR_PER_FRAME);
    return true;
}

int32_t QnnVocoder::Impl::decodeImpl(const int32_t * codes, int32_t nFrames, float * pcmOut) {
    // 持锁调用。输入 [1,25,16] int32:有效帧拷入,尾部补 0 码(只清尾部)
    const size_t validBytes = (size_t) nFrames * FRAME_CODES * sizeof(int32_t);
    if (validBytes > inBytes) return -2;
    std::memcpy(inBuf.data(), codes, validBytes);
    if (validBytes < inBytes) {
        std::memset(inBuf.data() + validBytes, 0, inBytes - validBytes);
    }

    if (iface.graphExecute(graph, inT.data(), 1, outT.data(), 1, nullptr, nullptr) != QNN_SUCCESS) {
        LOGE("execute failed");
        return -3;
    }

    // 输出裁到有效帧数并按需反量化
    const size_t want = (size_t) nFrames * SR_PER_FRAME;
    switch (outDtype) {
        case QNN_DATATYPE_FLOAT_32: {
            if (outBuf.size() < want * sizeof(float)) return -4;
            std::memcpy(pcmOut, outBuf.data(), want * sizeof(float));
            break;
        }
        case QNN_DATATYPE_UFIXED_POINT_8:
        case QNN_DATATYPE_UINT_8:
        case QNN_DATATYPE_INT_8: {
            if (outBuf.size() < want) return -4;
            const bool isSigned = outDtype == QNN_DATATYPE_INT_8;
            const uint8_t * q = outBuf.data();
            for (size_t k = 0; k < want; ++k) {
                const int32_t v = isSigned ? (int32_t) (int8_t) q[k] : (int32_t) q[k];
                pcmOut[k] = (v - outOffset) * outScale;
            }
            break;
        }
        default:
            LOGE("unsupported output dtype 0x%x", (unsigned) outDtype);
            return -5;
    }
    return 0;
}

int32_t QnnVocoder::decode(const int32_t * codes, int32_t nFrames, float * pcmOut) {
    if (!codes || !pcmOut || nFrames <= 0 || nFrames > CHUNK_FRAMES) return -2;
    std::lock_guard<std::mutex> g(impl->lock);
    if (!impl->ready) return -1;
    return impl->decodeImpl(codes, nFrames, pcmOut);
}

int32_t QnnVocoder::submit(const int32_t * codes, int32_t nFrames) {
    if (!codes || nFrames <= 0 || nFrames > CHUNK_FRAMES) return -2;
    std::lock_guard<std::mutex> g(impl->lock);
    if (!impl->ready) return -1;
    Impl::VocTask t;
    t.nFrames = nFrames;
    t.codes.assign(codes, codes + (size_t) nFrames * FRAME_CODES);
    t.pcm.resize((size_t) nFrames * SR_PER_FRAME);
    impl->queue.push_back(std::move(t));
    impl->cv.notify_all();
    return 0;
}

int32_t QnnVocoder::wait(float * pcmOut) {
    if (!pcmOut) return -2;
    std::unique_lock<std::mutex> lk(impl->lock);
    if (!impl->ready) return -1;
    impl->cv.wait(lk, [this] {
        return impl->quitting || (!impl->queue.empty() && impl->queue.front().done);
    });
    if (impl->queue.empty()) return -1; // quitting:队列被清,调用方应放弃本次合成
    Impl::VocTask t = std::move(impl->queue.front());
    impl->queue.pop_front();
    impl->cv.notify_all(); // 唤醒 worker 处理下一块
    if (t.failed) return -3;
    std::memcpy(pcmOut, t.pcm.data(), t.pcm.size() * sizeof(float));
    return 0;
}

} // namespace talkify
