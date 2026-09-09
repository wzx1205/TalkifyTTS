package com.github.lonepheasantwarrior.talkify.book.pipeline

import com.github.lonepheasantwarrior.talkify.book.model.EmotionTag
import com.github.lonepheasantwarrior.talkify.book.model.Gender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则分析器回归用例，素材取自《飘邈之旅》联调时暴露的真实问题。
 */
class RuleEngineTest {

    @Test
    fun `引号前旁白不得丢字`() {
        // 曾被 stripSpeechAttribution 误删「她心里叹」，只读出「息一声又想：」
        val text = "　　她心里叹息一声又想：“老总的事，可不是我们小职员可以问的。”摇摇头又忙自己的工作去了。"
        val utterances = RuleEngine.analyze(text)

        assertEquals(3, utterances.size)
        assertEquals("她心里叹息一声又想：", utterances[0].text)
        assertTrue(!utterances[0].isQuote)
        assertEquals("老总的事，可不是我们小职员可以问的。", utterances[1].text)
        assertTrue(utterances[1].isQuote)
        assertEquals("摇摇头又忙自己的工作去了。", utterances[2].text)
    }

    @Test
    fun `裸代词主语参与性别路由`() {
        // 「她…又想：」→ speaker=她，性别女
        val text = "她心里叹息一声又想：“老总的事，可不是我们小职员可以问的。”"
        val quote = RuleEngine.analyze(text).first { it.isQuote }
        assertEquals("她", quote.speaker)
        assertEquals(Gender.FEMALE, quote.gender)
        assertEquals(EmotionTag.SADNESS, quote.emotion)
    }

    @Test
    fun `纯提示语整段剥离`() {
        // 「林风低声道：」整段是提示语，不产生旁白碎片
        val utterances = RuleEngine.analyze("林风低声道：“我们走。”")
        assertEquals(1, utterances.size)
        assertEquals("我们走。", utterances[0].text)
        assertEquals("林风", utterances[0].speaker)
    }

    @Test
    fun `引号后短提示尾剥离`() {
        val utterances = RuleEngine.analyze("“走吧。”他说。")
        assertEquals(1, utterances.size)
        assertEquals("走吧。", utterances[0].text)
    }

    @Test
    fun `动作节拍不被误吞`() {
        // 「他笑了笑。」是动作描写，必须保留为旁白
        val utterances = RuleEngine.analyze("他笑了笑。")
        assertEquals(1, utterances.size)
        assertEquals("他笑了笑。", utterances[0].text)
    }

    @Test
    fun `独立引号段落回退旁白`() {
        // 无任何提示语的整段对白：当前版本回退旁白音色（已知限制）
        val utterances = RuleEngine.analyze("　　“李总，您的电话，是全通公司曾总。”")
        assertEquals(1, utterances.size)
        assertTrue(utterances[0].isQuote)
        assertEquals("李总，您的电话，是全通公司曾总。", utterances[0].text)
    }

    @Test
    fun `具名说话人抽取与性别`() {
        val text = "少女快步走来，脆声喊道：“师兄，等等我！”"
        val quote = RuleEngine.analyze(text).first { it.isQuote }
        assertTrue(quote.speaker.isNotEmpty())
        assertEquals(Gender.FEMALE, quote.gender)
    }

    @Test
    fun `男角色对白走男声`() {
        val text = "林风皱眉道：“此事有诈。”"
        val quote = RuleEngine.analyze(text).first { it.isQuote }
        assertEquals("林风", quote.speaker)
    }

    @Test
    fun `段内对话轮替`() {
        val text = "林风说道：“走吧。”傅山摇头道：“再等等。”“为什么？”"
        val quotes = RuleEngine.analyze(text).filter { it.isQuote }
        assertEquals(3, quotes.size)
        assertEquals("林风", quotes[0].speaker)
        assertEquals("傅山", quotes[1].speaker)
        // 第三句无提示语，轮替回林风
        assertEquals("林风", quotes[2].speaker)
    }

    @Test
    fun `跨段对话轮替`() {
        // 模拟 Legado 按段下发：第一段末尾是傅山的对白，第二段开头无提示对白应轮替回李强
        val p1 = "傅山笑道：“这件宝甲名字叫『满天星』，是件极品内甲。”"
        val p2 = "“啊，不能脱，那我怎么见人？”"
        val q1 = RuleEngine.analyze(p1, "李强", "傅山").filter { it.isQuote }.first()
        assertEquals("傅山", q1.speaker)
        val q2 = RuleEngine.analyze(p2, "傅山", "李强").filter { it.isQuote }.first()
        assertEquals("李强", q2.speaker)
    }

    @Test
    fun `同角分段续说延续上一说话人`() {
        val text = "林风说道：“第一句。”“第二句继续说。”"
        val quotes = RuleEngine.analyze(text).filter { it.isQuote }
        assertEquals("林风", quotes[0].speaker)
        // 无轮替信息（只有林风出现过），第二句延续林风而不是回退旁白
        assertEquals("林风", quotes[1].speaker)
    }

    @Test
    fun `上段以旁白结尾时跨段延续说话人`() {
        // 《飘邈之旅》联调实录：傅山交代完感慨一句，下一段继续指示
        val p1 = "傅山说道：“我第一次也是从这里到封缘星的。”语气里透着感慨。"
        val p2 = "“好，你先站在阵中心，等我启动它。”"
        val q1 = RuleEngine.analyze(p1).filter { it.isQuote }.first()
        assertEquals("傅山", q1.speaker)
        // 上段以旁白结尾、仅傅山一个已知说话人 → 延续傅山而不是回退旁白
        val q2 = RuleEngine.analyze(p2, "傅山", null, prevEndedWithQuote = false)
            .filter { it.isQuote }.first()
        assertEquals("傅山", q2.speaker)
    }

    @Test
    fun `无对白段落保持单旁白`() {
        val text = "　　总经理的为人公司上下都很佩服，他凭着闯劲，一个人以极少的资金创办这家贸易公司。"
        val utterances = RuleEngine.analyze(text)
        assertEquals(1, utterances.size)
        assertTrue(!utterances[0].isQuote)
        assertTrue(utterances[0].text.startsWith("总经理的为人"))
    }
}
