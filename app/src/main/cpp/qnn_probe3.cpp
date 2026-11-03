// qnn_probe3 —— context binary 探针(最终路线)
//
// 流程:libQnnHtp dlopen → SystemContext 解析 .ctx 元数据(图名/IO 张量)
//   → contextCreateFromBinary(HTP 离线产物直接进 NPU) → graphRetrieve
//   → execute 计时。
// .ctx 由 Linux 版 QAIRT Prepare 生成(Windows 版有 Gather+VTCM bug)。

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <sys/time.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "QnnInterface.h"
#include "QnnTypes.h"
#include "System/QnnSystemContext.h"
#include "QnnGraph.h"
#include "System/QnnSystemInterface.h"
#include "QnnTensor.h"

#define TAG "QnnHtpProbe"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

double now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

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

extern "C" JNIEXPORT jstring JNICALL
Java_com_github_lonepheasantwarrior_talkify_qnn_QnnProbe_nativeRunContextBinary(
        JNIEnv * env, jobject /*thiz*/,
        jstring j_native_dir, jstring j_ctx_path, jint iters) {

    auto fail = [&](const std::string & step, const std::string & detail) -> jstring {
        std::string s = "FAIL@" + step + ": " + detail;
        LOGE("%s", s.c_str());
        return env->NewStringUTF(s.c_str());
    };

    const char * nd = env->GetStringUTFChars(j_native_dir, nullptr);
    std::string nativeDir(nd ? nd : "");
    env->ReleaseStringUTFChars(j_native_dir, nd);
    const char * cp = env->GetStringUTFChars(j_ctx_path, nullptr);
    std::string ctxPath(cp ? cp : "");
    env->ReleaseStringUTFChars(j_ctx_path, cp);

    std::string adsp = nativeDir + ";/system/lib/rfsa/adsp;/vendor/dsp/cdsp;/dsp";
    setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);

    // ---- 读 context binary 文件 ----
    FILE * f = fopen(ctxPath.c_str(), "rb");
    if (!f) return fail("open-ctx", ctxPath);
    fseek(f, 0, SEEK_END);
    long fileSize = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::vector<uint8_t> bin(fileSize);
    size_t rd = fread(bin.data(), 1, fileSize, f);
    fclose(f);
    if (rd != (size_t) fileSize) return fail("read-ctx", "short read");
    LOGI("ctx loaded: %s (%ld bytes)", ctxPath.c_str(), fileSize);

    // ---- backend ----
    void * hBackend = dlopen("libQnnHtp.so", RTLD_NOW);
    if (!hBackend) {
        const char * e = dlerror();
        return fail("dlopen-libQnnHtp", e ? e : "?");
    }
    using GetProvidersFn = Qnn_ErrorHandle_t (*)(const QnnInterface_t ***, uint32_t *);
    auto getProviders = reinterpret_cast<GetProvidersFn>(
        dlsym(hBackend, "QnnInterface_getProviders"));
    if (!getProviders) return fail("dlsym-getProviders", "?");

    const QnnInterface_t ** providerList = nullptr;
    uint32_t nProviders = 0;
    if (getProviders(&providerList, &nProviders) != QNN_SUCCESS || !nProviders || !providerList)
        return fail("getProviders", "none");
    QnnInterface_t impl = providerList[0][0];
    QNN_INTERFACE_VER_TYPE iface = impl.QNN_INTERFACE_VER_NAME;

    Qnn_LogHandle_t logger = nullptr;
    if (iface.logCreate) iface.logCreate(nullptr, QNN_LOG_LEVEL_ERROR, &logger);

    Qnn_DeviceHandle_t device = nullptr;
    if (iface.deviceCreate) {
        if (iface.deviceCreate(logger, nullptr, &device) != QNN_SUCCESS) {
            LOGI("deviceCreate failed (non-fatal), using null device");
            device = nullptr;
        }
    }
    Qnn_BackendHandle_t backend = nullptr;
    if (iface.backendCreate(logger, nullptr, &backend) != QNN_SUCCESS)
        return fail("backendCreate", "?");
    LOGI("backend created");

    // ---- SystemContext:解析元数据(拿图名和 IO 规格) ----
    void * hSystem = dlopen("libQnnSystem.so", RTLD_NOW);
    if (!hSystem) {
        const char * e = dlerror();
        return fail("dlopen-libQnnSystem", e ? e : "?");
    }
    using SysGetFn = Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t ***, uint32_t *);
    auto sysGetProviders = reinterpret_cast<SysGetFn>(
        dlsym(hSystem, "QnnSystemInterface_getProviders"));
    if (!sysGetProviders) return fail("dlsym-sysProviders", "?");

    const QnnSystemInterface_t ** sysList = nullptr;
    uint32_t nSys = 0;
    if (sysGetProviders(&sysList, &nSys) != QNN_SUCCESS || !nSys || !sysList)
        return fail("sysGetProviders", "none");
    QnnSystemInterface_t sysWrap = sysList[0][0];
    // 函数表嵌在版本子结构里,与 QnnInterface 同款
    auto sysIface = sysWrap.QNN_SYSTEM_INTERFACE_VER_NAME;
    LOGI("system interface acquired");

    QnnSystemContext_Handle_t sysCtx = nullptr;
    if (sysIface.systemContextCreate(&sysCtx) != QNN_SUCCESS)
        return fail("systemContextCreate", "?");

    const QnnSystemContext_BinaryInfo_t * binInfo = nullptr;
    Qnn_ContextBinarySize_t binInfoSize = 0;
    if (sysIface.systemContextGetBinaryInfo(sysCtx, bin.data(), bin.size(),
                                            &binInfo, &binInfoSize) != QNN_SUCCESS || !binInfo)
        return fail("getBinaryInfo", "?");

    // 支持 V1/V2/V3:V3 的 GraphInfo 前五个字段与 V1 布局兼容(graphName/inputs/outputs)
    const char *  graphNameC = nullptr;
    uint32_t nIn = 0, nOut = 0;
    Qnn_Tensor_t * inT = nullptr, * outT = nullptr;
    switch (binInfo->version) {
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3: {
            const auto & v3 = binInfo->contextBinaryInfoV3;
            if (v3.numGraphs == 0) return fail("graphs", "0(v3)");
            graphNameC = v3.graphs[0].graphInfoV3.graphName;
            nIn  = v3.graphs[0].graphInfoV3.numGraphInputs;
            inT  = v3.graphs[0].graphInfoV3.graphInputs;
            nOut = v3.graphs[0].graphInfoV3.numGraphOutputs;
            outT = v3.graphs[0].graphInfoV3.graphOutputs;
            LOGI("binaryInfo v3, socModel=%u", v3.socModel);
            break;
        }
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2: {
            const auto & v2 = binInfo->contextBinaryInfoV2;
            if (v2.numGraphs == 0) return fail("graphs", "0(v2)");
            graphNameC = v2.graphs[0].graphInfoV2.graphName;
            nIn  = v2.graphs[0].graphInfoV2.numGraphInputs;
            inT  = v2.graphs[0].graphInfoV2.graphInputs;
            nOut = v2.graphs[0].graphInfoV2.numGraphOutputs;
            outT = v2.graphs[0].graphInfoV2.graphOutputs;
            break;
        }
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1: {
            const auto & v1 = binInfo->contextBinaryInfoV1;
            if (v1.numGraphs == 0) return fail("graphs", "0(v1)");
            graphNameC = v1.graphs[0].graphInfoV1.graphName;
            nIn  = v1.graphs[0].graphInfoV1.numGraphInputs;
            inT  = v1.graphs[0].graphInfoV1.graphInputs;
            nOut = v1.graphs[0].graphInfoV1.numGraphOutputs;
            outT = v1.graphs[0].graphInfoV1.graphOutputs;
            break;
        }
        default:
            return fail("binaryInfo-version", std::to_string((int) binInfo->version));
    }
    if (!graphNameC) return fail("graphs", "null name");
    std::string graphName = graphNameC;
    LOGI("graph=%s in=%u out=%u", graphName.c_str(), nIn, nOut);

    // ---- fromBinary:离线产物直接进 HTP ----
    Qnn_ContextHandle_t context = nullptr;
    double t0 = now_ms();
    auto e = iface.contextCreateFromBinary(backend, device, nullptr,
                                           bin.data(), bin.size(), &context, nullptr);
    double fromBinMs = now_ms() - t0;
    if (e != QNN_SUCCESS) {
        char buf[64];
        snprintf(buf, sizeof(buf), "err=0x%llx", (unsigned long long) e);
        return fail("contextCreateFromBinary", buf);
    }
    LOGI("context from binary in %.0fms", fromBinMs);

    Qnn_GraphHandle_t graph = nullptr;
    if (iface.graphRetrieve(context, graphName.c_str(), &graph) != QNN_SUCCESS)
        return fail("graphRetrieve", graphName);

    // ---- IO 缓冲(按元数据规格) ----
    std::vector<Qnn_Tensor_t> in(inT, inT + nIn);
    std::vector<Qnn_Tensor_t> out(outT, outT + nOut);
    std::vector<std::vector<uint8_t>> inBufs(nIn), outBufs(nOut);
    for (uint32_t i = 0; i < nIn; ++i) {
        size_t bytes = tensor_bytes(in[i]);
        inBufs[i].resize(bytes ? bytes : 1, 0);
        // hidden(fp16)填非零;ids(int32)保持 0
        if (in[i].v2.dataType == QNN_DATATYPE_FLOAT_16 ||
            in[i].v2.dataType == QNN_DATATYPE_FLOAT_32) {
            for (size_t k = 0; k < inBufs[i].size(); k += 2) inBufs[i][k] = 0x38;
        }
        in[i].v2.clientBuf.data = inBufs[i].data();
        in[i].v2.clientBuf.dataSize = inBufs[i].size();
        in[i].v2.type = QNN_TENSOR_TYPE_APP_READ;
        LOGI("in[%u] %s dtype=0x%x bytes=%zu", i, in[i].v2.name,
             (unsigned) in[i].v2.dataType, bytes);
    }
    for (uint32_t i = 0; i < nOut; ++i) {
        size_t bytes = tensor_bytes(out[i]);
        outBufs[i].resize(bytes ? bytes : 1, 0);
        out[i].v2.clientBuf.data = outBufs[i].data();
        out[i].v2.clientBuf.dataSize = outBufs[i].size();
        out[i].v2.type = QNN_TENSOR_TYPE_APP_WRITE;
        LOGI("out[%u] %s bytes=%zu", i, out[i].v2.name, bytes);
    }

    // ---- 预热 + 计时 ----
    for (int i = 0; i < 5; ++i)
        iface.graphExecute(graph, in.data(), nIn, out.data(), nOut, nullptr, nullptr);
    const int runs = iters > 0 ? iters : 100;
    double best = 1e9, worst = 0, total = 0;
    int ok = 0;
    for (int i = 0; i < runs; ++i) {
        double s = now_ms();
        auto ee = iface.graphExecute(graph, in.data(), nIn, out.data(), nOut, nullptr, nullptr);
        double dt = now_ms() - s;
        if (ee == QNN_SUCCESS) {
            ++ok; total += dt;
            if (dt < best) best = dt;
            if (dt > worst) worst = dt;
        } else {
            char buf[64];
            snprintf(buf, sizeof(buf), "execute err=0x%llx", (unsigned long long) ee);
            return fail("graphExecute", buf);
        }
    }

    char result[512];
    snprintf(result, sizeof(result),
             "OK fromBinMs=%.0f exec n=%d avg=%.2fms min=%.2fms max=%.2fms",
             fromBinMs, ok, total / ok, best, worst);
    LOGI("PROBE3 RESULT %s", result);
    return env->NewStringUTF(result);
}
