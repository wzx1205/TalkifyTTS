// talkify_llm —— Qwen3-TTS JNI 桥（llama.cpp backbone + mtmd 音频管线）
//
// 调用链对齐上游 tools/tts/tts.cpp：
//   backbone GGUF (llama) --隐藏态--> mmproj GGUF (mtmd: 说话人编码/codec/声码器) --> PCM
//
//   nativeTtsInit(backbonePath, mmprojPath, nCtx, nThreads) -> handle
//   nativeTtsSynthesize(handle, text, lang, speakerPcm, temp, topK, topP,
//                       maxFrames, callback) -> sampleRate（负值为错误）
//   nativeTtsFree(handle)
//
// 流式策略：codec 12.5 帧/秒，每 25 帧（约 2 秒音频）调用一次 get_output
// 取增量 PCM 回调给 Kotlin；回调返回 false 立即中止（用户停止朗读）。
// 回调与合成都在调用线程（上层保证为 IO 线程），handle 内部互斥串行化。

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include "backend_guard.h"
#if defined(HAS_QAIRT_SDK)
#include "qnn_vocoder.h"
#endif

#define TAG "TalkifyQwenTTS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// 发射节奏(帧):ggml 声码器按 72 帧整窗计算(不足 pad 0、成本同整窗),必须按窗
// 对齐发射,否则每个部分窗口都付整窗代价(实测 RTF 差 1.7 倍);NPU 异步流水线
// 按 50 帧发射——块在 25 帧边界入队,留最新一块与下一批帧生成重叠,
// 发射时取的是上一块(已完成),等价于一条恒定 2s 延迟的流水线。
constexpr int EMIT_EVERY_FRAMES_GGML = 72;
constexpr int EMIT_EVERY_FRAMES_NPU  = 50;

struct QwenTtsSession {
    llama_model   * model = nullptr;
    llama_context * ctx   = nullptr;
    mtmd_context  * mctx  = nullptr;
    int             sample_rate = 24000;
    bool            vocoder_ready = false;
    std::mutex      lock;
};

#if defined(HAS_QAIRT_SDK)
// 外部声码器回调:helper 保证 n_frames ≤ 25,直接转发单例
int32_t vocoder_cb(const int32_t * codes, int32_t n_frames, float * pcm_out, void * /*ud*/) {
    return talkify::QnnVocoder::instance().decode(codes, n_frames, pcm_out);
}

// 异步流水线回调:submit 入队即回(NPU 解码在工作线程上与 CPU 帧生成并行),
// wait 阻塞到 FIFO 头部块完成。seq 由 helper 单调分配,严格按序 wait。
int32_t vocoder_submit_cb(const int32_t * codes, int32_t n_frames, int64_t /*seq*/, void * /*ud*/) {
    return talkify::QnnVocoder::instance().submit(codes, n_frames);
}
int32_t vocoder_wait_cb(int64_t /*seq*/, float * pcm_out, void * /*ud*/) {
    return talkify::QnnVocoder::instance().wait(pcm_out);
}
#endif

void log_callback(ggml_log_level level, const char * text, void * /*user*/) {
    // 模型加载期的 INFO 含 buffer/后端分配信息（layers → device），是排查
    // GPU 卸载是否生效的关键；其余时期的 INFO 逐帧刷屏，只留 WARN/ERROR
    if (level >= GGML_LOG_LEVEL_WARN) {
        LOGE("%s", text);
    } else if (level == GGML_LOG_LEVEL_INFO && strstr(text, "buffer") != nullptr) {
        LOGI("%s", text);
    }
}

