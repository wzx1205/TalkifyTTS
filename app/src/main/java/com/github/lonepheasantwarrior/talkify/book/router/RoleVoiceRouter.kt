package com.github.lonepheasantwarrior.talkify.book.router

import com.github.lonepheasantwarrior.talkify.book.appearance.CharacterAppearanceStore
import com.github.lonepheasantwarrior.talkify.book.config.BookTtsSettings
import com.github.lonepheasantwarrior.talkify.book.model.AgeBand
import com.github.lonepheasantwarrior.talkify.book.model.EmotionTag
import com.github.lonepheasantwarrior.talkify.book.model.Gender
import com.github.lonepheasantwarrior.talkify.book.model.Utterance
import com.github.lonepheasantwarrior.talkify.book.store.CharacterBookStore

/**
 * 角色 → 音色 / 语速 / 停顿 解析结果
 *
 * @param voiceId ZipVoice 参考音色 ID；null 表示沿用用户当前选中音色
 * @param speedMultiplier 相对系统语速的倍率（1.0 = 不变）
 * @param pauseMsAfter 本句播完后的句间静音（毫秒），用于情感留白与换声线
 */
data class VoicePlan(
    val voiceId: String?,
    val speedMultiplier: Float = 1.0f,
    val pauseMsAfter: Int = 0
)

/**
 * 角色声线路由
 *
 * 规则：
 * 1. 旁白 → narrator 槽
 * 2. 具名角色：同一说话人稳定映射到 male/female/male2/female2 槽（按性别）
 * 3. 情感 → 语速微调 + 句间停顿（ZipVoice 无 Instruct 情感）
 */
object RoleVoiceRouter {

    /** 本章/本段内 说话人 → 槽位 */
    private val speakerSlot = mutableMapOf<String, String>()
    private var maleAssignCount = 0
    private var femaleAssignCount = 0

    /** 上一句对白的（说话人, 音色）：相邻对白防撞声线 */
    private var lastQuoteSpeaker: String? = null
    private var lastQuoteVoice: String? = null

    /** 上一句实际朗读的说话人（换声线时加一点停顿，避免贴脸切换） */
    private var lastSpokenSpeaker: String? = null

    /**
     * 相邻对白防撞：上一句不同角色刚用过同一音色时返回 true，
     * 调用方应换备用声线，避免两个男生对手戏连续同声线
     */
    fun collidesWithPrevious(speaker: String, voiceId: String?): Boolean {
        return voiceId != null &&
            lastQuoteSpeaker != null &&
            lastQuoteSpeaker != speaker &&
            lastQuoteVoice == voiceId
    }

    /** 记录本句实际使用的音色（防撞基准） */
    fun registerSpoken(speaker: String, voiceId: String?) {
        if (voiceId == null) return
        lastQuoteSpeaker = speaker
        lastQuoteVoice = voiceId
        lastSpokenSpeaker = speaker
    }

    fun resetSession() {
        speakerSlot.clear()
        maleAssignCount = 0
        femaleAssignCount = 0
        lastQuoteSpeaker = null
        lastQuoteVoice = null
        lastSpokenSpeaker = null
        CharacterAppearanceStore.reset()
    }

    fun resolve(utterance: Utterance, fallbackVoiceId: String?): VoicePlan {
        // 长篇听书角色会持续累积，超过上限重置映射防止无界增长
        if (speakerSlot.size > 64) {
            speakerSlot.clear()
            maleAssignCount = 0
            femaleAssignCount = 0
        }
        val voiceId = resolveVoiceId(utterance, fallbackVoiceId)
        val speed = speedFor(utterance.emotion, utterance.intensity, utterance.speaker)
        val pause = pauseFor(utterance)
        return VoicePlan(voiceId = voiceId, speedMultiplier = speed, pauseMsAfter = pause)
    }

