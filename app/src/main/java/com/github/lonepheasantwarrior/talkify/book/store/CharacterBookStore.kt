package com.github.lonepheasantwarrior.talkify.book.store

import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.book.model.Gender
import com.github.lonepheasantwarrior.talkify.book.pipeline.DialogueAnalyzer
import com.github.lonepheasantwarrior.talkify.book.router.RoleVoiceRouter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 角色册持久化（JSON 文件，org.json 序列化）
 *
 * 文件布局：<filesDir>/character_books/<bookId>.json
 * 「当前生效的书」记录在 talkify_book_tts prefs（与多角色开关同域），
 * 切换生效书时重置运行时会话状态（轮替/槽位/性别投票）。
 */
object CharacterBookStore {

    private const val KEY_ACTIVE_BOOK = "active_character_book"

    @Volatile
    private var dir: File? = null

    private fun storeDir(): File {
        return dir ?: run {
            val ctx = TalkifyAppHolder.getContext()
                ?: throw IllegalStateException("Context unavailable for CharacterBookStore")
            File(ctx.filesDir, "character_books").apply { mkdirs() }.also { dir = it }
        }
    }

    // ---- 查询（合成热路径） ----

    /** 当前生效书里某角色的音色绑定（含前缀互配）；无生效书/无绑定返回 null */
    fun activeVoiceFor(name: String): String? {
        val id = activeBookId() ?: return null
        return load(id)?.matchSpeaker(name)?.voiceId
    }

    /** 当前生效书的旁白声线；无生效书/未设置返回 null */
    fun activeNarratorVoice(): String? {
        val id = activeBookId() ?: return null
        return load(id)?.narratorVoiceId?.takeIf { it.isNotBlank() }
    }

    /** 当前生效书里某角色的全书扫描性别（含前缀互配）；无生效书/无绑定返回 null */
    fun activeGenderFor(name: String): Gender? {
        val id = activeBookId() ?: return null
        return load(id)?.matchSpeaker(name)?.gender
    }

    fun activeBookId(): String? {
        val ctx = TalkifyAppHolder.getContext() ?: return null
        val prefs = ctx.getSharedPreferences("talkify_book_tts", 0)
        return prefs.getString(KEY_ACTIVE_BOOK, null)
    }

    // ---- 管理 ----

    fun setActiveBook(bookId: String?) {
        val ctx = TalkifyAppHolder.getContext()
            ?: throw IllegalStateException("Context unavailable for CharacterBookStore")
        ctx.getSharedPreferences("talkify_book_tts", 0)
            .edit().putString(KEY_ACTIVE_BOOK, bookId).apply()
        // 换书后旧会话状态（轮替/槽位/性别投票）不再适用
        DialogueAnalyzer.resetSession()
        RoleVoiceRouter.resetSession()
    }

    fun save(book: CharacterBook) {
        val json = JSONObject().apply {
            put("bookId", book.bookId)
            put("title", book.title)
            put("narratorVoiceId", book.narratorVoiceId)
            put("characters", JSONArray().apply {
                book.characters.forEach { c ->
                    put(JSONObject().apply {
                        put("name", c.name)
                        put("gender", c.gender.name)
                        put("dialogueCount", c.dialogueCount)
                        put("sampleQuote", c.sampleQuote)
                        put("voiceId", c.voiceId)
                    })
                }
            })
        }
        File(storeDir(), "${book.bookId}.json").writeText(json.toString())
    }

    fun load(bookId: String): CharacterBook? {
        val f = File(storeDir(), "$bookId.json")
        if (!f.exists()) return null
        return try {
            val json = JSONObject(f.readText())
            val chars = mutableListOf<BookCharacterBinding>()
            val arr = json.optJSONArray("characters") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                chars.add(
                    BookCharacterBinding(
                        name = o.getString("name"),
                        gender = runCatching { Gender.valueOf(o.getString("gender")) }
                            .getOrDefault(Gender.UNKNOWN),
                        dialogueCount = o.optInt("dialogueCount", 0),
                        sampleQuote = o.optString("sampleQuote", ""),
                        voiceId = o.getString("voiceId")
                    )
                )
            }
            CharacterBook(
                bookId = json.getString("bookId"),
                title = json.optString("title", ""),
                characters = chars,
                narratorVoiceId = json.optString("narratorVoiceId", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    fun list(): List<CharacterBook> {
        return storeDir().listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { load(it.name.removeSuffix(".json")) }
            ?.sortedBy { it.title }
            ?: emptyList()
    }

    fun delete(bookId: String) {
        File(storeDir(), "$bookId.json").delete()
        if (activeBookId() == bookId) setActiveBook(null)
    }

    /** 更新单个角色的音色绑定（UI 手动调整） */
    fun updateVoice(bookId: String, characterName: String, voiceId: String) {
        val book = load(bookId) ?: return
        val updated = book.characters.map {
            if (it.name == characterName) it.copy(voiceId = voiceId) else it
        }
        save(book.copy(characters = updated))
    }

    /** 更新旁白声线（UI 手动调整） */
    fun updateNarratorVoice(bookId: String, voiceId: String) {
        val book = load(bookId) ?: return
        save(book.copy(narratorVoiceId = voiceId))
    }
}
