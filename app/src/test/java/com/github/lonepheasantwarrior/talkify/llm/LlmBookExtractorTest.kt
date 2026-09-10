package com.github.lonepheasantwarrior.talkify.llm

import com.github.lonepheasantwarrior.talkify.book.model.EmotionTag
import com.github.lonepheasantwarrior.talkify.book.model.Gender
import com.github.lonepheasantwarrior.talkify.book.model.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LLM 校正逻辑单测：重点验证"输入输出句数一致、文本一字不改"
 * 以及模型输出格式不完美时的容错。
 */
class LlmBookExtractorTest {

    private fun q(text: String, speaker: String = "未知") =
        Utterance(text = text, isQuote = true, speaker = speaker)

    private val narrator = Utterance(text = "他抬起头。", isQuote = false)

    // ==================== buildPrompt ====================

    @Test
    fun promptListsEveryQuoteWithIndex() {
        val quotes = listOf(q("师父，我饿了。"), q("忍一忍。"))
        val p = LlmBookExtractor.buildPrompt(quotes)
        assertTrue("must state the exact count", p.contains("正好 2 条"))
        assertTrue("index 0 present", p.contains("0. 【对白】师父，我饿了。"))
        assertTrue("index 1 present", p.contains("1. 【对白】忍一忍。"))
        assertTrue("think must be pre-closed", p.contains("<｜end▁of▁thinking｜>"))
    }

    /** 前一句也是对白时，不作为上文线索（对判断说话人无帮助、可能误导） */
    @Test
    fun promptOmitsQuoteAsContext() {
        val p = LlmBookExtractor.buildPrompt(listOf(q("第一句", "甲"), q("第二句", "乙")))
        assertTrue("对白不应被当成上文线索", !p.contains("【上文】第一句"))
    }

    /**
     * 关键：说话人线索在"上一句旁白"里，必须作为【上文】带进提示词，
     * 否则模型无法判断说话人（这正是规则引擎误判「咬牙」为说话人的根因）。
     */
    @Test
    fun promptCarriesPrecedingNarrationAsContext() {
        val utterances = listOf(
            Utterance(text = "裴钱咬了咬牙，低声道：", isQuote = false),
            q("师父，我饿了。", "咬牙")
        )
        val p = LlmBookExtractor.buildPrompt(utterances)
        assertTrue("必须带上文线索", p.contains("【上文】"))
        assertTrue("上文应含说话人归属片段", p.contains("裴钱咬了咬牙"))
        assertTrue("必须提醒不要把动作词当人名", p.contains("不要把动作词"))
    }

    @Test
    fun promptIndexIgnoresNarration() {
        // 序号只在"对白"上递增，旁白不占号，保证与 apply() 对齐
        val utterances = listOf(
            Utterance(text = "旁白甲", isQuote = false),
            q("第一句", "甲"),
            Utterance(text = "旁白乙", isQuote = false),
            q("第二句", "乙")
        )
        val p = LlmBookExtractor.buildPrompt(utterances)
        assertTrue(p.contains("正好 2 条"))
        assertTrue(p.contains("1. 【上文】旁白乙 【对白】第二句"))
    }

    // ==================== parse 容错 ====================

    @Test
    fun parsesCleanJsonArray() {
        val raw = """[{"i":0,"speaker":"裴钱","gender":"male","emotion":"SADNESS"},
                      {"i":1,"speaker":"陈平安","gender":"male","emotion":"JOY"}]"""
        val c = LlmBookExtractor.parse(raw)
        assertEquals(2, c.size)
        assertEquals("裴钱", c[0]?.speaker)
        assertEquals(EmotionTag.SADNESS, c[0]?.emotion)
        assertEquals(EmotionTag.JOY, c[1]?.emotion)
    }

    @Test
    fun stripsThinkAndCodeFence() {
        val raw = "thinking process here<｜end▁of▁thinking｜>\n```json\n[{\"i\":0,\"speaker\":\"宁姚\",\"gender\":\"female\",\"emotion\":\"CALM\"}]\n```"
        val c = LlmBookExtractor.parse(raw)
        assertEquals(1, c.size)
        assertEquals("宁姚", c[0]?.speaker)
        assertEquals(Gender.FEMALE, c[0]?.gender)
    }

