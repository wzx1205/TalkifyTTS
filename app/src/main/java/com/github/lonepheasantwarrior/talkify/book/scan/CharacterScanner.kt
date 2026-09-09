package com.github.lonepheasantwarrior.talkify.book.scan

import com.github.lonepheasantwarrior.talkify.book.model.Gender
import com.github.lonepheasantwarrior.talkify.book.model.Utterance
import com.github.lonepheasantwarrior.talkify.book.pipeline.RuleEngine

/**
 * 全书扫描出的角色画像
 *
 * @param name 说话人名（规则分析器抽取的具名角色）
 * @param gender 全书性别投票多数派
 * @param dialogueCount 全书对白句数（按角色聚合）
 * @param sampleQuote 最长对白样例（供用户确认角色时参考）
 */
data class CharacterProfile(
    val name: String,
    val gender: Gender,
    val dialogueCount: Int,
    val sampleQuote: String
)

/**
 * 全书角色扫描
 *
 * 对 EPUB 各章文本跑规则分析器，把逐句的说话人归因聚合成
 * 「角色画像列表」。与运行时顺流分析不同：全书扫描没有会话
 * 概念，每章独立分析，所以多角色连续对白中依赖轮替猜出的
 * 「她/他/旁白」不计入具名角色。
 */
object CharacterScanner {

    /** 代词/群体不是可绑定音色的角色 */
    private val NON_CHARACTERS = setOf("她", "他", "它", "众人", "两人", "大家", "自己")

    fun scan(chapterTexts: List<String>, minDialogueCount: Int = 3): List<CharacterProfile> {
        data class Acc(
            var count: Int = 0,
            var femaleVotes: Int = 0,
            var maleVotes: Int = 0,
            var sample: String = ""
        )

        val acc = HashMap<String, Acc>()

        for (chapter in chapterTexts) {
            for (u in RuleEngine.analyze(chapter)) {
                if (!u.isQuote) continue
                val speaker = u.speaker
                if (speaker == Utterance.SPEAKER_NARRATOR || speaker in NON_CHARACTERS) continue
                if (speaker.length !in 2..4) continue

                val a = acc.getOrPut(speaker) { Acc() }
                a.count++
                when (u.gender) {
                    Gender.FEMALE -> a.femaleVotes++
                    Gender.MALE -> a.maleVotes++
                    Gender.UNKNOWN -> {}
                }
                if (u.text.length > a.sample.length) a.sample = u.text
            }
        }

        val profiles = acc.mapNotNull { (name, a) ->
            if (a.count < minDialogueCount) return@mapNotNull null  // 资格线：对白太少的当路人
            val gender = when {
                a.femaleVotes > a.maleVotes -> Gender.FEMALE
                a.maleVotes > a.femaleVotes -> Gender.MALE
                else -> Gender.UNKNOWN
            }
            CharacterProfile(
                name = name,
                gender = gender,
                dialogueCount = a.count,
                sampleQuote = a.sample.take(80)
            )
        }.sortedByDescending { it.dialogueCount }

        return mergePrefixTruncations(profiles).take(MAX_CHARACTERS)
    }

    /**
     * 截断名合并：抽取残留的「陈平」「崔东」并入「陈平安」「崔东山」。
     * 长名对白量须明显占优（≥1.5 倍）才判定短名为截断，
     * 避免误合同名不同角色。
     */
    private fun mergePrefixTruncations(profiles: List<CharacterProfile>): List<CharacterProfile> {
        val counts = HashMap<String, Int>()
        profiles.forEach { counts[it.name] = it.dialogueCount }
        val absorbed = HashSet<String>()

        for (short in profiles) {
            if (short.name in absorbed) continue
            val full = profiles.firstOrNull {
                it.name.length > short.name.length &&
                    it.name.startsWith(short.name) &&
                    it.dialogueCount >= short.dialogueCount * 1.5f
            } ?: continue
            counts[full.name] = (counts[full.name] ?: full.dialogueCount) + short.dialogueCount
            absorbed.add(short.name)
        }

        return profiles
            .filter { it.name !in absorbed }
            .map { it.copy(dialogueCount = counts[it.name] ?: it.dialogueCount) }
    }

    private const val MAX_CHARACTERS = 300
}
