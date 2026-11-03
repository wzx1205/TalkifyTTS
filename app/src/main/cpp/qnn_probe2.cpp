// qnn_probe2 —— QAIRT 高层 API 探针:DLC → HTP 离线 Prepare → NPU 推理计时
//
// v1(qnn_probe.cpp)证明:App 域 cDSP 通路可用,但运行时构图(finalize)
// 被 ROM 拒(0x36b1 / err 1002)。v2 改走 QairtSystemBuilder:
//   libQairtSystem.so → SystemDlc(量化后的 cp_int8.dlc) → build()(离线
//   Prepare,等价 dlc-prepare 在 App 域执行)→ execute() 计时。
// HTP 后端 libQairtHtp.so / libQairtHtpV79Skel.so 均随 APK 解压在
// nativeLibraryDir,ADSP_LIBRARY_PATH 在 native 侧 setenv。

#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <string>
#include <vector>

#include "QairtCpp/QairtLog.hpp"
#include "QairtCpp/System/QairtSystemApi.hpp"
#include "QairtCpp/System/QairtSystemBuilder.hpp"
#include "QairtCpp/System/Qairt.hpp"

#define TAG "QnnHtpProbe"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

double now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

size_t dtype_bytes_qairt(unsigned t) {
    // Qairt DataType 与 Qnn_DataType_t 同源
    switch (t) {
        case 0x0408: return 2;   // FLOAT_16
        case 0x0409: return 4;   // FLOAT_32
        case 0x040A:
        case 0x040B: return 4;   // INT_32 / INT_64(取 4 保底,数值内容不影响计时)
        case 0x0401: return 1;   // INT_8
        default:     return 4;
    }
}

size_t tensor_bytes(qairt::SystemContextTensorInfo * ti) {
    size_t elems = 1;
    for (uint32_t d = 0; d < ti->getRank(); ++d) elems *= ti->getDimensions()[d];
    return elems * dtype_bytes_qairt(static_cast<unsigned>(ti->getDataType()));
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_github_lonepheasantwarrior_talkify_qnn_QnnProbe_nativeRunDlc(
        JNIEnv * env, jobject /*thiz*/,
        jstring j_native_dir, jstring j_dlc_path, jint iters) {

    auto fail = [&](const std::string & step, const std::string & detail) -> jstring {
        std::string s = "FAIL@" + step + ": " + detail;
        LOGE("%s", s.c_str());
        return env->NewStringUTF(s.c_str());
    };

    const char * nd = env->GetStringUTFChars(j_native_dir, nullptr);
    std::string nativeDir(nd ? nd : "");
    env->ReleaseStringUTFChars(j_native_dir, nd);
    const char * dp = env->GetStringUTFChars(j_dlc_path, nullptr);
    std::string dlcPath(dp ? dp : "");
    env->ReleaseStringUTFChars(j_dlc_path, dp);

    std::string adsp = nativeDir + ";/system/lib/rfsa/adsp;/vendor/dsp/cdsp;/dsp";
    setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);

    try {
        // ---- SystemApi(加载 libQairtSystem.so)----
        auto systemApi = std::make_shared<qairt::SystemApi>(
            (nativeDir + "/libQairtSystem.so").c_str());
        LOGI("SystemApi loaded");

        // SystemDlc 构造:(apiTable, ApiTypeRef<SystemLog>&&, path);
        // log 引用可缺省(optional),直接传两个参数让 log 取默认
        auto systemDlc = systemApi->makeShared<qairt::SystemDlc>(dlcPath);
        LOGI("DLC opened: %s", dlcPath.c_str());

        auto builder = systemApi->make<qairt::QairtSystemBuilder>();
        builder.setDlc(systemDlc)
               .setBackendType(qairt::BackendType::Htp)
               .setLogLevel(qairt::LogLevel::Warn)
               .setPerfProfile(qairt::PerfProfile::HighPerformance);

        std::string err;
        if (!builder.validate(err)) {
            return fail("validate", err);
        }

        double t0 = now_ms();
        auto qairt = builder.build();   // DLC → HTP 离线 Prepare(App 域)
        double prepMs = now_ms() - t0;
        LOGI("build(prepare) done in %.0fms", prepMs);

        // ---- 张量元数据 ----
        uint32_t nGraphs = qairt.getNumGraphInfos();
        if (nGraphs == 0) return fail("graphs", "0 graphs in DLC");
        auto & graphInfo = qairt.getGraphInfoAt(0);
        auto & inputs = graphInfo.getGraphInputs();
        auto & outputs = graphInfo.getGraphOutputs();
        LOGI("graph=%s inputs=%zu outputs=%zu",
             graphInfo.getGraphName().c_str(), inputs.size(), outputs.size());

        // ---- 缓冲区 ----
        std::vector<std::vector<uint8_t>> inBufs, outBufs;
        std::vector<std::pair<qairt::SystemContextTensorInfo *, uint8_t *>> inPairs, outPairs;
        for (auto & ti : inputs) {
            size_t bytes = tensor_bytes(&ti);
            inBufs.emplace_back(bytes, 0);
            inPairs.emplace_back(&ti, inBufs.back().data());
            LOGI("in %s bytes=%zu dtype=0x%x", ti.getName().c_str(), bytes,
                 (unsigned) ti.getDataType());
        }
        for (auto & ti : outputs) {
            size_t bytes = tensor_bytes(&ti);
            outBufs.emplace_back(bytes, 0);
            outPairs.emplace_back(&ti, outBufs.back().data());
            LOGI("out %s bytes=%zu", ti.getName().c_str(), bytes);
        }
        // hidden 部分填非零 pattern,ids 部分保持 0(合法索引)
        for (auto & b : inBufs) {
            for (size_t i = 0; i < b.size(); i += 2) b[i] = 0x38; // fp16 ≈0.5
        }

        // ---- 预热 + 计时 ----
        for (int i = 0; i < 3; ++i) qairt.execute(inPairs, outPairs);
        const int runs = iters > 0 ? iters : 50;
        double best = 1e9, worst = 0, total = 0;
        int ok = 0;
        for (int i = 0; i < runs; ++i) {
            double s = now_ms();
            qairt.execute(inPairs, outPairs);
            double dt = now_ms() - s;
            ++ok; total += dt;
            if (dt < best) best = dt;
            if (dt > worst) worst = dt;
        }

        char result[512];
        snprintf(result, sizeof(result),
                 "OK prepareMs=%.0f exec n=%d avg=%.2fms min=%.2fms max=%.2fms",
                 prepMs, ok, total / ok, best, worst);
        LOGI("PROBE2 RESULT %s", result);
        return env->NewStringUTF(result);

    } catch (const std::exception & e) {
        return fail("exception", e.what());
    } catch (...) {
        return fail("exception", "unknown");
    }
}
