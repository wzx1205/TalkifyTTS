package com.github.lonepheasantwarrior.talkify.book.pipeline

import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.book.model.Gender
import com.github.lonepheasantwarrior.talkify.book.model.Utterance
import com.github.lonepheasantwarrior.talkify.llm.LlmBookConfig
import com.github.lonepheasantwarrior.talkify.llm.LlmBookExtractor
import com.github.lonepheasantwarrior.talkify.llm.LlmEngine
import com.github.lonepheasantwarrior.talkify.service.TtsLogger

/**
 * 对白分析入口
 *
 * 规则层 + 跨段落会话状态：
 * - 记住最近两句对白的说话人，供无提示对白做「对话轮替」猜测
 * - 记住说话人性别，轮替句沿用以保证声线稳定
 *
 * 可选端上 LLM 增强（[LlmBookConfig.isEnabled] 时）：
 * 规则引擎先切好句，再把对白交给 LLM 校正「说话人 / 性别 / 情绪」三项。
 * LLM 从不参与切句，因此失败只会退回规则结果，绝不会造成吞字或断流。
 */
object DialogueAnalyzer {

    private const val TAG = "DialogueAnalyzer"

    /** 上一句对白的说话人（跨段保持；旁白不参与） */
    @Volatile
    private var carryLast: String? = null

    /** 上上一句对白的说话人 */
    @Volatile
    private var carryPrev: String? = null

    /** 上一段是否以对白结尾（决定下一段开头无提示对白做轮替还是延续） */
    @Volatile
    private var prevEndedWithQuote: Boolean = false

    /** 说话人 → 性别投票 [female, male]（窗口线索会被旁白里的第三者描述污染，累计多数派更稳） */
    private val genderVotes = HashMap<String, IntArray>()

    fun analyze(text: String): List<Utterance> {
        if (text.isBlank()) return emptyList()
        val last = carryLast
        val prev = carryPrev
        val utterances = RuleEngine.analyze(text, last, prev, prevEndedWithQuote)
            .filter { it.text.isNotBlank() }

        // 先按规则结果推进会话状态（性别投票、轮替记忆），
        // 这样即使 LLM 校正改变了个别句子的说话人，也不会污染跨段记忆
        updateSession(utterances)

        // 性别按累计多数派回填：早期被旁白描述污染的单次误判会被后续票数纠正
        val voted = applyGenderMajority(utterances)

        return enhanceWithLlm(voted)
    }

    /**
     * 可选 LLM 增强；任何异常都吞掉并返回原结果
     */
    private fun enhanceWithLlm(utterances: List<Utterance>): List<Utterance> {
        if (!LlmBookConfig.isEnabled()) return utterances
        val context = TalkifyAppHolder.getContext() ?: return utterances
        return try {
            if (!LlmEngine.isModelReady(context)) {
                TtsLogger.w("LLM enabled but model not downloaded, using rule result", tag = TAG)
                return utterances
            }
            LlmBookExtractor.enhance(utterances) { prompt, expected ->
                LlmEngine.generate(context, prompt, expectedObjects = expected) ?: ""
            }
        } catch (e: Exception) {
            TtsLogger.w("LLM enhancement failed, using rule result: ${e.message}", tag = TAG)
            utterances
        }
    }

    /**
     * 用本段具名对白更新轮替与性别投票
     */
    private fun updateSession(utterances: List<Utterance>) {
        var newLast = carryLast
        var newPrev = carryPrev
        for (u in utterances) {
            if (!u.isQuote || u.speaker == Utterance.SPEAKER_NARRATOR) continue
            if (u.gender != Gender.UNKNOWN) {
                val v = genderVotes.getOrPut(u.speaker) { IntArray(2) }
                if (u.gender == Gender.FEMALE) v[0]++ else v[1]++
            }
            if (u.speaker != newLast) {
                newPrev = newLast
                newLast = u.speaker
            }
        }
        carryLast = newLast
        carryPrev = newPrev
        prevEndedWithQuote = utterances.lastOrNull()?.isQuote == true
        if (genderVotes.size > 64) genderVotes.clear()
    }

    /**
     * 性别按累计多数派回填
     */
    private fun applyGenderMajority(utterances: List<Utterance>): List<Utterance> =
        utterances.map { u ->
            if (u.isQuote && u.speaker != Utterance.SPEAKER_NARRATOR) {
                val v = genderVotes[u.speaker]
                if (v != null && v[0] != v[1]) {
                    val majority = if (v[0] > v[1]) Gender.FEMALE else Gender.MALE
                    if (u.gender != majority) u.copy(gender = majority) else u
                } else {
                    u
                }
            } else {
                u
            }
        }

    fun resetSession() {
        carryLast = null
        carryPrev = null
        prevEndedWithQuote = false
        genderVotes.clear()
    }
}
