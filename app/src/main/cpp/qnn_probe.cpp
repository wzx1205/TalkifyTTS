// qnn_probe —— App 内 Hexagon NPU(HTP)探针
//
// 目的:在 App 进程上下文里验证 QNN HTP 通路并实测 code_predictor 每帧推理耗时。
// adb shell 无法打开 /dev/fastrpc-cdsp(组权限),只有 App 域经公开库
// libcdsprpc.so 才能到 NPU——所以探针必须住在 App 里跑。
//
// 流程(libQnnHtp 从 APK nativeLibraryDir dlopen,模型库从 App 私有目录 dlopen):
//   interface(QnnInterface_getProviders) → device → backend → context
//   → QnnModel_composeGraphs(模型库导出) → graph finalize → N 次 execute 计时
//
// 仅当构建机存在 QAIRT SDK 时编译(CMake 条件),否则该 JNI 不存在,
// Kotlin 侧捕获 NoSuchMethodError 优雅降级。

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <sys/time.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "QnnInterface.h"
#include "QnnTypes.h"
#include "QnnModel.hpp"
#include "QnnWrapperUtils.hpp"

using namespace qnn_wrapper_api;

#define TAG "QnnHtpProbe"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

double now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

// QNN 后端日志 → logcat(WARN 以上转发;INFO 过滤掉刷屏的逐 token 行)
void qnn_log_cb(const char * fmt, QnnLog_Level_t level, uint64_t /*ts*/, va_list args) {
    android_LogPriority p = level >= QNN_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR :
                            level >= QNN_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN :
                                                            ANDROID_LOG_INFO;
    __android_log_vprint(p, "QnnHtpNative", fmt, args);
}

size_t dtype_bytes(Qnn_DataType_t t) {
    switch (t) {
        case QNN_DATATYPE_FLOAT_16: return 2;
        case QNN_DATATYPE_FLOAT_32:
        case QNN_DATATYPE_INT_32:
        case QNN_DATATYPE_UINT_32:  return 4;
        case QNN_DATATYPE_INT_64:
        case QNN_DATATYPE_UINT_64:  return 8;
        case QNN_DATATYPE_INT_8:
        case QNN_DATATYPE_UINT_8:   return 1;
        case QNN_DATATYPE_INT_16:
        case QNN_DATATYPE_UINT_16:  return 2;
        default:                    return 0;
    }
}

