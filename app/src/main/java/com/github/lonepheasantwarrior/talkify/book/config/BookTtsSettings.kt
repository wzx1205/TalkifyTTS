package com.github.lonepheasantwarrior.talkify.book.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 多角色听书开关与角色→音色绑定
 *
 * 独立于供应商配置，仅影响本地模型（ZipVoice）路径。
 * 默认关闭，避免升级用户被无声改变听感。
 */
object BookTtsSettings {

    private const val PREFS_NAME = "talkify_book_tts"
    private const val KEY_ENABLED = "book_mode_enabled"
    private const val KEY_ROLE_PREFIX = "role_voice_"

    /** 默认角色槽位 */
    const val ROLE_NARRATOR = "narrator"
    const val ROLE_MALE = "male"
    const val ROLE_FEMALE = "female"
    const val ROLE_MALE2 = "male2"
    const val ROLE_FEMALE2 = "female2"

    /** 默认绑定到内置 12 音色中的代表声线 */
    val DEFAULT_ROLE_VOICES: Map<String, String> = mapOf(
        ROLE_NARRATOR to "zh_male_cixingjieshuonan_uranus_bigtts",
        ROLE_MALE to "zh_male_m191_uranus_bigtts",
        ROLE_FEMALE to "zh_female_qingchezizi_uranus_bigtts",
        ROLE_MALE2 to "zh_male_liufei_uranus_bigtts",
        ROLE_FEMALE2 to "zh_female_wenroushunv_uranus_bigtts"
    )

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun p(): SharedPreferences? = prefs

    fun isEnabled(): Boolean = p()?.getBoolean(KEY_ENABLED, false) ?: false

    fun setEnabled(enabled: Boolean) {
        p()?.edit { putBoolean(KEY_ENABLED, enabled) }
    }

    /**
     * 获取角色音色；未绑定时回落默认表，再回落 null（调用方用用户当前音色）
     */
    fun voiceForRole(role: String): String? {
        val stored = p()?.getString(KEY_ROLE_PREFIX + role, null)
        if (!stored.isNullOrBlank()) return stored
        return DEFAULT_ROLE_VOICES[role]
    }

    fun setRoleVoice(role: String, voiceId: String) {
        p()?.edit { putString(KEY_ROLE_PREFIX + role, voiceId) }
    }

    fun allRoleVoices(): Map<String, String> {
        val roles = listOf(ROLE_NARRATOR, ROLE_MALE, ROLE_FEMALE, ROLE_MALE2, ROLE_FEMALE2)
        return roles.associateWith { voiceForRole(it) ?: "" }
            .filterValues { it.isNotBlank() }
    }
}
