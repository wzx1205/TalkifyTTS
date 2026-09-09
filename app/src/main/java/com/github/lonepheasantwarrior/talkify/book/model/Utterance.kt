package com.github.lonepheasantwarrior.talkify.book.model

/**
 * 网文情感标签（轻量枚举，ZipVoice 侧主要映射到语速）
 */
enum class EmotionTag {
    CALM,
    JOY,
    ANGER,
    SADNESS,
    FEAR,
    SURPRISE
}

/**
 * 角色性别（用于兜底选声线）
 */
enum class Gender {
    MALE,
    FEMALE,
    UNKNOWN
}

/**
 * 对白分析后的最小朗读单元
 *
 * @param text 实际要合成的文本（不含引号本身时可保留标点）
 * @param isQuote 是否为引号内对白
 * @param speaker 归一化说话人名；旁白固定为 [SPEAKER_NARRATOR]
 * @param gender 性别线索
 * @param emotion 情感标签
 * @param intensity 情感强度 0..1
 */
data class Utterance(
    val text: String,
    val isQuote: Boolean = false,
    val speaker: String = SPEAKER_NARRATOR,
    val gender: Gender = Gender.UNKNOWN,
    val emotion: EmotionTag = EmotionTag.CALM,
    val intensity: Float = 0f
) {
    companion object {
        const val SPEAKER_NARRATOR = "旁白"
    }
}
