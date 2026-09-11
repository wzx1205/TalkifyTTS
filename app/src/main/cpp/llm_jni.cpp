// talkify_llm —— 手写 llama.cpp JNI 桥
//
// 设计目标：给 Kotlin 侧一个"够用且稳定"的最小 LLM 推理接口，
// 不绑定任何上层业务：谁调用都只是"喂 prompt、收流式 token"。
//
//   nativeInit(modelPath, nCtx, nThreads) -> handle
//   nativeGenerate(handle, prompt, maxTokens, temp, topP, callback) -> 完整文本
//   nativeFree(handle)
//
// 线程模型：generate 是同步阻塞的，必须在工作线程调用；回调在调用线程触发。
// 一个 handle 同一时刻只允许一个 generate（上层保证）。

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>
#include <mutex>

#include "llama.h"

#define TAG "TalkifyLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct LlmSession {
    llama_model   * model   = nullptr;
    llama_context * ctx     = nullptr;
    const llama_vocab * vocab = nullptr;
    int             n_threads = 4;
    std::mutex      lock;      // 串行化同一 handle 的推理

    // ---- KV 前缀复用状态 ----
    // 听书每段都携带同一段 system 提示词（~200 token）。记住上次成功 decode
    // 的完整 prompt，下次只对分叉之后的部分做 prefill，省掉重复的系统段计算
    std::vector<llama_token> prev_prompt;
    bool kv_valid = false;     // KV 中是否保存着与 prev_prompt 对应的完整前缀
};

// 判断 [s, s+n) 是否以完整 UTF-8 序列结尾；返回"可安全输出的字节数"。
// 中文一个 token 常是半个多字节字符，直接回调会碎裂成乱码。
size_t utf8_safe_len(const char * s, size_t n) {
    size_t i = n;
    // 最多回退 3 字节即可判定尾部不完整序列
    for (size_t back = 1; back <= 3 && back <= n; ++back) {
        unsigned char c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0x80) == 0x00) {           // ASCII：本身完整
            break;
        }
        if ((c & 0xC0) == 0xC0) {           // 首字节：说明这是一个完整序列的起点
            size_t need = (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : 4;
            i = (back >= need) ? n : (n - back);
            break;
        }
        // 0x80 续字节：继续往前找首字节
        i = (back == 3) ? (n - back) : i;
    }
    return i;
}

