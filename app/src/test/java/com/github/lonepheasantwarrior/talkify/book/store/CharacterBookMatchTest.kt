package com.github.lonepheasantwarrior.talkify.book.store

import com.github.lonepheasantwarrior.talkify.book.model.Gender
import com.github.lonepheasantwarrior.talkify.book.scan.CharacterScanner
import com.github.lonepheasantwarrior.talkify.book.scan.CharacterProfile
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterBookMatchTest {

    private val book = CharacterBook(
        bookId = "test",
        title = "测试书",
        characters = listOf(
            BookCharacterBinding("陈平安", Gender.MALE, 500, "", "voice_a"),
            BookCharacterBinding("裴钱", Gender.FEMALE, 200, "", "voice_b"),
            BookCharacterBinding("顾璨", Gender.MALE, 100, "", "voice_c")
        )
    )

    @Test
    fun `精确匹配`() {
        assertEquals("voice_a", book.matchSpeaker("陈平安")?.voiceId)
    }

    @Test
    fun `说话人截断名前缀互配`() {
        // 「陈平」是运行时截断，应匹配到对白量最高的「陈平安」
        assertEquals("voice_a", book.matchSpeaker("陈平")?.voiceId)
        assertEquals(Gender.MALE, book.matchSpeaker("陈平")?.gender)
    }

    @Test
    fun `说话人带动作尾巴前缀互配`() {
        // 「顾璨放」←「顾璨放下筷子」误抽，应归回「顾璨」
        assertEquals("voice_c", book.matchSpeaker("顾璨放")?.voiceId)
        assertEquals(Gender.MALE, book.matchSpeaker("顾璨放")?.gender)
    }

    @Test
    fun `无候选返回空`() {
        assertNull(book.matchSpeaker("花媚娘"))
    }

    @Test
    fun `资格线过滤低对白路人`() {
        // 路人各 1 句：低于资格线 3，过滤；主角 3 句：达标保留
        val chapter = (1..4).joinToString("") { "路人$it 说道：“第${it}句话。”" } +
            (1..3).joinToString("") { "主角说道：“我说的第${it}句话。”" }
        val profiles = CharacterScanner.scan(listOf(chapter), minDialogueCount = 3)
        assertTrue(profiles.none { it.name.startsWith("路人") })
        assertEquals("主角", profiles.single().name)
    }

    @Test
    fun `前缀归并比例一倍半`() {
        // 崔东 4 句 + 崔东山 6 句：6 >= 4*1.5 → 崔东并入崔东山
        val chapter = (1..4).joinToString("") { "崔东说道：“短句${it}。”" } +
            (1..6).joinToString("") { "崔东山笑道：“长名第${it}句话。”" }
        val profiles = CharacterScanner.scan(listOf(chapter), minDialogueCount = 2)
        assertEquals(1, profiles.size)
        assertEquals("崔东山", profiles[0].name)
        assertEquals(10, profiles[0].dialogueCount)
    }
}

/** fanchen 多声线包的自动分配：必须从 fanchen 自己的性别池取音色 */
class FanchenAutoAssignTest {

    private fun profile(name: String, gender: Gender) =
        CharacterProfile(name = name, gender = gender, dialogueCount = 5, sampleQuote = "…")

    @Test
    fun assignUsesFanchenPoolsWhenModelProvided() {
        val model = LocalModelRegistry.getModel("vits_zh_fanchen_c")!!
        val bindings = VoiceAutoAssign.assign(
            listOf(profile("裴钱", Gender.MALE), profile("宁姚", Gender.FEMALE)),
            localModelInfo = model
        )
        assertTrue(bindings[0].voiceId.startsWith("fanchen_c_"))
        assertTrue(bindings[1].voiceId.startsWith("fanchen_c_"))
        // 性别对应池正确：裴钱(男) 拿到的音色性别标签应为 MALE
        val male = model.voiceList.first { it.voiceId == bindings[0].voiceId }
        val female = model.voiceList.first { it.voiceId == bindings[1].voiceId }
        assertEquals(Gender.MALE, male.gender)
        assertEquals(Gender.FEMALE, female.gender)
    }

    @Test
    fun assignWithoutModelFallsBackToLocalPool() {
        val bindings = VoiceAutoAssign.assign(listOf(profile("裴钱", Gender.MALE)))
        assertTrue(bindings[0].voiceId.startsWith("zh_male_"))
    }
}
