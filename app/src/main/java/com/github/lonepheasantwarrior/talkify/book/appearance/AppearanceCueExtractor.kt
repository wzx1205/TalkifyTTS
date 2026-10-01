package com.github.lonepheasantwarrior.talkify.book.appearance

import com.github.lonepheasantwarrior.talkify.book.model.AgeBand

/**
 * 旁白面相/年龄线索抽取（零模型、边走边扫）
 *
 * 网文里年龄/面相大多写在旁白介绍与外貌描写里，例如：
 * - 「陈平安看起来只有十四五岁」
 * - 「那少年剑眉星目，正是林风」
 * - 「一位白发老者缓步走来，自称魏檗」
 *
 * 主角常伪装（易容/隐藏修为年龄），所以**后写覆盖先写**，
 * 不做一锤定音；多次同向命中会抬高置信，反向命中直接换段。
 */
object AppearanceCueExtractor {

    /**
     * @param name 关联到的角色名（已尽量与说话人名对齐）
     * @param age 推断年龄段
     * @param strength 线索强度 1..3（3=明确岁数/白发皱纹，2=少年老者等称谓，1=弱修饰）
     */
    data class Cue(
        val name: String,
        val age: AgeBand,
        val strength: Int
    )

    private data class AgeLex(
        val token: String,
        val band: AgeBand,
        val strength: Int
    )

    /** 明确岁数/极强面相 */
    private val strongLex = listOf(
        AgeLex("十四五岁", AgeBand.YOUTH, 3),
        AgeLex("十五六岁", AgeBand.YOUTH, 3),
        AgeLex("十六七岁", AgeBand.YOUTH, 3),
        AgeLex("十七八岁", AgeBand.YOUTH, 3),
        AgeLex("双十年华", AgeBand.YOUTH, 3),
        AgeLex("三十许", AgeBand.ADULT, 3),
        AgeLex("四十许", AgeBand.ADULT, 3),
        AgeLex("年过花甲", AgeBand.ELDER, 3),
        AgeLex("年逾古稀", AgeBand.ELDER, 3),
        AgeLex("七老八十", AgeBand.ELDER, 3),
        AgeLex("耄耋", AgeBand.ELDER, 3),
        AgeLex("白发苍苍", AgeBand.ELDER, 3),
        AgeLex("鹤发童颜", AgeBand.ELDER, 3),
        AgeLex("满脸皱纹", AgeBand.ELDER, 3),
        AgeLex("老态龙钟", AgeBand.ELDER, 3)
    )

    /** 称谓级线索 */
    private val mediumLex = listOf(
        AgeLex("孩童", AgeBand.CHILD, 2),
        AgeLex("小孩", AgeBand.CHILD, 2),
        AgeLex("童子", AgeBand.CHILD, 2),
        AgeLex("女童", AgeBand.CHILD, 2),
        AgeLex("男童", AgeBand.CHILD, 2),
        AgeLex("少年", AgeBand.YOUTH, 2),
        AgeLex("少女", AgeBand.YOUTH, 2),
        AgeLex("青年", AgeBand.YOUTH, 2),
        AgeLex("少年郎", AgeBand.YOUTH, 2),
        AgeLex("中年", AgeBand.ADULT, 2),
        AgeLex("壮年", AgeBand.ADULT, 2),
        AgeLex("老者", AgeBand.ELDER, 2),
        AgeLex("老翁", AgeBand.ELDER, 2),
        AgeLex("老妪", AgeBand.ELDER, 2),
        AgeLex("老汉", AgeBand.ELDER, 2),
        AgeLex("老太", AgeBand.ELDER, 2),
        AgeLex("老人", AgeBand.ELDER, 2)
    )

    private val allLex = strongLex + mediumLex

