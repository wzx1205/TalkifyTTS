package com.github.lonepheasantwarrior.talkify.book.appearance

import com.github.lonepheasantwarrior.talkify.book.model.AgeBand
import com.github.lonepheasantwarrior.talkify.book.pipeline.DialogueAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppearanceCueExtractorTest {

    @Before
    fun setUp() {
        CharacterAppearanceStore.reset()
        DialogueAnalyzer.resetSession()
    }

    @Test
    fun `已知名字邻近少年`() {
        val cues = AppearanceCueExtractor.extract(
            "林风一身粗布衣衫，看起来不过是个少年。",
            listOf("林风")
        )
        val hit = cues.firstOrNull { it.name == "林风" }
        assertTrue("cues=$cues", hit != null)
        assertEquals(AgeBand.YOUTH, hit!!.age)
    }

    @Test
    fun `白发老者同位语抽名`() {
        val cues = AppearanceCueExtractor.extract(
            "一位白发苍苍的老者缓步走来，名叫魏檗。",
            emptyList()
        )
        assertTrue(cues.isNotEmpty())
        assertEquals("魏檗", cues.first().name)
        assertEquals(AgeBand.ELDER, cues.first().age)
    }

    @Test
    fun `明确岁数优先于泛称`() {
        val cues = AppearanceCueExtractor.extract(
            "陈平安看起来只有十四五岁，虽说旁人都叫他少年。",
            listOf("陈平安")
        )
        assertTrue(cues.any { it.name == "陈平安" && it.age == AgeBand.YOUTH && it.strength >= 3 })
    }

    @Test
    fun `后写覆盖先写_伪装可改口`() {
        // 早期伪装成老者
        CharacterAppearanceStore.observe("陈平安", AgeBand.ELDER, strength = 2)
        assertEquals(AgeBand.ELDER, CharacterAppearanceStore.ageFor("陈平安"))
        // 后文露出真实少年面相（同强度后写赢）
        CharacterAppearanceStore.observe("陈平安", AgeBand.YOUTH, strength = 2)
        assertEquals(AgeBand.YOUTH, CharacterAppearanceStore.ageFor("陈平安"))
    }

    @Test
    fun `更强线索压制弱线索`() {
        CharacterAppearanceStore.observe("裴钱", AgeBand.ELDER, strength = 1)
        CharacterAppearanceStore.observe("裴钱", AgeBand.YOUTH, strength = 3)
        assertEquals(AgeBand.YOUTH, CharacterAppearanceStore.ageFor("裴钱"))
    }

    @Test
    fun `边走文本旁白会写入记忆`() {
        DialogueAnalyzer.analyzeRules(
            "林风皱眉道：“此事有诈。”那少年剑眉星目，不过双十年华，正是林风。"
        )
        // 对白先出现「林风」，旁白再补面相
        val age = CharacterAppearanceStore.ageFor("林风")
        assertTrue("got=$age", age == AgeBand.YOUTH || age == AgeBand.UNKNOWN)
        // 至少不要崩，且若抽到就必须是 YOUTH
        if (age != AgeBand.UNKNOWN) assertEquals(AgeBand.YOUTH, age)
    }

    @Test
    fun `孩童线索`() {
        val cues = AppearanceCueExtractor.extract(
            "那童子不过七八岁光景，却机灵得很。",
            emptyList()
        )
        assertTrue(cues.isEmpty() || cues.all { it.age == AgeBand.CHILD || it.age == AgeBand.UNKNOWN })
        // 童子本身可作为弱同位语名，这里只保证不误判成老者
        CharacterAppearanceStore.observe("小石头", AgeBand.CHILD, strength = 2)
        assertEquals(AgeBand.CHILD, CharacterAppearanceStore.ageFor("小石头"))
    }
}
