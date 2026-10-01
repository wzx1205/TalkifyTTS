package com.github.lonepheasantwarrior.talkify.book.router

import com.github.lonepheasantwarrior.talkify.book.config.BookTtsSettings
import com.github.lonepheasantwarrior.talkify.book.model.EmotionTag
import com.github.lonepheasantwarrior.talkify.book.model.Gender
import com.github.lonepheasantwarrior.talkify.book.model.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RoleVoiceRouterTest {

    @Test
    fun `相邻对白不同角色不撞声线`() {
        RoleVoiceRouter.resetSession()
        // 两个男角色撞到同一槽位声线时，后者应被判定为碰撞
        RoleVoiceRouter.registerSpoken("陈灵均", "苏打")
        assertTrue(RoleVoiceRouter.collidesWithPrevious("魏檗", "苏打"))
        assertTrue(!RoleVoiceRouter.collidesWithPrevious("陈灵均", "苏打")) // 同角色续说不算撞
        assertTrue(!RoleVoiceRouter.collidesWithPrevious("魏檗", "白桦")) // 换了声线不算撞
    }

    @Test
    fun `性别提示修正槽位`() {
        // 《剑来》实录：裴钱逐句窗口无线索(UNKNOWN)，需角色册全书投票性别补正
        RoleVoiceRouter.resetSession()
        val u = Utterance(text = "测试。", isQuote = true, speaker = "裴钱", gender = Gender.UNKNOWN)
        val slot = RoleVoiceRouter.slotFor(u, Gender.FEMALE)
        assertEquals(BookTtsSettings.ROLE_FEMALE, slot)
        // 同一说话人稳定复用槽位
        assertEquals(slot, RoleVoiceRouter.slotFor(u.copy(), Gender.FEMALE))
    }

    private fun quote(speaker: String, gender: Gender = Gender.MALE) = Utterance(
        text = "测试对白。",
        isQuote = true,
        speaker = speaker,
        gender = gender,
        emotion = EmotionTag.CALM
    )

    @Test
    fun `不同段落的不同男性角色保持不同音色`() {
        // 联调暴露的 bug：resetSession 每段清空导致司机/李强都分到同一音色
        RoleVoiceRouter.resetSession()
        val driver = RoleVoiceRouter.resolve(quote("司机"), null)
        val liqiang = RoleVoiceRouter.resolve(quote("李强"), null)
        assertNotEquals(driver.voiceId, liqiang.voiceId)
    }

    @Test
    fun `同一角色跨段稳定复用同一音色`() {
        RoleVoiceRouter.resetSession()
        val first = RoleVoiceRouter.resolve(quote("林风"), null)
        val second = RoleVoiceRouter.resolve(quote("林风"), null)
        assertEquals(first.voiceId, second.voiceId)
    }

    @Test
    fun `旁白走narrator槽位`() {
        RoleVoiceRouter.resetSession()
        val narration = Utterance(text = "旁白文本。")
        val plan = RoleVoiceRouter.resolve(narration, "user_voice")
        assertEquals("zh_male_cixingjieshuonan_uranus_bigtts", plan.voiceId)
    }

    @Test
    fun `女性角色走female槽位`() {
        RoleVoiceRouter.resetSession()
        val plan = RoleVoiceRouter.resolve(quote("小玉", Gender.FEMALE), null)
        assertEquals("zh_female_qingchezizi_uranus_bigtts", plan.voiceId)
    }

    @Test
    fun `高强度悲伤压慢语速并加长停顿`() {
        RoleVoiceRouter.resetSession()
        val calm = RoleVoiceRouter.resolve(
            quote("林风").copy(emotion = EmotionTag.CALM, intensity = 0f), null
        )
        val sad = RoleVoiceRouter.resolve(
            quote("林风").copy(emotion = EmotionTag.SADNESS, intensity = 1f), null
        )
        assertTrue("sad speed=${sad.speedMultiplier} should be slower than calm=${calm.speedMultiplier}",
            sad.speedMultiplier < calm.speedMultiplier)
        assertTrue("sad pause=${sad.pauseMsAfter} should exceed calm=${calm.pauseMsAfter}",
            sad.pauseMsAfter > calm.pauseMsAfter)
    }

    @Test
    fun `高强度恐惧加速语速`() {
        RoleVoiceRouter.resetSession()
        val calm = RoleVoiceRouter.resolve(
            quote("林风").copy(emotion = EmotionTag.CALM, intensity = 0f), null
        )
        val fear = RoleVoiceRouter.resolve(
            quote("林风").copy(emotion = EmotionTag.FEAR, intensity = 1f), null
        )
        assertTrue(fear.speedMultiplier > calm.speedMultiplier)
    }

    @Test
    fun `换说话人时停顿不短于同角续说`() {
        RoleVoiceRouter.resetSession()
        val first = RoleVoiceRouter.resolve(quote("林风"), null)
        RoleVoiceRouter.registerSpoken("林风", first.voiceId)
        val same = RoleVoiceRouter.resolve(quote("林风"), null)
        RoleVoiceRouter.registerSpoken("林风", same.voiceId)
        val other = RoleVoiceRouter.resolve(quote("傅山"), null)
        assertTrue("switch pause=${other.pauseMsAfter} vs same=${same.pauseMsAfter}",
            other.pauseMsAfter >= same.pauseMsAfter)
    }

    @Test
    fun `旁白停顿不过长`() {
        RoleVoiceRouter.resetSession()
        val narration = Utterance(
            text = "旁白文本。",
            emotion = EmotionTag.SADNESS,
            intensity = 1f
        )
        val plan = RoleVoiceRouter.resolve(narration, null)
        assertTrue(plan.pauseMsAfter <= 60)
    }
}