    /**
     * 从一段旁白里抽年龄线索。
     *
     * @param text 旁白/叙述正文
     * @param knownNames 已知角色名（对白里出现过的人），用于「名字+面相」对齐
     */
    fun extract(text: String, knownNames: Collection<String> = emptyList()): List<Cue> {
        if (text.isBlank()) return emptyList()
        val cues = ArrayList<Cue>()

        // 1) 已知名字邻近窗口里的年龄词
        for (name in knownNames) {
            if (name.length < 2) continue
            var from = 0
            while (true) {
                val idx = text.indexOf(name, from)
                if (idx < 0) break
                val window = text.substring(
                    (idx - 24).coerceAtLeast(0),
                    (idx + name.length + 36).coerceAtMost(text.length)
                )
                bestLex(window)?.let { lex ->
                    cues += Cue(name, lex.band, lex.strength)
                }
                from = idx + name.length
            }
        }

        // 2) 「名叫/是/唤作」结构：年龄词 + 称谓 + 人名
        //    「一位白发老者……名叫魏檗」「那少年正是林风」
        cues += extractByApposition(text, knownNames)

        // 3) 「人名 + 看起来/瞧上去/不过 + 年龄词」
        cues += extractLooksLike(text)

        // 同名同段取最强，冲突取更强/后出现（伪装场景依赖后写）
        val merged = LinkedHashMap<String, Cue>()
        for (c in cues) {
            val prev = merged[c.name]
            if (prev == null || c.strength >= prev.strength) {
                merged[c.name] = c
            }
        }
        return merged.values.toList()
    }

    private fun bestLex(window: String): AgeLex? =
        allLex
            .filter { window.contains(it.token) }
            .maxByOrNull { it.strength }

    /**
     * 同位语：年龄词后 20 字内出现「名叫/唤作/是/叫」+ 2~4 字人名
     */
    private fun extractByApposition(text: String, knownNames: Collection<String>): List<Cue> {
        val cues = ArrayList<Cue>()
        for (lex in allLex) {
            var from = 0
            while (true) {
                val idx = text.indexOf(lex.token, from)
                if (idx < 0) break
                val tail = text.substring(idx, (idx + 48).coerceAtMost(text.length))
                val name = pullNameAfterLink(tail) ?: knownNameIn(tail, knownNames)
                if (name != null) {
                    cues += Cue(name, lex.band, lex.strength)
                }
                from = idx + lex.token.length
            }
        }
        return cues
    }

    private fun pullNameAfterLink(tail: String): String? {
        val links = listOf("名叫", "唤作", "叫做", "自称", "正是", "便是", "叫")
        for (link in links) {
            val p = tail.indexOf(link)
            if (p < 0) continue
            val after = tail.substring(p + link.length).trimStart('，', '。', ' ', '是')
            val name = after.takeWhile { it.code in 0x4E00..0x9FA5 }
                .take(4)
                .let { candidate ->
                    when {
                        candidate.length >= 2 -> candidate.take(3).takeIf { looksLikeName(it) }
                            ?: candidate.take(2).takeIf { looksLikeName(it) }
                        else -> null
                    }
                }
            if (name != null) return name
        }
        return null
    }

    private fun knownNameIn(tail: String, knownNames: Collection<String>): String? =
        knownNames.filter { tail.contains(it) }.maxByOrNull { it.length }

    /**
     * 「林风看起来只有十四五岁」「他瞧上去不过双十年华」
     * 主语取年龄词前 8 字内的 2~3 字人名
     */
    private fun extractLooksLike(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        val verbs = listOf("看起来", "瞧上去", "看上去年纪", "不过", "只有", "约莫", "大约", "年纪")
        for (lex in allLex) {
            var from = 0
            while (true) {
                val idx = text.indexOf(lex.token, from)
                if (idx < 0) break
                val head = text.substring((idx - 16).coerceAtLeast(0), idx)
                val nearVerb = verbs.any { head.contains(it) || head.endsWith(it.takeLast(2)) }
                if (nearVerb) {
                    val cleaned = head.trimEnd(
                        '，', '。', ' ', '看', '瞧', '只', '不', '约', '大', '年', '纪', '是', '个', '过', '了', '的'
                    )
                    val name = when {
                        cleaned.length >= 3 && looksLikeName(cleaned.takeLast(3)) -> cleaned.takeLast(3)
                        cleaned.length >= 2 && looksLikeName(cleaned.takeLast(2)) -> cleaned.takeLast(2)
                        else -> null
                    }
                    if (name != null) cues += Cue(name, lex.band, lex.strength + 1)
                }
                from = idx + lex.token.length
            }
        }
        return cues
    }

    /** 极简人名启发式：2~4 汉字，排除常见虚词/动作/系词尾字 */
    private fun looksLikeName(token: String): Boolean {
        if (token.length !in 2..4) return false
        if (token.any {
                it == '了' || it == '的' || it == '着' || it == '之' ||
                    it == '是' || it == '不' || it == '过' || it == '个' || it == '来'
            }
        ) return false
        val stopLast = "道说喊问笑叹声岁年纪的样子"
        if (token.last() in stopLast) return false
        val stopFirst = "不无就也都很太真的了着是这那其"
        if (token.first() in stopFirst) return false
        return true
    }
}
