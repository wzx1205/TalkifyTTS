// qnn_vocoder —— Qwen3-TTS 声码器(tok_decoder)的 HTP 运行器(生产路径,单例)
//
// 仅在装有 QAIRT SDK 的构建机上编译(头文件依赖同 qnn_probe3);运行时全部
// dlopen,不新增链接依赖。模型文件缺失或初始化失败时 ready()=false,引擎走 ggml。
#pragma once

#include <cstdint>

namespace talkify {

class QnnVocoder {
public:
    struct Impl;

    static QnnVocoder & instance();

    QnnVocoder();
    ~QnnVocoder();
    QnnVocoder(const QnnVocoder &) = delete;
    QnnVocoder & operator=(const QnnVocoder &) = delete;

    // 幂等;成功后 ready()=true。nativeDir 供 ADSP_LIBRARY_PATH(skel 搜索)。
    bool init(const char * nativeDir, const char * ctxPath);
    bool ready() const;

    // 解码最多 25 帧(codes [nFrames][16] int32)→ pcmOut(容量 nFrames*1920 fp32)。
    // 返回 0 成功,非 0 失败。内部串行(锁),可从任意线程调用。
    int32_t decode(const int32_t * codes, int32_t nFrames, float * pcmOut);

    // 异步流水线(NPU 与 CPU 帧生成并行):submit 拷贝入队后立即返回;
    // wait 阻塞到 FIFO 头部块完成并拷出 PCM(帧数以 submit 时为准)。
    // 必须严格按 submit 顺序 wait(调用方契约由 helper 保证)。
    int32_t submit(const int32_t * codes, int32_t nFrames);
    int32_t wait(float * pcmOut);

private:
    Impl * impl;
};

} // namespace talkify
