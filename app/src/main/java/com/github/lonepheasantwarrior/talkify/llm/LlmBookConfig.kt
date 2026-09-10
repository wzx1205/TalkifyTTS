package com.github.lonepheasantwarrior.talkify.llm

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 端上 LLM 角色分析设置
 *
 * 独立于 TTS 供应商配置：LLM 只负责"把规则引擎切好的句子，逐句判定
 * 说话人/性别/情绪"，其选择（开关、模型、线程）不应随供应商切换而丢失。
 *
 * 默认关闭。原因：模型约 460MB 需用户显式下载，且小模型判定偶有抖动，
 * 不开时走纯规则引擎（既有行为完全不变）。
 */
object LlmBookConfig {

    private const val PREFS_NAME = "talkify_llm_book"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MODEL_ID = "model_id"
    private const val KEY_THREADS = "threads"
    private const val KEY_LLM_TIMEOUT_MS = "timeout_ms"

    /** 默认模型：Qwen3-0.6B Q4_K_M，真机实测 33 tok/s、PSS 增量约 730MB */
    const val DEFAULT_MODEL_ID = "qwen3_0_6b"

    /** 默认线程数：实测 4 线程最优（6 线程在小核调度下反而更慢） */
    const val DEFAULT_THREADS = 4

    /**
     * 默认单次分析超时
     *
     * 超时即回退规则结果。听书不该因为分析慢而卡住。
     * 取值需覆盖「首次调用时的模型加载 + prompt 预填 + 生成」全过程：
     * 实测模拟器（4 核）整段约需 5~8 秒，真机（8 Elite）约 2~3 秒，
     * 因此给到 12 秒以免在慢设备上被截断成无法解析的半截 JSON。
     */
    const val DEFAULT_TIMEOUT_MS = 12000L

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

    fun getModelId(): String =
        p()?.getString(KEY_MODEL_ID, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_ID

    fun setModelId(id: String) {
        p()?.edit { putString(KEY_MODEL_ID, id) }
    }

    fun getThreads(): Int = (p()?.getInt(KEY_THREADS, DEFAULT_THREADS) ?: DEFAULT_THREADS)

    fun setThreads(n: Int) {
        p()?.edit { putInt(KEY_THREADS, n.coerceIn(1, 8)) }
    }

    fun getTimeoutMs(): Long =
        p()?.getLong(KEY_LLM_TIMEOUT_MS, DEFAULT_TIMEOUT_MS) ?: DEFAULT_TIMEOUT_MS

    fun setTimeoutMs(ms: Long) {
        p()?.edit { putLong(KEY_LLM_TIMEOUT_MS, ms.coerceIn(1000L, 60000L)) }
    }
}
