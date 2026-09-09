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

    /**
     * 自动分配：对白量多的角色优先拿池内前排音色；同池循环复用
     */
    fun assign(profiles: List<CharacterProfile>): List<BookCharacterBinding> {
        var femaleIdx = 0
        var maleIdx = 0
        return profiles.map { p ->
            val voiceId = when (p.gender) {
                Gender.FEMALE -> FEMALE_POOL[femaleIdx++ % FEMALE_POOL.size]
                Gender.MALE -> MALE_POOL[maleIdx++ % MALE_POOL.size]
                Gender.UNKNOWN -> {
                    // 性别不明：往人数少的池子里塞，尽量拉开声线
                    if (maleIdx <= femaleIdx) MALE_POOL[maleIdx++ % MALE_POOL.size]
                    else FEMALE_POOL[femaleIdx++ % FEMALE_POOL.size]
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