    /**
     * 解析角色所属槽位（narrator/male/female/male2/female2）。
     *
     * 供非 ZipVoice 供应商把槽位映射到自己的音色表：
     * 槽位分配与 [resolve] 共用同一映射，保证两套引擎下同一角色稳定。
     *
     * @param genderHint 逐句窗口线索缺失时的性别补充（角色册全书投票性别）
     */
    fun slotFor(utterance: Utterance, genderHint: Gender? = null): String {
        if (speakerSlot.size > 64) {
            speakerSlot.clear()
            maleAssignCount = 0
            femaleAssignCount = 0
        }
        if (!utterance.isQuote || utterance.speaker == Utterance.SPEAKER_NARRATOR) {
            return BookTtsSettings.ROLE_NARRATOR
        }
        val effectiveGender = utterance.gender.takeIf { it != Gender.UNKNOWN } ?: genderHint
        return speakerSlot.getOrPut(utterance.speaker) {
            when (effectiveGender) {
                Gender.FEMALE -> {
                    val s = if (femaleAssignCount == 0) BookTtsSettings.ROLE_FEMALE else BookTtsSettings.ROLE_FEMALE2
                    femaleAssignCount++
                    s
                }
                Gender.MALE, Gender.UNKNOWN, null -> {
                    val s = if (maleAssignCount == 0) BookTtsSettings.ROLE_MALE else BookTtsSettings.ROLE_MALE2
                    maleAssignCount++
                    s
                }
            }
        }
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

    /**
     * 情感 + 年龄段 → 语速倍率。
     *
     * 情感用二次曲线：低强度几乎不动，高强度拉开差距。
     * 年龄是旁白面相的弱先验：老者略沉、孩童略快，幅度远小于情感。
     */
    private fun speedFor(emotion: EmotionTag, intensity: Float, speaker: String): Float {
        val k = intensity.coerceIn(0f, 1f)
        val shaped = k * k
        val emotionDelta = when (emotion) {
            EmotionTag.CALM -> 0f
            EmotionTag.JOY -> 0.08f * shaped
            EmotionTag.ANGER -> 0.14f * shaped
            EmotionTag.SADNESS -> -0.14f * shaped
            EmotionTag.FEAR -> 0.16f * shaped
            EmotionTag.SURPRISE -> 0.10f * shaped
        }
        val ageDelta = when (CharacterAppearanceStore.ageFor(speaker)) {
            AgeBand.ELDER -> -0.04f
            AgeBand.CHILD, AgeBand.YOUTH -> 0.03f
            AgeBand.ADULT, AgeBand.UNKNOWN -> 0f
        }
        return (1f + emotionDelta + ageDelta).coerceIn(0.80f, 1.25f)
    }

    /**
     * 情感/换角 → 句间停顿（毫秒）。
     *
     * - 旁白：短平快，不拖节奏
     * - 悲伤/愤怒高强：多留白，像人念完重话要喘一下
     * - 恐惧：语速快、停顿短
     * - 换说话人：额外加一点，声线切换不贴脸
     */
    private fun pauseFor(utterance: Utterance): Int {
        val k = utterance.intensity.coerceIn(0f, 1f)
        val base = when (utterance.emotion) {
            EmotionTag.CALM -> 40
            EmotionTag.JOY -> 50 + (30 * k).toInt()
            EmotionTag.ANGER -> 60 + (80 * k).toInt()
            EmotionTag.SADNESS -> 80 + (100 * k).toInt()
            EmotionTag.FEAR -> 40 + (40 * k).toInt()
            EmotionTag.SURPRISE -> 50 + (50 * k).toInt()
        }
        val isNarration = !utterance.isQuote || utterance.speaker == Utterance.SPEAKER_NARRATOR
        return when {
            isNarration -> base.coerceAtMost(60)
            lastSpokenSpeaker != null && lastSpokenSpeaker != utterance.speaker ->
                (base + 40).coerceAtMost(200)
            else -> base.coerceAtMost(160)
        }
    }
}
