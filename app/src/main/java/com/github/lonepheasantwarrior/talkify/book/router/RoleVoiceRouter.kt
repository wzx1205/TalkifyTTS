package com.github.lonepheasantwarrior.talkify.book.router

import com.github.lonepheasantwarrior.talkify.book.config.BookTtsSettings
import com.github.lonepheasantwarrior.talkify.book.model.EmotionTag
import com.github.lonepheasantwarrior.talkify.book.model.Gender
import com.github.lonepheasantwarrior.talkify.book.model.Utterance
import com.github.lonepheasantwarrior.talkify.book.store.CharacterBookStore

/**
 * 角色 → 音色 / 语速 解析结果
 *
 * @param voiceId ZipVoice 参考音色 ID；null 表示沿用用户当前选中音色
 * @param speedMultiplier 相对系统语速的倍率（1.0 = 不变）
 */
data class VoicePlan(
    val voiceId: String?,
    val speedMultiplier: Float = 1.0f
)

/**
 * 角色声线路由
 *
 * 规则：
 * 1. 旁白 → narrator 槽
 * 2. 具名角色：同一说话人稳定映射到 male/female/male2/female2 槽（按性别）
 * 3. 情感 → 语速微调（ZipVoice 无 Instruct 情感）
 */
object RoleVoiceRouter {

    /** 本章/本段内 说话人 → 槽位 */
    private val speakerSlot = mutableMapOf<String, String>()
    private var maleAssignCount = 0
    private var femaleAssignCount = 0

    fun resetSession() {
        speakerSlot.clear()
        maleAssignCount = 0
        femaleAssignCount = 0
    }

    fun resolve(utterance: Utterance, fallbackVoiceId: String?): VoicePlan {
        // 长篇听书角色会持续累积，超过上限重置映射防止无界增长
        if (speakerSlot.size > 64) {
            speakerSlot.clear()
            maleAssignCount = 0
            femaleAssignCount = 0
        }
        val voiceId = resolveVoiceId(utterance, fallbackVoiceId)
        val speed = speedFor(utterance.emotion, utterance.intensity)
        return VoicePlan(voiceId = voiceId, speedMultiplier = speed)
    }

    private fun resolveVoiceId(utterance: Utterance, fallbackVoiceId: String?): String? {
        if (!utterance.isQuote || utterance.speaker == Utterance.SPEAKER_NARRATOR) {
            return BookTtsSettings.voiceForRole(BookTtsSettings.ROLE_NARRATOR) ?: fallbackVoiceId
        }

        // 具名角色优先查角色册（EPUB 全书扫描的用户绑定），命中即精确用声
        CharacterBookStore.activeVoiceFor(utterance.speaker)?.let { return it }

        val slot = speakerSlot.getOrPut(utterance.speaker) {
            when (utterance.gender) {
                Gender.FEMALE -> {
                    val s = if (femaleAssignCount == 0) BookTtsSettings.ROLE_FEMALE else BookTtsSettings.ROLE_FEMALE2
                    femaleAssignCount++
                    s
                }
                Gender.MALE, Gender.UNKNOWN -> {
                    // 未知默认偏男声旁白系，避免与旁白（若为男解说）完全撞车时用 male2
                    val s = if (maleAssignCount == 0) BookTtsSettings.ROLE_MALE else BookTtsSettings.ROLE_MALE2
                    maleAssignCount++
                    s
                }
            }
        }
        return BookTtsSettings.voiceForRole(slot) ?: fallbackVoiceId
    }

    private fun speedFor(emotion: EmotionTag, intensity: Float): Float {
        val k = intensity.coerceIn(0f, 1f)
        return when (emotion) {
            EmotionTag.CALM -> 1.0f
            EmotionTag.JOY -> 1.0f + 0.06f * k
            EmotionTag.ANGER -> 1.0f + 0.10f * k
            EmotionTag.SADNESS -> 1.0f - 0.10f * k
            EmotionTag.FEAR -> 1.0f + 0.12f * k
            EmotionTag.SURPRISE -> 1.0f + 0.08f * k
        }.coerceIn(0.85f, 1.2f)
    }
}