// 归一化 float 样本 → PCM16 小端字节
void floats_to_pcm16(const float * src, size_t n, int16_t * dst) {
    for (size_t i = 0; i < n; ++i) {
        auto v = static_cast<int32_t>(src[i] * 32767.0f);
        if (v > 32767) v = 32767;
        if (v < -32768) v = -32768;
        dst[i] = static_cast<int16_t>(v);
    }
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_github_lonepheasantwarrior_talkify_llm_QwenTtsBridge_nativeTtsInit(
        JNIEnv * env, jobject /*thiz*/,
        jstring j_backbone, jstring j_mmproj, jint n_ctx, jint n_threads,
        jboolean use_gpu, jstring j_vocoder_ctx, jstring j_native_dir) {

    const char * backbone_path = env->GetStringUTFChars(j_backbone, nullptr);
    std::string backbone(backbone_path ? backbone_path : "");
    env->ReleaseStringUTFChars(j_backbone, backbone_path);

    const char * mmproj_path = env->GetStringUTFChars(j_mmproj, nullptr);
    std::string mmproj(mmproj_path ? mmproj_path : "");
    env->ReleaseStringUTFChars(j_mmproj, mmproj_path);

    const char * voc_ctx = j_vocoder_ctx != nullptr ? env->GetStringUTFChars(j_vocoder_ctx, nullptr) : nullptr;
    std::string vocoder_ctx(voc_ctx ? voc_ctx : "");
    if (voc_ctx) env->ReleaseStringUTFChars(j_vocoder_ctx, voc_ctx);
    const char * native_dir_c = j_native_dir != nullptr ? env->GetStringUTFChars(j_native_dir, nullptr) : nullptr;
    std::string native_dir(native_dir_c ? native_dir_c : "");
    if (native_dir_c) env->ReleaseStringUTFChars(j_native_dir, native_dir_c);

    // ---- NPU 声码器(可选):ctx 存在且初始化成功才启用,否则 ggml 兜底 ----
    bool vocoder_ready = false;
#if defined(HAS_QAIRT_SDK)
    if (!vocoder_ctx.empty() && !native_dir.empty()) {
        vocoder_ready = talkify::QnnVocoder::instance().init(native_dir.c_str(), vocoder_ctx.c_str());
        LOGI("qnn vocoder init: %s -> %s", vocoder_ctx.c_str(), vocoder_ready ? "ok" : "failed (ggml fallback)");
    }
#else
    if (!vocoder_ctx.empty()) {
        LOGI("qnn vocoder not compiled in (no QAIRT SDK), using ggml");
    }
#endif

    llama_log_set(log_callback, nullptr);
    talkify::backend_acquire();

    if (use_gpu) {
        // 列出注册的后端设备，确认 OpenCL/Adreno 是否被识别
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            switch (ggml_backend_dev_type(dev)) {
                case GGML_BACKEND_DEVICE_TYPE_GPU:
                case GGML_BACKEND_DEVICE_TYPE_IGPU:
                    LOGI("backend[%zu]: %s (GPU)", i, ggml_backend_dev_name(dev));
                    break;
                case GGML_BACKEND_DEVICE_TYPE_ACCEL:
                    LOGI("backend[%zu]: %s (ACCEL)", i, ggml_backend_dev_name(dev));
                    break;
                default:
                    LOGI("backend[%zu]: %s (CPU)", i, ggml_backend_dev_name(dev));
                    break;
            }
        }
    }

    llama_model_params mparams = llama_model_default_params();
    // OpenCL 后端可用时把层卸载到 Adreno；纯 CPU 时保持 0
    mparams.n_gpu_layers = use_gpu ? 99 : 0;

    llama_model * model = llama_model_load_from_file(backbone.c_str(), mparams);
    if (!model) {
        LOGE("failed to load backbone: %s", backbone.c_str());
        talkify::backend_release();
        return 0;
    }

    // TTS 需要 embedding 输出（隐藏态喂给 mtmd 管线），必须显式开启
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = static_cast<uint32_t>(n_ctx > 0 ? n_ctx : 2048);
    cparams.n_batch         = 256;
    cparams.n_ubatch        = 256;
    cparams.n_threads       = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    cparams.embeddings      = true;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    cparams.no_perf         = true;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("failed to create llama_context for TTS");
        llama_model_free(model);
        talkify::backend_release();
        return 0;
    }

    mtmd_context_params mtmd_params = mtmd_context_params_default();
    // 实测(Adreno 720/OpenCL):mmproj 图上 GPU 后静默无输出(部分算子不支持),
    // 且仅主干卸载的加速只有 ~1.1x。mmproj 固定 CPU;是否值得保留主干 GPU
    // 卸载由 use_gpu 调用方决定(默认关)。
    mtmd_params.use_gpu = false;

    mtmd_context * mctx = mtmd_init_from_file(mmproj.c_str(), model, mtmd_params);
    if (!mctx) {
        LOGE("failed to load mmproj: %s", mmproj.c_str());
        llama_free(ctx);
        llama_model_free(model);
        talkify::backend_release();
        return 0;
    }

    mtmd_gen_audio_info info = mtmd_gen_audio_get_info(mctx);
    if (info.type == MTMD_GEN_AUDIO_TYPE_NONE) {
        LOGE("mmproj does not support audio generation: %s", mmproj.c_str());
        mtmd_free(mctx);
        llama_free(ctx);
        llama_model_free(model);
        talkify::backend_release();
        return 0;
    }

    auto * session = new QwenTtsSession();
    session->model = model;
    session->ctx   = ctx;
    session->mctx  = mctx;
    session->sample_rate = info.sample_rate > 0 ? info.sample_rate : 24000;
    session->vocoder_ready = vocoder_ready;

    LOGI("qwen3-tts init ok: %s + %s (sr=%d ctx=%u threads=%d vocoder=%s)",
         backbone.c_str(), mmproj.c_str(), session->sample_rate,
         cparams.n_ctx, cparams.n_threads, vocoder_ready ? "npu" : "ggml");
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jint JNICALL
Java_com_github_lonepheasantwarrior_talkify_llm_QwenTtsBridge_nativeTtsSynthesize(
        JNIEnv * env, jobject /*thiz*/, jlong handle,
        jstring j_text, jstring j_lang,
        jfloatArray j_speaker_pcm, jint speaker_sample_rate,
        jfloat temp, jint top_k, jfloat top_p, jint max_frames,
        jobject j_callback) {

    auto * session = reinterpret_cast<QwenTtsSession *>(handle);
    if (!session || !session->ctx || !session->mctx) return -1;

    std::lock_guard<std::mutex> guard(session->lock);

    const char * text_chars = env->GetStringUTFChars(j_text, nullptr);
    std::string text(text_chars ? text_chars : "");
    env->ReleaseStringUTFChars(j_text, text_chars);
    if (text.empty()) return -1;

    const char * lang_chars = j_lang != nullptr ? env->GetStringUTFChars(j_lang, nullptr) : nullptr;
    std::string lang(lang_chars ? lang_chars : "zh");
    if (lang_chars) env->ReleaseStringUTFChars(j_lang, lang_chars);

    // ---- 回调方法绑定：PcmCallback.onPcm([B I) Z ----
    jmethodID on_pcm = nullptr;
    if (j_callback != nullptr) {
        jclass cb_class = env->GetObjectClass(j_callback);
        on_pcm = env->GetMethodID(cb_class, "onPcm", "([BI)Z");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            on_pcm = nullptr;
        }
    }

    // ---- 说话人参考音频（24kHz 归一化 float），克隆音色用 ----
    mtmd_bitmap * speaker = nullptr;
    if (j_speaker_pcm != nullptr && speaker_sample_rate == 24000) {
        const jsize n = env->GetArrayLength(j_speaker_pcm);
        if (n > 0) {
            jfloat * data = env->GetFloatArrayElements(j_speaker_pcm, nullptr);
            if (data) {
                speaker = mtmd_bitmap_init_from_audio(static_cast<size_t>(n), data);
                env->ReleaseFloatArrayElements(j_speaker_pcm, data, JNI_ABORT);
            }
        }
    } else if (j_speaker_pcm != nullptr) {
        // Kotlin 侧已负责重采样到 24k；不到 24k 说明上游数据异常，放弃克隆保合成可用
        LOGE("speaker pcm sample rate %d != 24000, clone disabled", speaker_sample_rate);
    }

    // ---- 清理上一轮 KV，重建 mtmd 生成器 ----
    llama_memory_clear(llama_get_memory(session->ctx), true);
    mtmd_helper::gen_audio gen(session->ctx, session->mctx);

    mtmd_helper_gen_audio_inp inp{};
    inp.seq_id      = 0;
    inp.prompt      = text.c_str();
    inp.prompt_len  = text.size();
    inp.speaker_ref = speaker;
    inp.lang        = lang.c_str();
    inp.top_k       = top_k;
    inp.top_p       = top_p;
    inp.seed        = LLAMA_DEFAULT_SEED;
    inp.out_type    = MTMD_HELPER_GEN_AUDIO_OUTTYPE_PCM;
