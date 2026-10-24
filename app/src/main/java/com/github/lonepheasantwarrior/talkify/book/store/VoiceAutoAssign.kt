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

    /**
     * fanchen-C（vits_zh_fanchen_c）的性别池
     *
     * 上游 sid 是匿名聚类编号，这里的性别来自我们对全部 187 条声线的
     * 基频声学分析（29 男 / 158 女），按基频升序排列（低沉→沉稳 / 低柔→清亮）。
     * 与 LocalModelRegistry 的 voiceList 排序一致，分配时"池内前排"即"听感最柔"。
     */
        val FANCHEN_FEMALE_POOL = listOf(
            "fanchen_c_174",
            "fanchen_c_102",
            "fanchen_c_151",
            "fanchen_c_181",
            "fanchen_c_183",
            "fanchen_c_175",
            "fanchen_c_32",
            "fanchen_c_23",
            "fanchen_c_48",
            "fanchen_c_177",
            "fanchen_c_83",
            "fanchen_c_38",
            "fanchen_c_36",
            "fanchen_c_29",
            "fanchen_c_80",
            "fanchen_c_162",
            "fanchen_c_148",
            "fanchen_c_140",
            "fanchen_c_144",
            "fanchen_c_7",
            "fanchen_c_131",
            "fanchen_c_169",
            "fanchen_c_178",
            "fanchen_c_13",
            "fanchen_c_27",
            "fanchen_c_96",
            "fanchen_c_57",
            "fanchen_c_59",
            "fanchen_c_88",
            "fanchen_c_113",
            "fanchen_c_149",
            "fanchen_c_176",
            "fanchen_c_186",
            "fanchen_c_47",
            "fanchen_c_9",
            "fanchen_c_94",
            "fanchen_c_180",
            "fanchen_c_4",
            "fanchen_c_19",
            "fanchen_c_106",
            "fanchen_c_112",
            "fanchen_c_117",
            "fanchen_c_24",
            "fanchen_c_56",
            "fanchen_c_70",
            "fanchen_c_77",
            "fanchen_c_81",
            "fanchen_c_82",
            "fanchen_c_132",
            "fanchen_c_158",
            "fanchen_c_133",
            "fanchen_c_138",
            "fanchen_c_179",
            "fanchen_c_6",
            "fanchen_c_62",
            "fanchen_c_146",
            "fanchen_c_147",
            "fanchen_c_84",
            "fanchen_c_93",
            "fanchen_c_114",
            "fanchen_c_165",
            "fanchen_c_30",
            "fanchen_c_12",
            "fanchen_c_18",
            "fanchen_c_51",
            "fanchen_c_108",
            "fanchen_c_120",
            "fanchen_c_164",
            "fanchen_c_184",
            "fanchen_c_3",
            "fanchen_c_49",
            "fanchen_c_104",
            "fanchen_c_16",
            "fanchen_c_37",
            "fanchen_c_71",
            "fanchen_c_87",
            "fanchen_c_91",
            "fanchen_c_129",
            "fanchen_c_160",
            "fanchen_c_155",
            "fanchen_c_11",
            "fanchen_c_35",
            "fanchen_c_41",
            "fanchen_c_67",
            "fanchen_c_79",
            "fanchen_c_128",
            "fanchen_c_168",
            "fanchen_c_54",
            "fanchen_c_90",
            "fanchen_c_99",
            "fanchen_c_101",
            "fanchen_c_115",
            "fanchen_c_123",
            "fanchen_c_137",
            "fanchen_c_170",
            "fanchen_c_61",
            "fanchen_c_63",
            "fanchen_c_22",
            "fanchen_c_64",
            "fanchen_c_73",
            "fanchen_c_78",
            "fanchen_c_89",
            "fanchen_c_109",
            "fanchen_c_111",
            "fanchen_c_122",
            "fanchen_c_134",
            "fanchen_c_142",
            "fanchen_c_159",
            "fanchen_c_161",
            "fanchen_c_172",
            "fanchen_c_2",
            "fanchen_c_20",
            "fanchen_c_31",
            "fanchen_c_55",
            "fanchen_c_68",
            "fanchen_c_85",
            "fanchen_c_145",
            "fanchen_c_154",
            "fanchen_c_166",
            "fanchen_c_185",
            "fanchen_c_1",
            "fanchen_c_26",
            "fanchen_c_118",
            "fanchen_c_0",
            "fanchen_c_17",
            "fanchen_c_28",
            "fanchen_c_34",
            "fanchen_c_50",
            "fanchen_c_126",
            "fanchen_c_130",
            "fanchen_c_150",
            "fanchen_c_152",
            "fanchen_c_182",
            "fanchen_c_173",
            "fanchen_c_125",
            "fanchen_c_141",
            "fanchen_c_5",
            "fanchen_c_8",
            "fanchen_c_44",
            "fanchen_c_45",
            "fanchen_c_103",
            "fanchen_c_135",
            "fanchen_c_157",
            "fanchen_c_39",
            "fanchen_c_86",
            "fanchen_c_97",
            "fanchen_c_127",
            "fanchen_c_43",
            "fanchen_c_53",
            "fanchen_c_139",
            "fanchen_c_14",
            "fanchen_c_66",
            "fanchen_c_92",
            "fanchen_c_121",
            "fanchen_c_33",
            "fanchen_c_42",
            "fanchen_c_105",
            "fanchen_c_116",
        )

        val FANCHEN_MALE_POOL = listOf(
            "fanchen_c_74",
            "fanchen_c_124",
            "fanchen_c_119",
            "fanchen_c_136",
            "fanchen_c_69",
            "fanchen_c_40",
            "fanchen_c_58",
            "fanchen_c_100",
            "fanchen_c_46",
            "fanchen_c_163",
            "fanchen_c_60",
            "fanchen_c_15",
            "fanchen_c_76",
            "fanchen_c_10",
            "fanchen_c_107",
            "fanchen_c_25",
            "fanchen_c_167",
            "fanchen_c_52",
            "fanchen_c_153",
            "fanchen_c_110",
            "fanchen_c_21",
            "fanchen_c_156",
            "fanchen_c_171",
            "fanchen_c_95",
            "fanchen_c_98",
            "fanchen_c_143",
            "fanchen_c_65",
            "fanchen_c_72",
            "fanchen_c_75",
        )

    /**
     * 按当前本地模型解析性别池
     *
     * fanchen 多说话人包 → 它自己的性别池；其余（ZipVoice/未来 VITS 包）→ 内置池。
     * 返回 (女声池, 男声池)，供自动分配与连播防撞共用，保证两处行为一致。
     */
    fun poolsForLocalModel(modelInfo: com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo?): Pair<List<String>, List<String>> {
        if (modelInfo?.id == "vits_zh_fanchen_c") return FANCHEN_FEMALE_POOL to FANCHEN_MALE_POOL
        return FEMALE_POOL to MALE_POOL
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
     *
     * [localModelInfo] 提供时用该模型自己的性别池（如 fanchen 多声线包），
     * 否则按 [scheme] 用对应的预置池。
     */
    fun assign(
        profiles: List<CharacterProfile>,
        scheme: Scheme = Scheme.LOCAL,
        localModelInfo: com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo? = null
    ): List<BookCharacterBinding> {
        val (femalePool, malePool) = localModelInfo?.let { poolsForLocalModel(it) } ?: poolsFor(scheme)
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
