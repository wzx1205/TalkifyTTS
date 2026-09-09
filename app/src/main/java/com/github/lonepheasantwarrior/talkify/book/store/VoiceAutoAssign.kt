package com.github.lonepheasantwarrior.talkify.book.store

import com.github.lonepheasantwarrior.talkify.book.model.Gender
import com.github.lonepheasantwarrior.talkify.book.scan.CharacterProfile

/**
 * 内置音色池（与 assets/voices、local_model_voices.xml 的 voiceId 一致）
 *
 * 旁白默认占用 zh_male_cixingjieshuonan（磁性男解说），不参与角色分配；
 * 分配按对白量排序循环取池内音色，超出池大小后从头复用。
 */
object VoiceAutoAssign {

    /** 音色方案：一套引擎对应一组同性别音色池 */
    enum class Scheme { LOCAL, MIMO, EDGE }

    val FEMALE_POOL = listOf(
        "zh_female_qingchezizi_uranus_bigtts",
        "zh_female_meilinvyou_uranus_bigtts",
        "zh_female_wenroushunv_uranus_bigtts",
        "zh_female_jiaochuannv_uranus_bigtts",
        "zh_female_gaolengyujie_uranus_bigtts",
        "zh_female_wenroumama_uranus_bigtts",
        "zh_female_vv_uranus_bigtts"
    )

    val MALE_POOL = listOf(
        "zh_male_m191_uranus_bigtts",
        "zh_male_liufei_uranus_bigtts",
        "zh_male_dongfanghaoran_uranus_bigtts",
        "zh_male_xuanyijieshuo_uranus_bigtts"
    )

    private val MIMO_FEMALE_POOL = listOf("茉莉", "冰糖")
    private val MIMO_MALE_POOL = listOf("苏打", "白桦")

    private val EDGE_FEMALE_POOL = listOf(
        "zh-CN-XiaoyiNeural",
        "zh-CN-XiaoxiaoNeural",
        "zh-CN-XiaoshuangNeural"
    )

    private val EDGE_MALE_POOL = listOf(
        "zh-CN-YunxiNeural",
        "zh-CN-YunjianNeural",
        "zh-CN-YunxiaNeural",
        "zh-CN-YunyangNeural"
    )

    fun poolsFor(scheme: Scheme): Pair<List<String>, List<String>> = when (scheme) {
        Scheme.LOCAL -> FEMALE_POOL to MALE_POOL
        Scheme.MIMO -> MIMO_FEMALE_POOL to MIMO_MALE_POOL
        Scheme.EDGE -> EDGE_FEMALE_POOL to EDGE_MALE_POOL
    }

    /** 判定一个音色 id 属于哪套方案 */
    fun schemeOf(voiceId: String): Scheme? = when {
        voiceId.startsWith("zh-CN-") -> Scheme.EDGE
        voiceId.startsWith("zh_female_") || voiceId.startsWith("zh_male_") -> Scheme.LOCAL
        voiceId in MIMO_FEMALE_POOL || voiceId in MIMO_MALE_POOL -> Scheme.MIMO
        else -> null
    }

    /**
     * 跨方案转换一个音色：在同性别池内按索引映射（可逆）。
     * 无法识别的音色返回 null（调用方保留原值或走槽位）。
     */
    fun convertVoice(voiceId: String, gender: Gender?, target: Scheme): String? {
        if (schemeOf(voiceId) == target) return voiceId
        val (femalePool, malePool) = poolsFor(target)
        // 先看原音色在它自己方案的池内位置，按索引映射到目标池
        val sourceScheme = schemeOf(voiceId) ?: return defaultFor(gender, target)
        val (srcFemale, srcMale) = poolsFor(sourceScheme)
        val srcPool = if (voiceId in srcFemale) srcFemale else srcMale
        val idx = srcPool.indexOf(voiceId)
        if (idx < 0) return defaultFor(gender, target)
        val isFemale = srcPool === srcFemale
        val dstPool = if (isFemale) femalePool else malePool
        return dstPool[idx % dstPool.size]
    }

    private fun defaultFor(gender: Gender?, target: Scheme): String? {
        val (femalePool, malePool) = poolsFor(target)
        return if (gender == Gender.FEMALE) femalePool.first() else malePool.first()
    }

    /**
     * 自动分配：对白量多的角色优先拿池内前排音色；同池循环复用
     */
    fun assign(
        profiles: List<CharacterProfile>,
        scheme: Scheme = Scheme.LOCAL
    ): List<BookCharacterBinding> {
        val (femalePool, malePool) = poolsFor(scheme)
        var femaleIdx = 0
        var maleIdx = 0
        return profiles.map { p ->
            val voiceId = when (p.gender) {
                Gender.FEMALE -> femalePool[femaleIdx++ % femalePool.size]
                Gender.MALE -> malePool[maleIdx++ % malePool.size]
                Gender.UNKNOWN -> {
                    // 性别不明：往人数少的池子里塞，尽量拉开声线
                    if (maleIdx <= femaleIdx) malePool[maleIdx++ % malePool.size]
                    else femalePool[femaleIdx++ % femalePool.size]
                }
            }
            BookCharacterBinding(
                name = p.name,
                gender = p.gender,
                dialogueCount = p.dialogueCount,
                sampleQuote = p.sampleQuote,
                voiceId = voiceId
            )
        }
    }
}