    @Test
    fun toleratesSingleObjectWithoutArray() {
        val c = LlmBookExtractor.parse("""{"i":0,"speaker":"老剑条","gender":"male","emotion":"ANGER"}""")
        assertEquals(1, c.size)
        assertEquals(EmotionTag.ANGER, c[0]?.emotion)
    }

    @Test
    fun skipsMalformedEntriesButKeepsValidOnes() {
        val raw = """[{"i":0,"speaker":"裴钱","gender":"male","emotion":"CALM"},
                      {"speaker":"没有序号"},
                      {"i":2,"speaker":"陈平安","gender":"female","emotion":"不存在的情绪"}]"""
        val c = LlmBookExtractor.parse(raw)
        assertEquals(2, c.size)
        assertEquals("裴钱", c[0]?.speaker)
        assertNotNull(c[2])
        assertEquals("陈平安", c[2]?.speaker)
        // 无法识别的情绪留空 → 调用方保持规则原值
        assertEquals(null, c[2]?.emotion)
    }

    @Test
    fun garbageInputYieldsEmptyMap() {
        assertEquals(0, LlmBookExtractor.parse("").size)
        assertEquals(0, LlmBookExtractor.parse("我不太确定你在问什么").size)
        assertEquals(0, LlmBookExtractor.parse("[坏掉的").size)
    }

    /** 实测缺陷复现：小模型在数组元素之间漏掉逗号 */
    @Test
    fun parsesArrayWithMissingCommas() {
        val raw = """[{"i": 0, "speaker": "裴钱", "gender": "male", "emotion": "SADNESS"}
                     {"i": 1, "speaker": "陈平安", "gender": "male", "emotion": "JOY"}]"""
        val c = LlmBookExtractor.parse(raw)
        assertEquals(2, c.size)
        assertEquals("裴钱", c[0]?.speaker)
        assertEquals(EmotionTag.SADNESS, c[0]?.emotion)
        assertEquals("陈平安", c[1]?.speaker)
        assertEquals(EmotionTag.JOY, c[1]?.emotion)
    }

    /** 实测缺陷复现：答案之后又回显一遍，且夹杂 think 残留 */
    @Test
    fun keepsFirstOccurrenceWhenAnswerEchoed() {
        val raw = """[{"i":0,"speaker":"裴钱","gender":"male","emotion":"SADNESS"}]
                     </think>
                     [{"i":0,"speaker":"裴钱","gender":"male","emotion":"JOY"}]"""
        val c = LlmBookExtractor.parse(raw)
        assertEquals(1, c.size)
        assertEquals(EmotionTag.SADNESS, c[0]?.emotion)
    }

    /** 被超时截断的半截对象不应污染已完整的条目 */
    @Test
    fun ignoresTruncatedTrailingObject() {
        val raw = """[{"i":0,"speaker":"裴钱","gender":"male","emotion":"CALM"},
                      {"i":1,"speaker":"陈平"""
        val c = LlmBookExtractor.parse(raw)
        assertEquals(1, c.size)
        assertEquals("裴钱", c[0]?.speaker)
    }

    @Test
    fun unknownSpeakerPlaceholderIsIgnored() {
        val c = LlmBookExtractor.parse("""[{"i":0,"speaker":"未知","gender":"male","emotion":"CALM"}]""")
        // "未知" 不应覆盖规则层已经猜到的说话人
        assertEquals(null, c[0]?.speaker)
    }

    // ==================== apply 安全性 ====================

    @Test
    fun applyNeverChangesTextOrCount() {
        val utterances = listOf(
            narrator,
            q("师父，我饿了。", "裴钱"),
            q("忍一忍。", "陈平安")
        )
        val corrections = mapOf(
            0 to LlmBookExtractor.Correction(speaker = "裴钱", gender = Gender.MALE, emotion = EmotionTag.SADNESS),
            1 to LlmBookExtractor.Correction(speaker = "陈平安", gender = Gender.MALE, emotion = EmotionTag.JOY)
        )
        val out = LlmBookExtractor.apply(utterances, corrections)

        assertEquals("句数必须一致", utterances.size, out.size)
        assertEquals("旁白保持不动", narrator, out[0])
        for (i in utterances.indices) {
            assertEquals("文本不得被改动 @$i", utterances[i].text, out[i].text)
            assertEquals("isQuote 不得被改动 @$i", utterances[i].isQuote, out[i].isQuote)
        }
        assertEquals("裴钱", out[1].speaker)
        assertEquals(EmotionTag.SADNESS, out[1].emotion)
        assertEquals(EmotionTag.JOY, out[2].emotion)
    }

