// llama_backend_init/free 的进程级引用计数
//
// llama_backend_* 是全局状态：分析 LLM（llm_jni.cpp）与 Qwen3-TTS 引擎
// （tts_jni.cpp）可能同时存活（多角色听书 = LLM 校正 + TTS 合成并行），
// 任一侧会话退出时无条件 free 会拆掉另一侧正在使用的后端。
// 计数保证只有最后一个会话释放时才真正 teardown。
#pragma once

#include <mutex>

#include "llama.h"

namespace talkify {

inline int & backend_refcount() {
    static int count = 0;
    return count;
}

inline void backend_acquire() {
    static std::mutex m;
    std::lock_guard<std::mutex> guard(m);
    if (backend_refcount()++ == 0) {
        llama_backend_init();
    }
}

inline void backend_release() {
    static std::mutex m;
    std::lock_guard<std::mutex> guard(m);
    if (--backend_refcount() <= 0) {
        backend_refcount() = 0;
        llama_backend_free();
    }
}

} // namespace talkify