// fp16 位模式:简单地把 fp32 的低半部分近似成 0x3800(0.5)量级,内容无所谓,只要非退化
void fill_random_fp16(uint8_t * buf, size_t bytes) {
    auto * h = reinterpret_cast<uint16_t *>(buf);
    for (size_t i = 0; i < bytes / 2; ++i) {
        // 0x3800 ≈ 0.5f;加个低位变化避免全同值触发异常优化路径
        h[i] = static_cast<uint16_t>(0x3800 | (i & 0xFF));
    }
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_github_lonepheasantwarrior_talkify_qnn_QnnProbe_nativeRunHtp(
        JNIEnv * env, jobject /*thiz*/,
        jstring j_native_lib_dir, jstring j_model_lib_path, jint iters) {

    std::string summary;
    auto fail = [&](const std::string & step, const std::string & detail) {
        summary = "FAIL@" + step + ": " + detail;
        LOGE("%s", summary.c_str());
        return env->NewStringUTF(summary.c_str());
    };

    const char * nld = env->GetStringUTFChars(j_native_lib_dir, nullptr);
    std::string nativeDir(nld ? nld : "");
    env->ReleaseStringUTFChars(j_native_lib_dir, nld);
    const char * mlp = env->GetStringUTFChars(j_model_lib_path, nullptr);
    std::string modelPath(mlp ? mlp : "");
    env->ReleaseStringUTFChars(j_model_lib_path, mlp);

    // HTP skel(libQairtHtpV79Skel.so)随 APK 解压在 nativeLibraryDir;
    // DSP 侧加载器按该路径搜索,并补上系统标准目录(skel 的依赖可能在那里)
    std::string adsp = nativeDir + ";/system/lib/rfsa/adsp;/vendor/dsp/cdsp;/dsp";
    setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);

    // ---- 1. backend interface ----
    // 裸名 dlopen:经类加载器命名空间解析到 APK 内/nativeLibraryDir 的库
    void * hBackend = dlopen("libQnnHtp.so", RTLD_NOW);
    if (!hBackend) {
        const char * e = dlerror();   // dlerror 只能取一次,二次调用返回 null
        return fail("dlopen-libQnnHtp", e ? e : "?");
    }

    // 签名:QnnInterface_getProviders(const QnnInterface_t*** providerList, uint32_t*)
    // providerList[i] 指向一组 QnnInterface_t,取第 0 组第 0 个实现
    using GetProvidersFn = Qnn_ErrorHandle_t (*)(const QnnInterface_t ***, uint32_t *);
    auto getProviders = reinterpret_cast<GetProvidersFn>(dlsym(hBackend, "QnnInterface_getProviders"));
    if (!getProviders) return fail("dlsym-getProviders", "?");

    const QnnInterface_t ** providerList = nullptr;
    uint32_t nProviders = 0;
    if (getProviders(&providerList, &nProviders) != QNN_SUCCESS || nProviders == 0 || !providerList) {
        return fail("getProviders", "no provider");
    }
    QnnInterface_t impl = providerList[0][0];
    QNN_INTERFACE_VER_TYPE iface = impl.QNN_INTERFACE_VER_NAME;
    LOGI("interface acquired, apiVersion=0x%x", impl.apiVersion);

    // 打开 QNN 日志通道:device 失败的真实原因(skel 加载/RPC/电源)会打到 logcat
    Qnn_LogHandle_t logger = nullptr;
    if (iface.logCreate) {
        iface.logCreate(qnn_log_cb, QNN_LOG_LEVEL_DEBUG, &logger);
    }

    // ---- 2. device / backend / context ----
    // 部分 QNN 后端不实现 device 接口(fn 为 null),设备可空,backend/context 照建
    Qnn_DeviceHandle_t device = nullptr;
    Qnn_BackendHandle_t backend = nullptr;
    Qnn_ContextHandle_t context = nullptr;
    if (iface.deviceCreate) {
        auto e = iface.deviceCreate(nullptr, nullptr, &device);
        if (e != QNN_SUCCESS) {
            // 实测(SM8750/ColorOS):HTP 基础初始化成功,deviceCreate 在
            // perf-infra(电源/DCVS)配置上报 0x36b1,但不阻塞推理——降级继续
            char buf[80];
            snprintf(buf, sizeof(buf), "err=0x%llx, degrade to null device",
                     (unsigned long long) e);
            LOGE("deviceCreate %s", buf);
            LOGI("deviceCreate %s", buf);
            device = nullptr;
        } else {
            LOGI("device created");
        }
    } else {
        LOGI("deviceCreate not provided by backend, skip");
    }
    if (!iface.backendCreate) return fail("backendCreate", "fn null");
    if (iface.backendCreate(nullptr, nullptr, &backend) != QNN_SUCCESS) {
        return fail("backendCreate", "?");
    }
    if (!iface.contextCreate) return fail("contextCreate", "fn null");
    if (iface.contextCreate(backend, device, nullptr, &context) != QNN_SUCCESS) {
        return fail("contextCreate", "?");
    }
    LOGI("backend/context created");

    // ---- 3. 模型库 compose ----
    void * hModel = dlopen(modelPath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!hModel) {
        const char * e = dlerror();
        return fail("dlopen-model", e ? e : modelPath);
    }

    using ComposeFn = ModelError_t (*)(Qnn_BackendHandle_t, QNN_INTERFACE_VER_TYPE,
                                       Qnn_ContextHandle_t, const GraphConfigInfo_t **,
                                       uint32_t, GraphInfoPtr_t **, uint32_t *,
                                       bool, QnnLog_Callback_t, QnnLog_Level_t);
    auto compose = reinterpret_cast<ComposeFn>(dlsym(hModel, "QnnModel_composeGraphs"));
    if (!compose) return fail("dlsym-compose", "?");

    GraphInfoPtr_t * graphsInfo = nullptr;
    uint32_t nGraphs = 0;
    double t0 = now_ms();
    ModelError_t me = compose(backend, iface, context, nullptr, 0,
                              &graphsInfo, &nGraphs, false, nullptr, QNN_LOG_LEVEL_ERROR);
    double composeMs = now_ms() - t0;
    if (me != MODEL_NO_ERROR) {
        char buf[64];
        snprintf(buf, sizeof(buf), "ModelError=%d", (int) me);
        return fail("composeGraphs", buf);
    }
    LOGI("composed %u graph(s) in %.0fms", nGraphs, composeMs);
    if (nGraphs == 0) return fail("composeGraphs", "0 graphs");

    // ---- 4. finalize + 准备输入输出 ----
    auto * gi = graphsInfo[0];
    if (iface.graphFinalize(gi->graph, nullptr, nullptr) != QNN_SUCCESS) {
        return fail("graphFinalize", "?");
    }

    std::vector<Qnn_Tensor_t> in(gi->inputTensors, gi->inputTensors + gi->numInputTensors);
    std::vector<Qnn_Tensor_t> out(gi->outputTensors, gi->outputTensors + gi->numOutputTensors);
    std::vector<std::vector<uint8_t>> inBufs(in.size());
    std::vector<std::vector<uint8_t>> outBufs(out.size());

    for (uint32_t i = 0; i < in.size(); ++i) {
        const Qnn_Tensor_t & t = gi->inputTensors[i];
        size_t elems = 1;
        for (uint32_t d = 0; d < t.v2.rank; ++d) elems *= t.v2.dimensions[d];
        size_t bytes = elems * dtype_bytes(t.v2.dataType);
        inBufs[i].resize(bytes ? bytes : 1);
        fill_random_fp16(inBufs[i].data(), inBufs[i].size());
        // codec_ids(int64)等整数输入:低位放小索引值
        if (t.v2.dataType == QNN_DATATYPE_INT_64 ||
            t.v2.dataType == QNN_DATATYPE_INT_32) {
            memset(inBufs[i].data(), 0, inBufs[i].size());
        }
        in[i].v2.clientBuf.data = inBufs[i].data();
        in[i].v2.clientBuf.dataSize = inBufs[i].size();
        in[i].v2.type = QNN_TENSOR_TYPE_APP_READ;
        LOGI("in[%u] %s dtype=%d bytes=%zu", i, t.v2.name, (int) t.v2.dataType, bytes);
    }
    for (uint32_t i = 0; i < out.size(); ++i) {
        const Qnn_Tensor_t & t = gi->outputTensors[i];
        size_t elems = 1;
        for (uint32_t d = 0; d < t.v2.rank; ++d) elems *= t.v2.dimensions[d];
        size_t bytes = elems * dtype_bytes(t.v2.dataType);
        outBufs[i].resize(bytes ? bytes : 1);
        out[i].v2.clientBuf.data = outBufs[i].data();
        out[i].v2.clientBuf.dataSize = outBufs[i].size();
        out[i].v2.type = QNN_TENSOR_TYPE_APP_WRITE;
        LOGI("out[%u] %s dtype=%d bytes=%zu", i, t.v2.name, (int) t.v2.dataType, bytes);
    }

    // ---- 5. execute 计时 ----
    const int warmup = 5;
    const int runs = iters > 0 ? iters : 50;
    for (int i = 0; i < warmup; ++i) {
        iface.graphExecute(gi->graph, in.data(), in.size(), out.data(), out.size(),
                           nullptr, nullptr);
    }
    double best = 1e9, worst = 0, total = 0;
    int okCount = 0;
    for (int i = 0; i < runs; ++i) {
        double s = now_ms();
        auto e = iface.graphExecute(gi->graph, in.data(), in.size(), out.data(), out.size(),
                                    nullptr, nullptr);
        double dt = now_ms() - s;
        if (e == QNN_SUCCESS) {
            ++okCount; total += dt;
            if (dt < best) best = dt;
            if (dt > worst) worst = dt;
        } else if (i == 0) {
            char buf[64];
            snprintf(buf, sizeof(buf), "err=%d at iter0", (int) e);
            return fail("graphExecute", buf);
        }
    }

    char result[512];
    snprintf(result, sizeof(result),
             "OK graphs=%u composeMs=%.0f exec: n=%d/%d avg=%.2fms min=%.2fms max=%.2fms",
             nGraphs, composeMs, okCount, runs,
             okCount ? total / okCount : 0.0, best == 1e9 ? 0 : best, worst);
    LOGI("PROBE RESULT %s", result);

    // 清理(compose 出的 graph/context 生命周期由 backend 管理,进程将死,不细拆)
    return env->NewStringUTF(result);
}

} // extern "C"