    @Test
    fun applyFallsBackToRuleValuesWhenFieldMissing() {
        val utterances = listOf(
            Utterance(text = "我不该来。", isQuote = true, speaker = "宁姚",
                gender = Gender.UNKNOWN, emotion = EmotionTag.ANGER)
        )
        // 模型只给了性别，说话人与情绪应保持规则原值
        val out = LlmBookExtractor.apply(utterances, mapOf(0 to LlmBookExtractor.Correction(gender = Gender.FEMALE)))
        assertEquals("宁姚", out[0].speaker)
        assertEquals(EmotionTag.ANGER, out[0].emotion)
        assertEquals(Gender.FEMALE, out[0].gender)
    }

    /**
     * 性别已有规则/投票结论时，不得被模型覆盖
     * （实测 0.6B 会把「陈平安」误判成 female）
     */
    @Test
    fun applyDoesNotOverrideKnownGender() {
        val utterances = listOf(
            Utterance(text = "忍一忍。", isQuote = true, speaker = "陈平安",
                gender = Gender.MALE, emotion = EmotionTag.CALM)
        )
        val out = LlmBookExtractor.apply(
            utterances,
            mapOf(0 to LlmBookExtractor.Correction(speaker = "陈平安", gender = Gender.FEMALE, emotion = EmotionTag.JOY))
        )
        assertEquals("已知性别不应被覆盖", Gender.MALE, out[0].gender)
        // 但说话人与情绪仍采纳模型
        assertEquals(EmotionTag.JOY, out[0].emotion)
    }

    @Test
    fun applyIndexesOnlyQuoteItems() {
        // 混杂旁白时，序号必须只在"对白"上递增
        val utterances = listOf(
            Utterance(text = "旁白一", isQuote = false),
            q("第一句", "甲"),
            Utterance(text = "旁白二", isQuote = false),
            q("第二句", "乙")
        )
        val out = LlmBookExtractor.apply(utterances, mapOf(
            0 to LlmBookExtractor.Correction(speaker = "甲"),
            1 to LlmBookExtractor.Correction(speaker = "乙")
        ))
        assertEquals("甲", out[1].speaker)
        assertEquals("乙", out[3].speaker)
        assertEquals("第一句", out[1].text)
        assertEquals("第二句", out[3].text)
    }

    // ==================== enhance 编排 ====================

    @Test
    fun enhanceSkipsGenerationWhenNoQuotes() {
        var called = false
        val out = LlmBookExtractor.enhance(listOf(narrator)) { _, _ -> called = true; "[]" }
        assertEquals(listOf(narrator), out)
        assertTrue("无对白时不应触发推理", !called)
    }

    @Test
    fun enhanceFallsBackWhenGenerateThrows() {
        val utterances = listOf(q("师父，我饿了。", "裴钱"))
        val out = LlmBookExtractor.enhance(utterances) { _, _ -> throw RuntimeException("模型没下载") }
        assertEquals(utterances, out)
    }

    @Test
    fun enhanceAppliesCorrectionsOnHappyPath() {
        val utterances = listOf(q("就凭你？", "未知"), q("就凭我。", "未知"))
        val modelOut = """[{"i":0,"speaker":"老剑条","gender":"male","emotion":"ANGER"},
                            {"i":1,"speaker":"少年","gender":"male","emotion":"JOY"}]"""
        val out = LlmBookExtractor.enhance(utterances) { _, _ -> modelOut }
        assertEquals("老剑条", out[0].speaker)
        assertEquals(EmotionTag.ANGER, out[0].emotion)
        assertEquals("少年", out[1].speaker)
        assertEquals(EmotionTag.JOY, out[1].emotion)
    }

    @Test
    fun enhanceSkipsWhenTooManyQuotes() {
        val many = (1..30).map { q("第${it}句", "甲") }
        var called = false
        val out = LlmBookExtractor.enhance(many) { _, _ -> called = true; "[]" }
        assertEquals(many, out)
        assertTrue("超过上限不应触发推理", !called)
    }
}
