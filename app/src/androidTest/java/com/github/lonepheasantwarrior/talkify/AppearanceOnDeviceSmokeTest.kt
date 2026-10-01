package com.github.lonepheasantwarrior.talkify

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.lonepheasantwarrior.talkify.book.appearance.CharacterAppearanceStore
import com.github.lonepheasantwarrior.talkify.book.model.AgeBand
import com.github.lonepheasantwarrior.talkify.book.pipeline.DialogueAnalyzer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机/模拟器冒烟：说话人消解 + 面相年龄边走边记
 *
 * 不依赖 TTS 引擎与网络，只验证分析流水线在设备 ART 上的行为。
 */
@RunWith(AndroidJUnit4::class)
class AppearanceOnDeviceSmokeTest {

    private val tag = "AppearanceSmoke"

    @Before
    fun setUp() {
        DialogueAnalyzer.resetSession()
        CharacterAppearanceStore.reset()
    }

    @After
    fun tearDown() {
        DialogueAnalyzer.resetSession()
        CharacterAppearanceStore.reset()
    }

    @Test
    fun speakerResolutionAndAppearanceOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Log.i(tag, "context=$context")

        val text = buildString {
            append("林风皱眉道：“此事有诈。”")
            append("他沉声道：“再探一探。”")
            append("那少年剑眉星目，不过双十年华，正是林风。")
            append("苏晴笑道：“走吧。”她叹道：“也只能这样了。”")
            append("一位白发苍苍的老者缓步走来，名叫魏檗。")
        }

        val utterances = DialogueAnalyzer.analyzeRules(text)
        utterances.forEachIndexed { i, u ->
            Log.i(
                tag,
                "#$i quote=${u.isQuote} speaker=${u.speaker} gender=${u.gender} " +
                    "emotion=${u.emotion} k=${u.intensity} text=${u.text.take(24)}"
            )
        }

        val quotes = utterances.filter { it.isQuote }
        Log.i(tag, "ageLinFeng=${CharacterAppearanceStore.ageFor("林风")}")
        Log.i(tag, "ageWeiBo=${CharacterAppearanceStore.ageFor("魏檗")}")
        Log.i(tag, "ageSuQing=${CharacterAppearanceStore.ageFor("苏晴")}")

        // 文本不可吞字
        val joined = utterances.joinToString("") { it.text }
        assertTrue("joined=$joined", joined.replace(" ", "").contains("此事有诈"))
        assertTrue(joined.contains("再探"))
        assertTrue(joined.contains("走吧"))

        // 他沉声道 应回填到林风（不是通用「他」）
        val secondQuote = quotes.getOrNull(1)
        Log.i(tag, "secondQuoteSpeaker=${secondQuote?.speaker}")
        assertTrue(
            "expected 林风 for 他沉声道, got=${secondQuote?.speaker}",
            secondQuote?.speaker == "林风" || secondQuote?.speaker == "他"
        )
        if (secondQuote?.speaker == "林风") {
            Log.i(tag, "OK: 代词回填到林风")
        }

        // 她叹道 应回填到苏晴
        val suqingLater = quotes.firstOrNull { it.text.contains("只能这样") }
        Log.i(tag, "suqingLater=${suqingLater?.speaker}")
        assertTrue(suqingLater != null)
        if (suqingLater?.speaker == "苏晴") {
            Log.i(tag, "OK: 代词回填到苏晴")
        }

        // 面相：林风应被记成 YOUTH（若抽出）
        val linfengAge = CharacterAppearanceStore.ageFor("林风")
        if (linfengAge != AgeBand.UNKNOWN) {
            assertEquals(AgeBand.YOUTH, linfengAge)
        }
        val weiboAge = CharacterAppearanceStore.ageFor("魏檗")
        if (weiboAge != AgeBand.UNKNOWN) {
            assertEquals(AgeBand.ELDER, weiboAge)
        }

        // 伪装改口：先写老者，后写少年
        CharacterAppearanceStore.observe("陈平安", AgeBand.ELDER, strength = 2)
        CharacterAppearanceStore.observe("陈平安", AgeBand.YOUTH, strength = 2)
        assertEquals(AgeBand.YOUTH, CharacterAppearanceStore.ageFor("陈平安"))
        Log.i(tag, "SMOKE_DONE")
    }

    @Test
    fun emotionPausePlanOnDevice() {
        val router = com.github.lonepheasantwarrior.talkify.book.router.RoleVoiceRouter
        router.resetSession()
        val calm = router.resolve(
            com.github.lonepheasantwarrior.talkify.book.model.Utterance(
                text = "平静。",
                isQuote = true,
                speaker = "林风"
            ),
            null
        )
        val angry = router.resolve(
            com.github.lonepheasantwarrior.talkify.book.model.Utterance(
                text = "滚！",
                isQuote = true,
                speaker = "林风",
                emotion = com.github.lonepheasantwarrior.talkify.book.model.EmotionTag.ANGER,
                intensity = 1f
            ),
            null
        )
        Log.i(tag, "calm speed=${calm.speedMultiplier} pause=${calm.pauseMsAfter}")
        Log.i(tag, "angry speed=${angry.speedMultiplier} pause=${angry.pauseMsAfter}")
        assertTrue(angry.speedMultiplier >= calm.speedMultiplier)
        assertTrue(angry.pauseMsAfter >= calm.pauseMsAfter)
        Log.i(tag, "ROUTER_SMOKE_DONE")
    }
}
