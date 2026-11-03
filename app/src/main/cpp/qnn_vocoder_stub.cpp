// qnn_vocoder_stub —— 无 QAIRT SDK 构建环境下的空实现
// 引擎仍可编译链接,NPU 声码器不可用(ready()=false),自动走 ggml 内置声码器。
#include "qnn_vocoder.h"

namespace talkify {

struct QnnVocoder::Impl {};

QnnVocoder & QnnVocoder::instance() {
    static QnnVocoder inst;
    return inst;
}

QnnVocoder::QnnVocoder() : impl(nullptr) {}
QnnVocoder::~QnnVocoder() {}

bool QnnVocoder::ready() const { return false; }

bool QnnVocoder::init(const char * /*nativeDir*/, const char * /*ctxPath*/) { return false; }

int32_t QnnVocoder::decode(const int32_t * /*codes*/, int32_t /*nFrames*/, float * /*pcmOut*/) {
    return -1;
}

} // namespace talkify
