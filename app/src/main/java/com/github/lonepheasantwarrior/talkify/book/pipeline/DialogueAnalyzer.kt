package com.github.lonepheasantwarrior.talkify.book.pipeline

import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.book.appearance.AppearanceCueExtractor
import com.github.lonepheasantwarrior.talkify.book.appearance.CharacterAppearanceStore
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

    /** 跨段最近具名男角色（「他皱眉道」回填） */
    @Volatile
    private var lastNamedMale: String? = null

    /** 跨段最近具名女角色（「她叹道」回填） */
    @Volatile
    private var lastNamedFemale: String? = null

    /** 说话人 → 性别投票 [female, male]（窗口线索会被旁白里的第三者描述污染，累计多数派更稳） */
    private val genderVotes = HashMap<String, IntArray>()

    fun analyze(text: String): List<Utterance> = enhanceWithLlm(analyzeRules(text))

    /**
     * 规则层分析（不含 LLM）：切句 + 轮替猜测 + 性别投票回填
     *
     * 独立暴露给流水线：旁白句与 LLM 无关，可先用规则结果立刻合成，
     * LLM 校正（只影响对白句）并行进行，首包不再等推理。
     */
    fun analyzeRules(text: String): List<Utterance> {
        if (text.isBlank()) return emptyList()
        val last = carryLast
        val prev = carryPrev
        val utterances = RuleEngine.analyze(
            text = text,
            carryLast = last,
            carryPrev = prev,
            prevEndedWithQuote = prevEndedWithQuote,
            carryLastNamedMale = lastNamedMale,
            carryLastNamedFemale = lastNamedFemale
        ).filter { it.text.isNotBlank() }

        // 先按规则结果推进会话状态（性别投票、轮替记忆、具名角色），
        // 这样即使 LLM 校正改变了个别句子的说话人，也不会污染跨段记忆
        updateSession(utterances)

        // 旁白面相/年龄边走边记（主角伪装可被后文覆盖）
        harvestAppearance(utterances)

        // 性别按累计多数派回填：早期被旁白描述污染的单次误判会被后续票数纠正
        return applyGenderMajority(utterances)
    }

    /**
     * 可选 LLM 增强；任何异常都吞掉并返回原结果。
     * 只改对白句（说话人/性别/情绪），旁白句与文本切分原样保留——
     * 因此可以先播旁白、待本函数返回后再播校正过的对白。
     */
    fun enhanceWithLlm(utterances: List<Utterance>): List<Utterance> {
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
     * 用本段具名对白更新轮替、性别投票与最近具名角色
     */
    private fun updateSession(utterances: List<Utterance>) {
        var newLast = carryLast
        var newPrev = carryPrev
        var namedMale = lastNamedMale
        var namedFemale = lastNamedFemale
        for (u in utterances) {
            if (!u.isQuote || u.speaker == Utterance.SPEAKER_NARRATOR) continue
            if (u.gender != Gender.UNKNOWN) {
                val v = genderVotes.getOrPut(u.speaker) { IntArray(2) }
                if (u.gender == Gender.FEMALE) v[0]++ else v[1]++
            }
            // 具名角色（排除旁白/裸代词）入最近记忆，供跨段「他/她 + 动作 + 道」回填。
            // 性别未知时也记住——同段内「林风皱眉道…他沉声道」仍应延续林风
            if (u.speaker != "他" && u.speaker != "她" && u.speaker != "众人") {
                when (u.gender) {
                    Gender.MALE -> namedMale = u.speaker
                    Gender.FEMALE -> namedFemale = u.speaker
                    Gender.UNKNOWN -> {
                        if (namedMale == null) namedMale = u.speaker
                        if (namedFemale == null) namedFemale = u.speaker
                    }
                }
            }
            if (u.speaker != newLast) {
                newPrev = newLast
                newLast = u.speaker
            }
        }
        carryLast = newLast
        carryPrev = newPrev
        lastNamedMale = namedMale
        lastNamedFemale = namedFemale
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
        lastNamedMale = null
        lastNamedFemale = null
        genderVotes.clear()
        CharacterAppearanceStore.reset()
    }

    /**
     * 旁白外貌/年龄线索 → [CharacterAppearanceStore]
     *
     * 已知名字来自本段对白 + 记忆里已出现过的角色；
     * 主角伪装时后文会再写真实面相，store 后写覆盖即可。
     */
    private fun harvestAppearance(utterances: List<Utterance>) {
        val known = LinkedHashSet<String>()
        for (u in utterances) {
            if (u.isQuote &&
                u.speaker != Utterance.SPEAKER_NARRATOR &&
                u.speaker != "他" && u.speaker != "她" && u.speaker != "众人"
            ) {
                known += u.speaker
            }
        }
        known += lastNamedMale?.let { listOf(it) }.orEmpty()
        known += lastNamedFemale?.let { listOf(it) }.orEmpty()

        for (u in utterances) {
            if (u.isQuote) continue
            val cues = AppearanceCueExtractor.extract(u.text, known)
            if (cues.isNotEmpty()) {
                CharacterAppearanceStore.observeAll(cues)
                TtsLogger.d("Appearance cues: $cues from=${u.text.take(40)}", tag = TAG)
            }
        }
    }
}