void log_callback(ggml_log_level level, const char * text, void * /*user*/) {
    if (level >= GGML_LOG_LEVEL_ERROR) {
        LOGE("%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        LOGI("WARN %s", text);
    }
}

// 把 prompt 按 n_batch 上限分块喂进 KV cache；begin 之前的 token 假定已在 KV 中
bool decode_prompt(llama_context * ctx, const std::vector<llama_token> & tokens, int n_batch, size_t begin) {
    for (size_t i = begin; i < tokens.size(); i += n_batch) {
        int32_t n = static_cast<int32_t>(std::min<size_t>(n_batch, tokens.size() - i));
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(tokens.data()) + i, n);
        if (llama_decode(ctx, batch) != 0) {
            LOGE("llama_decode failed while feeding prompt chunk at %zu", i);
            return false;
        }
    }
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_github_lonepheasantwarrior_talkify_llm_LlamaBridge_nativeInit(
        JNIEnv * env, jobject /*thiz*/,
        jstring j_model_path, jint n_ctx, jint n_threads) {

    const char * model_path = env->GetStringUTFChars(j_model_path, nullptr);
    std::string path(model_path ? model_path : "");
    env->ReleaseStringUTFChars(j_model_path, model_path);

    llama_log_set(log_callback, nullptr);
    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;            // Android CPU 推理

    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        LOGE("failed to load model: %s", path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = static_cast<uint32_t>(n_ctx > 0 ? n_ctx : 2048);
    cparams.n_batch         = 256;       // 与 decode_prompt 的分块保持一致
    cparams.n_ubatch        = 256;
    cparams.n_threads       = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED; // CPU 后端上 FA 不一定更快，先关
    cparams.no_perf         = true;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("failed to create llama_context");
        llama_model_free(model);
        return 0;
    }

    auto * session = new LlmSession();
    session->model     = model;
    session->ctx       = ctx;
    session->vocab     = llama_model_get_vocab(model);
    session->n_threads = cparams.n_threads;

    LOGI("llama init ok: %s (n_ctx=%u threads=%d)", path.c_str(), cparams.n_ctx, cparams.n_threads);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jstring JNICALL
Java_com_github_lonepheasantwarrior_talkify_llm_LlamaBridge_nativeGenerate(
        JNIEnv * env, jobject /*thiz*/, jlong handle,
        jstring j_prompt, jint max_tokens, jfloat temperature, jfloat top_p,
        jobject j_callback) {

    auto * session = reinterpret_cast<LlmSession *>(handle);
    if (!session || !session->ctx) return env->NewStringUTF("");

    std::lock_guard<std::mutex> guard(session->lock);

    // ---- 取 prompt 字节 ----
    const char * prompt_chars = env->GetStringUTFChars(j_prompt, nullptr);
    std::string prompt(prompt_chars ? prompt_chars : "");
    env->ReleaseStringUTFChars(j_prompt, prompt_chars);

    // ---- tokenize ----
    const llama_vocab * vocab = session->vocab;
    int32_t n_need = llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
                                    nullptr, 0, true, true);
    if (n_need < 0) n_need = -n_need;
    std::vector<llama_token> tokens(static_cast<size_t>(n_need));
    int32_t n_tok = llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
                                   tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (n_tok < 0 || tokens.empty()) {
        LOGE("tokenize failed (n_tok=%d)", n_tok);
        return env->NewStringUTF("");
    }
    tokens.resize(static_cast<size_t>(n_tok));

    // 上下文装不下就从头截断 prompt，保住尾部（最近的对话最重要）
    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(session->ctx));
    if (n_tok >= n_ctx - 1) {
        int32_t keep = n_ctx - max_tokens - 8;
        if (keep < 1) keep = 1;
        tokens.erase(tokens.begin(), tokens.begin() + (n_tok - keep));
        LOGI("prompt truncated to %d tokens (n_ctx=%d)", keep, n_ctx);
    }

    // ---- KV 前缀复用：与上一次 prompt 求最长公共前缀，只 decode 分叉部分 ----
    // llama_batch_get_one 不带显式 pos 时位置沿 KV 现有占用顺延，
    // 因此保留前缀后接着喂新 token 即可无缝续上
    llama_memory_t mem = llama_get_memory(session->ctx);
    size_t common = 0;
    if (session->kv_valid) {
        size_t limit = std::min(session->prev_prompt.size(), tokens.size());
        while (common < limit && session->prev_prompt[common] == tokens[common]) ++common;
    }
    if (common > 0) {
        // 丢弃公共前缀之后的所有 KV（含上一轮生成的 token），前缀原样保留
        llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(common), -1);
    } else {
        llama_memory_clear(mem, true);
    }
    LOGI("kv prefix reuse: %zu/%zu tokens", common, tokens.size());

    if (!decode_prompt(session->ctx, tokens, 256, common)) {
        // decode 中断的 KV 内容不完整，作废复用状态，下次全量重算
        llama_memory_clear(mem, true);
        session->kv_valid = false;
        return env->NewStringUTF("");
    }
    session->prev_prompt = tokens;
    session->kv_valid = true;

    // ---- sampler ----
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // ---- 回调准备 ----
    jmethodID on_token = nullptr;
    bool use_callback = false;
    bool cb_returns_boolean = false;
    if (j_callback != nullptr) {
        jclass cb_class = env->GetObjectClass(j_callback);
        // 首选 TokenCallback.onToken(String): Boolean —— 签名确定，无反射歧义
        on_token = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)Z");
        if (on_token != nullptr) {
            use_callback = true;
            cb_returns_boolean = true;
        } else {
            env->ExceptionClear();
            // 兜底：Kotlin (String) -> Boolean 的 Function1 桥方法
            on_token = env->GetMethodID(cb_class, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
            if (on_token == nullptr) {
                env->ExceptionClear();
            } else {
                use_callback = true;
                cb_returns_boolean = false;
            }
        }
    }

    std::string full;        // 累积完整输出
    std::string pending;     // 未凑齐 UTF-8 的尾字节
    llama_token cur = tokens.back();
    int32_t generated = 0;
    bool stopped = false;

    while (generated < max_tokens && !stopped) {
        llama_token next = llama_sampler_sample(smpl, session->ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) break;

        char piece[256];
        int32_t n_piece = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, false);
        if (n_piece > 0) {
            pending.append(piece, static_cast<size_t>(n_piece));
            size_t safe = utf8_safe_len(pending.data(), pending.size());
            if (safe > 0) {
                std::string chunk = pending.substr(0, safe);
                pending.erase(0, safe);
                full += chunk;
                if (use_callback) {
                    jstring js = env->NewStringUTF(chunk.c_str());
                    if (cb_returns_boolean) {
                        jboolean keep = env->CallBooleanMethod(j_callback, on_token, js);
                        if (env->ExceptionCheck()) {
                            env->ExceptionDescribe();
                            env->ExceptionClear();
                            stopped = true;
                        } else if (!keep) {
                            stopped = true;   // 回调返回 false => 请求中止
                        }
                    } else {
                        jobject ret = env->CallObjectMethod(j_callback, on_token, js);
                        if (env->ExceptionCheck()) {
                            env->ExceptionDescribe();
                            env->ExceptionClear();
                            stopped = true;
                        } else if (ret != nullptr) {
                            jclass bcls = env->GetObjectClass(ret);
                            jmethodID bid = env->GetMethodID(bcls, "booleanValue", "()Z");
                            if (bid && !env->CallBooleanMethod(ret, bid)) stopped = true;
                            env->DeleteLocalRef(bcls);
                            env->DeleteLocalRef(ret);
                        }
                    }
                    env->DeleteLocalRef(js);
                    if (stopped) break;
                }
            }
        }

        llama_sampler_accept(smpl, next);

        // 把新 token 喂回去续算
        llama_batch batch = llama_batch_get_one(&next, 1);
        if (llama_decode(session->ctx, batch) != 0) {
            LOGE("llama_decode failed during generation at %d", generated);
            break;
        }
        cur = next;
        ++generated;

        // 上下文写满就停（不滚动窗口，保持实现简单可预期）
        if (tokens.size() + generated >= static_cast<size_t>(n_ctx)) break;
    }

    if (!pending.empty()) full += pending; // 不完整尾字节直接丢弃更安全，但这里保留以免丢内容

    llama_sampler_free(smpl);
    LOGI("generated %d tokens, %zu bytes", generated, full.size());
    return env->NewStringUTF(full.c_str());
}

JNIEXPORT void JNICALL
Java_com_github_lonepheasantwarrior_talkify_llm_LlamaBridge_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * session = reinterpret_cast<LlmSession *>(handle);
    if (!session) return;
    if (session->ctx)   llama_free(session->ctx);
    if (session->model) llama_model_free(session->model);
    delete session;
    llama_backend_free();
    LOGI("llama session freed");
}

} // extern "C"
