package com.github.lonepheasantwarrior.talkify.book.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重建覆盖测试：任何输入文本，分析产物拼接后必须覆盖全部
 * 非引号/空白字符——定位「语气文本被吞」。
 */
class ReconstructionCoverageTest {

    /** 去掉引号与空白后应保留的字符序列（保序保重复）；droppable 为设计内可剥离的纯提示语 */
    private fun expectedChars(raw: String, droppable: List<String> = emptyList()): List<Char> {
        var s = raw
        droppable.forEach { s = s.replace(it, "") }
        // ‘’ 为嵌套内层引号，属于朗读内容，不从期望中排除
        return s.filter { it !in "“”„\"「」『』 \t　\n\r" }.map { it }
    }

    private fun actualChars(utterances: List<String>): List<Char> =
        utterances.joinToString("").filter { it !in " \t　\n\r" }.map { it }

    private fun checkCoverage(raw: String, droppable: List<String> = emptyList()) {
        val utterances = RuleEngine.analyze(raw)
        val expected = expectedChars(raw, droppable)
        val actual = actualChars(utterances.map { it.text })
        assertEquals(
            "重建不一致！\n原文: $raw\n产物: ${utterances.map { it.text }}",
            expected, actual
        )
    }

    @Test
    fun `提示语+对白完整覆盖`() {
        checkCoverage("柳赤诚转头看了眼年轻人，笑问道：“顾璨，你一直没说为什么要来这边逛，还要故意撇开曾掖和马笃宜，现在可以讲了吧？”")
    }

    @Test
    fun `叹词独立段完整覆盖`() {
        checkCoverage("“哎哟！”裴钱捂住脑袋，“嘶——疼。”")
    }

    @Test
    fun `省略号语气完整覆盖`() {
        checkCoverage("她顿了顿：“我……我就是想问问。”")
    }

    @Test
    fun `强调引号完整覆盖`() {
        checkCoverage("满天花雨“万花劫”动了，缓缓飘在空中的花瓣，开始急速旋转，发出轻轻的“嗡嗡”声。")
    }

    @Test
    fun `语气描写后置完整覆盖`() {
        checkCoverage("“走吧。”他说着，语气里满是期待。")
    }

    @Test
    fun `连续对白无提示完整覆盖`() {
        checkCoverage("“嗯。”“哦。”“啊？你说什么？”")
    }

    @Test
    fun `前后夹叙述完整覆盖`() {
        checkCoverage("周米粒一跺脚，懊恼道：“这么久！得嗑多少瓜子才成！”说罢扭头就走。")
    }

    @Test
    fun `无空格引号嵌套不丢字`() {
        // 「他心想：」代词主语纯提示语，设计内剥离
        checkCoverage(
            "他心想：“这话‘有诈’吧。”",
            droppable = listOf("他心想：")
        )
    }

    @Test
    fun `反问道提示语不吞`() {
        // 《剑来》实录：「顾璨反问道：」——「反问」曾在动词表外，
        // 反被吞进人名导致整段剥离
        val utterances = RuleEngine.analyze("顾璨反问道：“万一呢？何必呢？”")
        assertEquals(1, utterances.size)
        assertTrue(utterances[0].isQuote)
        assertEquals("顾璨", utterances[0].speaker)
        checkCoverage("顾璨反问道：“万一呢？何必呢？”", droppable = listOf("顾璨反问道："))
    }

    @Test
    fun `带动作描写的提示语不得剥离`() {
        // 《剑来》听书实录丢字：叹气/咬牙类语气描写必须保留
        checkCoverage("裴钱叹了口气道：“来了来了。”")
        checkCoverage("他咬咬牙道：“拼了。”")
        checkCoverage("她揉了揉眼睛，哽咽道：“我没事。”")
    }

    @Test
    fun `拼接产物不留多余空白`() {
        val utterances = RuleEngine.analyze("“走吧。”他说。")
        assertTrue(utterances.all { it.text == it.text.trim() })
    }
}