#if defined(HAS_QAIRT_SDK)
    if (session->vocoder_ready) {
        // 异步流水线优先(NPU 与帧生成并行);submit/wait 与 vocoder_fn 互斥
        inp.vocoder_submit_fn = &vocoder_submit_cb;
        inp.vocoder_wait_fn = &vocoder_wait_cb;
        inp.vocoder_ud = nullptr;
    }
#endif

    if (gen.set_input(&inp) != 0) {
        LOGE("set_input failed");
        if (speaker) mtmd_bitmap_free(speaker);
        return -2;
    }

    // ---- 阶段一：文本 prefill ----
    bool ok = true;
    for (;;) {
        int32_t ret = gen.step_prompt(256);
        if (ret < 0) { LOGE("step_prompt failed"); ok = false; break; }
        if (ret == 0) break;
    }

    // ---- 采样器 ----
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
        if (top_p < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    jint result_rate = 0;
    size_t emitted_samples = 0;
    bool cancelled = false;

    // 增量下发：get_output 返回累积 float PCM，只把新增部分转 PCM16 回调
    auto emit_delta = [&]() -> bool {
        int32_t      sr   = 0;
        const char * data = nullptr;
        size_t       len  = 0;
        // 外部审查 BUG-3:输出失败静默吞掉会让"合成成功但音频缺失",这里按失败终止
        if (gen.get_output(&sr, &data, &len, nullptr) != 0 || !data) {
            LOGE("get_output failed, aborting synthesis");
            return false;
        }
        if (sr > 0) result_rate = sr;
        const size_t total = len / sizeof(float);
        if (total <= emitted_samples) return true;

        const size_t n_new = total - emitted_samples;
        std::vector<int16_t> pcm16(n_new);
        floats_to_pcm16(reinterpret_cast<const float *>(data) + emitted_samples, n_new, pcm16.data());
        emitted_samples = total;

        if (on_pcm != nullptr) {
            jbyteArray arr = env->NewByteArray(static_cast<jsize>(n_new * 2));
            if (arr != nullptr) {
                env->SetByteArrayRegion(arr, 0, static_cast<jsize>(n_new * 2),
                                        reinterpret_cast<const jbyte *>(pcm16.data()));
                jboolean keep = env->CallBooleanMethod(j_callback, on_pcm, arr, (jint) sr);
                env->DeleteLocalRef(arr);
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    return false;
                }
                return keep == JNI_TRUE;
            }
        }
        return true;
    };

    if (ok) {
        // ---- 阶段二：逐帧生成（1 帧 = 80ms 音频） ----
        llama_token sampled = llama_sampler_sample(smpl, session->ctx, -1);
        llama_sampler_accept(smpl, sampled);
        const float * h_state = llama_get_embeddings_ith(session->ctx, -1);

        int frames = 0;
        bool stop = false;
        const int frame_cap = max_frames > 0 ? max_frames : 800;
        const int emit_every = session->vocoder_ready ? EMIT_EVERY_FRAMES_NPU
                                                      : EMIT_EVERY_FRAMES_GGML;
        while (!stop && frames < frame_cap) {
            const float * h_next = nullptr;
            if (gen.step_gen(sampled, h_state, &h_next, &stop) != 0) {
                LOGE("step_gen failed at frame %d", frames);
                break;
            }
            if (!h_next) break;

            ++frames;
            h_state = h_next;
            if (frames % emit_every == 0 && !emit_delta()) {
                cancelled = true;
                break;
            }
            sampled = llama_sampler_sample(smpl, session->ctx, -1);
            llama_sampler_accept(smpl, sampled);
        }
        if (!cancelled) {
            // 异步声码器:等全部在飞块完成并入 PCM,再最终发射
            if (gen.finish() != 0) {
                LOGE("vocoder finish failed");
            }
            emit_delta();
        }
        LOGI("synthesized: %d frames, %zu samples", frames, emitted_samples);
    }

    llama_sampler_free(smpl);
    if (speaker) mtmd_bitmap_free(speaker);

    return cancelled ? 0 : (result_rate > 0 ? result_rate : session->sample_rate);
}

JNIEXPORT void JNICALL
Java_com_github_lonepheasantwarrior_talkify_llm_QwenTtsBridge_nativeTtsFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * session = reinterpret_cast<QwenTtsSession *>(handle);
    if (!session) return;
    {
        // 等在途合成退出后再释放
        std::lock_guard<std::mutex> guard(session->lock);
        if (session->mctx)  mtmd_free(session->mctx);
        if (session->ctx)   llama_free(session->ctx);
        if (session->model) llama_model_free(session->model);
    }
    delete session;
    talkify::backend_release();
    LOGI("qwen3-tts session freed");
}

} // extern "C"
