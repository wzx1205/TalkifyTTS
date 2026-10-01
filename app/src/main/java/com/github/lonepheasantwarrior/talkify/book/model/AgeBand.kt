package com.github.lonepheasantwarrior.talkify.book.model

/**
 * 角色年龄段（从旁白面相/年龄描述里抽）
 *
 * 不做精确岁数：听书场景只需决定声线偏「嫩 / 常 / 沉」。
 * 主角伪装时后文会再出现真实面相，存储层允许后写覆盖先写。
 */
enum class AgeBand {
    CHILD,   // 孩童/童子
    YOUTH,   // 少年/少女/青年
    ADULT,   // 中年/壮年
    ELDER,   // 老者/白发/花甲
    UNKNOWN
}
