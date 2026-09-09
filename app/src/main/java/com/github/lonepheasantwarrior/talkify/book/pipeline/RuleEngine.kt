package com.github.lonepheasantwarrior.talkify.book.pipeline

import com.github.lonepheasantwarrior.talkify.book.model.EmotionTag
import com.github.lonepheasantwarrior.talkify.book.model.Gender
import com.github.lonepheasantwarrior.talkify.book.model.Utterance

/**
 * 网文对白规则分析器（零模型、可离线）
 *
 * 覆盖常见网文形态：
 * - 中英引号对白："" 「」 『』
 * - 提示语：说/道/喊/问/吼/低声道/冷笑道…
 * - 性别线索：他/她、男子/女子、少年/少女、哥/姐…
 * - 情感线索：标点 + 提示动词/副词
 *
 * 复杂指代（多角色连续对话无提示语）交由后续可选 LLM 分析器增强。
 */
object RuleEngine {

    private data class QuoteSpan(
        val start: Int,
        val end: Int, // exclusive, includes closing quote
        val innerStart: Int,
        val innerEnd: Int
    )

    private val pairQuotes = listOf(
        '“' to '”',
        '「' to '」',
        '『' to '』',
        '‘' to '’',
        '"' to '"'
    )

    /** 说/道类动词（含修饰），用于抽取说话人 */
    private val speechVerbs = listOf(
        "低声道", "沉声道", "冷声道", "怒道", "吼道", "喊道", "叫道", "喝道",
        "笑道", "冷笑道", "苦笑道", "微微笑道", "叹道", "哭道", "哽咽道",
        "颤声道", "轻声道", "柔声道", "厉声道", "高声道",
        "问道", "答道", "回道", "说道", "道",
        "说", "问", "答", "喊", "叫", "吼", "喝", "笑", "叹", "哭",
        "低语", "呢喃", "嘟囔", "嚷嚷",
        // 心理描写与应答类提示语
        "心想", "暗想", "心道", "暗道", "寻思", "想",
        "反问", "追问", "接口", "搭话", "回话", "嘀咕", "咕哝"
    ).sortedByDescending { it.length }

    private val genderFemaleHints = listOf(
        "她", "她们", "女子", "女人", "少女", "姑娘", "小姐", "夫人", "妈妈", "母亲",
        "姐姐", "妹妹", "阿姨", "奶奶", "姥姥", "女", "姐", "妹", "娘",
        "秘书", "丫鬟", "丫环", "婢女", "护士", "公主", "妇人", "女孩"
    )

    private val genderMaleHints = listOf(
        "他", "他们", "男子", "男人", "少年", "公子", "少爷", "先生", "爸爸", "父亲",
        "哥哥", "弟弟", "叔叔", "爷爷", "姥爷", "男", "哥", "弟", "父",
        "大汉", "汉子", "老者", "老翁", "书生", "男孩"
    )

    private val angerHints = listOf("怒", "吼", "喝", "骂", "咆哮", "暴怒", "咬牙")
    private val joyHints = listOf("笑", "欢", "喜", "乐", "嘻嘻", "哈哈")
    private val sadHints = listOf("哭", "泣", "哽咽", "呜咽", "叹", "悲", "泪")
    private val fearHints = listOf("颤", "抖", "惊", "恐", "骇", "哆嗦", "尖叫")
    private val surpriseHints = listOf("惊", "讶", "愕", "咦", "啊", "竟")

    /**
     * @param carryLast 上一段最后一句对白的说话人（跨段对话轮替用）
     * @param carryPrev 上上一句对白的说话人
     * @param prevEndedWithQuote 上一段是否以对白结尾（是→本段开头的无提示对白按轮替处理；
     *                           否→中间隔了旁白，同角继续说的概率更高，延续上一说话人）
     */
    fun analyze(
        text: String,
        carryLast: String? = null,
        carryPrev: String? = null,
        prevEndedWithQuote: Boolean = false
    ): List<Utterance> {
        val normalized = normalize(text)
        if (normalized.isBlank()) return emptyList()

        val quotes = findQuoteSpans(normalized)
        if (quotes.isEmpty()) {
            return listOf(narratorUtterance(normalized))
        }

        val result = mutableListOf<Utterance>()
        var cursor = 0
        var lastSpeaker = Utterance.SPEAKER_NARRATOR
        var lastGender = Gender.UNKNOWN
        // 本段内最近两句对白的说话人，用于无提示对白的轮替猜测
        var prevSpeaker: String? = null

        for (span in quotes) {
            if (span.start > cursor) {
                val before = normalized.substring(cursor, span.start).trim()
                if (before.isNotEmpty()) {
                    // 引号前的旁白/提示语：保留旁白正文，提示语本身通常不朗读或并入旁白
                    val cleaned = stripSpeechAttribution(before)
                    if (cleaned.isNotEmpty()) {
                        result.add(narratorUtterance(cleaned))
                    }
                }
            }

            val inner = normalized.substring(span.innerStart, span.innerEnd).trim()
            if (inner.isNotEmpty()) {
                // 归因只看「上一引号结束到本引号开始」之间的文本，避免错配前面引语的提示语
                val attributionWindow = normalized.substring(cursor, span.start)
                val beforeQuote = beforeQuoteWindow(normalized, span)
                val afterEnd = (span.end + 40).coerceAtMost(normalized.length)
                val emotionWindow = beforeQuote + inner + normalized.substring(span.end, afterEnd)
                val attributed = resolveSpeaker(attributionWindow)
                val speaker = attributed ?: guessAlternatingSpeaker(
                    lastSpeaker, prevSpeaker, carryLast, carryPrev, prevEndedWithQuote
                )
                var gender = resolveGender(attributionWindow, speaker, lastGender)
                // 名字未抽出时，用紧邻提示语的他/她纠正性别（他皱眉道 / 她叹道）
                if (speaker == lastSpeaker && speaker != Utterance.SPEAKER_NARRATOR) {
                    val recent = attributionWindow.takeLast(12)
                    when {
                        recent.contains("她") -> gender = Gender.FEMALE
                        recent.contains("他") && !recent.contains("她") -> gender = Gender.MALE
                    }
                }
                val emotion = resolveEmotion(emotionWindow, inner)
                result.add(
                    Utterance(
                        text = inner,
                        isQuote = true,
                        speaker = speaker,
                        gender = gender,
                        emotion = emotion.first,
                        intensity = emotion.second
                    )
                )
                if (speaker != Utterance.SPEAKER_NARRATOR) {
                    prevSpeaker = lastSpeaker
                    lastSpeaker = speaker
                    lastGender = gender
                }
            }
            cursor = span.end
        }

        if (cursor < normalized.length) {
            val tail = normalized.substring(cursor).trim()
            if (tail.isNotEmpty()) {
                val cleaned = stripSpeechAttribution(tail)
                if (cleaned.isNotEmpty()) {
                    result.add(narratorUtterance(cleaned))
                }
            }
        }

        // 合并过短旁白碎片，避免 ZipVoice 频繁切换参考音频
        return mergeShortNarration(result)
    }

    /**
     * 无提示对白的轮替猜测：
     * - 段内 A→B 后的下句猜 A；两人对话来回切换
     * - 无轮替信息时延续上一说话人（同角分段说完一句话的常见形态）
     * - 跨段：上一段以对白结尾 → 轮替；隔了旁白 → 延续
     * - 完全未知才回退旁白
     */
    private fun guessAlternatingSpeaker(
        lastSpeaker: String,
        prevSpeaker: String?,
        carryLast: String?,
        carryPrev: String?,
        prevEndedWithQuote: Boolean
    ): String {
        if (lastSpeaker != Utterance.SPEAKER_NARRATOR) {
            val other = prevSpeaker
            return if (!other.isNullOrEmpty() &&
                other != Utterance.SPEAKER_NARRATOR && other != lastSpeaker
            ) {
                other
            } else {
                lastSpeaker
            }
        }
        if (!carryLast.isNullOrEmpty() && !carryPrev.isNullOrEmpty() && carryLast != carryPrev) {
            return carryPrev
        }
        if (!carryLast.isNullOrEmpty() && !prevEndedWithQuote) {
            return carryLast
        }
        return Utterance.SPEAKER_NARRATOR
    }

    private fun normalize(raw: String): String {
        return raw
            .replace('\r', '\n')
            .replace(Regex("[\\t　]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun findQuoteSpans(text: String): List<QuoteSpan> {
        val spans = mutableListOf<QuoteSpan>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val close = pairQuotes.firstOrNull { it.first == ch }?.second
            if (close != null) {
                // 双引号 " 需要成对处理；若 close==open，向后找下一个
                val openIdx = i
                var j = i + 1
                var closed = false
                while (j < text.length) {
                    if (text[j] == close) {
                        if (openIdx + 1 < j) {
                            spans.add(QuoteSpan(openIdx, j + 1, openIdx + 1, j))
                        }
                        i = j + 1
                        closed = true
                        break
                    }
                    // 避免跨段过长
                    if (j - openIdx > 500) break
                    j++
                }
                if (!closed) i++
            } else {
                i++
            }
        }
        return spans
    }

    /**
     * 引号前窗口：用于说话人抽取（截到开引号前）
     */
    private fun beforeQuoteWindow(text: String, span: QuoteSpan): String {
        val start = (span.start - 60).coerceAtLeast(0)
        return text.substring(start, span.start)
    }

    /**
     * 从引号前提示语解析说话人；无法确定（无动词/无人名/无代词）返回 null，
     * 由调用方走对话轮替猜测。
     */
    private fun resolveSpeaker(beforeQuote: String): String? {
        // 在引号前定位「最长」说/道动词，避免「低声道」被「道」截断
        val verb = findLastSpeechVerb(beforeQuote) ?: return null
        val prefix = beforeQuote.substring(0, verb.first)
        extractSubjectName(prefix)?.let { return it }
        // 裸代词主语（她心想：/他说道：）：用代词本身当说话人，让性别路由生效
        val clause = lastClause(prefix)
        return when {
            clause.startsWith("她") -> "她"
            clause.startsWith("他") -> "他"
            clause.startsWith("两人") || clause.startsWith("众人") -> "众人"
            else -> null
        }
    }

    /**
     * 返回 (start, length)：优先「结束位置更靠后」，同终点优先更长动词
     * （避免「低声道」被末尾的单字「道」覆盖）
     */
    private fun findLastSpeechVerb(text: String): Pair<Int, Int>? {
        var bestStart = -1
        var bestLen = 0
        var bestEnd = -1
        for (i in text.indices) {
            for (verb in speechVerbs) {
                if (i + verb.length <= text.length && text.regionMatches(i, verb, 0, verb.length)) {
                    val end = i + verb.length
                    if (end > bestEnd || (end == bestEnd && verb.length > bestLen)) {
                        bestStart = i
                        bestLen = verb.length
                        bestEnd = end
                    }
                    break
                }
            }
        }
        return if (bestStart >= 0) bestStart to bestLen else null
    }

    /**
     * 从提示语前缀提取主语人名：取最后一小句的开头 2~4 字
     */
    private fun extractSubjectName(prefix: String): String? {
        var clause = lastClause(prefix)
        if (clause.isEmpty()) return null

        // 去掉句首代词/发语词
        clause = clause.removePrefix("只见").removePrefix("这时").removePrefix("此时")
            .removePrefix("随后").removePrefix("接着").removePrefix("然后")
            .removePrefix("他").removePrefix("她").removePrefix("它")
        if (clause.isEmpty()) return null

        for (len in intArrayOf(2, 3, 4)) {
            if (clause.length < len) continue
            val name = clause.substring(0, len)
            if (isLikelyPersonName(name)) return name
        }
        return null
    }

    /**
     * 提示语前缀的最后一小句（最后一个逗号/句读之后，去掉引号与冒号）
     */
    private fun lastClause(prefix: String): String {
        var clause = prefix.trimEnd('，', '。', '！', '？', '：', ':', '、', '；', ';', ' ', '\n', '\t')
        if (clause.isEmpty()) return ""

        val cut = clause.lastIndexOfAny(charArrayOf('，', '。', '！', '？', '、', '；', ';', '\n'))
        if (cut >= 0 && cut < clause.length - 1) {
            clause = clause.substring(cut + 1)
        }
        return clause.trim()
            .trimStart('“', '”', '「', '」', '『', '』', '"', '\'', '‘', '’', ' ', '：', ':')
            .trim()
    }

    private fun isLikelyPersonName(token: String): Boolean {
        if (token.length !in 2..4) return false
        val blacklist = listOf(
            "剑柄", "长剑", "手中", "身后", "面前", "心里", "脸上", "眼中", "身上",
            "沉默", "知道", "必须", "危险", "没有", "可以", "这个", "那个", "什么",
            "一起", "出来", "起来", "过去", "过来", "下来", "上去", "皱眉", "低头",
            "抬头", "摇头", "点头", "握紧", "皱着", "叹气", "叹道", "笑道", "说道",
            "听完", "说完", "看完", "想完", "回到", "走进", "拉出", "翻出"
        )
        if (blacklist.any { token.contains(it) }) return false
        if (token.any { it == '说' || it == '道' || it == '喊' || it == '吼' }) return false
        // 首字不应是助词/连词/引号/趋向动词/副词（听/走/不/很…多为动作或修饰短语开头）
        if (token[0] in charArrayOf(
                '的', '了', '着', '就', '也', '都', '还', '又', '再', '而', '“', '”', '「', '』',
                '听', '走', '站', '立', '坐', '回', '进', '刚', '翻', '拉',
                '不', '很', '太', '真', '更', '最', '挺', '稍', '略'
            )
        ) {
            return false
        }
        // 含完成体/持续体标记的多是动作短语（听完电话 / 指着铠甲）
        if (token.contains('完') || token.contains('着')) return false
        return true
    }

    private fun resolveGender(beforeQuote: String, speaker: String, last: Gender): Gender {
        // 代词主语性别唯一确定
        if (speaker == "她") return Gender.FEMALE
        if (speaker == "他") return Gender.MALE
        if (speaker == Utterance.SPEAKER_NARRATOR) return Gender.UNKNOWN
        // 人名自带性别字
        if (speaker.contains("女") || speaker.contains("娘") || speaker.contains("姐") ||
            speaker.contains("妹") || speaker.contains("妈") || speaker.contains("奶")
        ) return Gender.FEMALE
        if (speaker.contains("男") || speaker.contains("哥") || speaker.contains("弟") ||
            speaker.contains("爷") || speaker.contains("叔") || speaker.contains("父")
        ) return Gender.MALE

        // 只看引号前提示语，避免被后文代词污染
        val near = beforeQuote.takeLast(40)
        val femaleScore = genderFemaleHints.count { near.contains(it) }
        val maleScore = genderMaleHints.count { near.contains(it) }
        return when {
            femaleScore > maleScore -> Gender.FEMALE
            maleScore > femaleScore -> Gender.MALE
            last != Gender.UNKNOWN -> last
            else -> Gender.UNKNOWN
        }
    }

    private fun resolveEmotion(window: String, inner: String): Pair<EmotionTag, Float> {
        val src = window + inner
        val score = mutableMapOf(
            EmotionTag.ANGER to 0f,
            EmotionTag.JOY to 0f,
            EmotionTag.SADNESS to 0f,
            EmotionTag.FEAR to 0f,
            EmotionTag.SURPRISE to 0f
        )
        fun bump(tag: EmotionTag, delta: Float) {
            score[tag] = (score[tag] ?: 0f) + delta
        }
        angerHints.forEach { if (src.contains(it)) bump(EmotionTag.ANGER, 1f) }
        joyHints.forEach { if (src.contains(it)) bump(EmotionTag.JOY, 1f) }
        sadHints.forEach { if (src.contains(it)) bump(EmotionTag.SADNESS, 1f) }
        fearHints.forEach { if (src.contains(it)) bump(EmotionTag.FEAR, 1f) }
        surpriseHints.forEach { if (src.contains(it)) bump(EmotionTag.SURPRISE, 0.5f) }

        if (inner.endsWith("！") || inner.endsWith("!")) {
            bump(EmotionTag.ANGER, 0.5f)
            bump(EmotionTag.SURPRISE, 0.3f)
        }
        if (inner.endsWith("？") || inner.endsWith("?")) {
            bump(EmotionTag.SURPRISE, 0.3f)
        }
        if (inner.contains("……") || inner.contains("...")) {
            bump(EmotionTag.SADNESS, 0.4f)
        }

        val best = score.maxByOrNull { it.value } ?: return EmotionTag.CALM to 0f
        if (best.value <= 0f) return EmotionTag.CALM to 0f
        val intensity = (best.value / 3f).coerceIn(0.2f, 1f)
        return best.key to intensity
    }

    /** 整段都是「主语+说/道动词+冒号」的纯提示语，如「林风低声道：」；主语限 6 字防误吞长节拍 */
    private val pureAttributionHead = Regex(
        "^[\\u4e00-\\u9fa5A-Za-z0-9]{1,6}(?:${speechVerbs.joinToString("|")})[：:]\\s*$"
    )

    /** 引号后短提示尾允许的动词：排除单字 笑/叹/哭/想 等可重叠的动作词（笑了笑/想了想） */
    private val tailStripVerbs = speechVerbs.filter {
        it.length >= 2 || it in setOf("说", "道", "问", "答", "喊", "叫", "吼", "喝")
    }

    /** 引号后紧跟的短提示尾，如「他说。」「林风问道！」 */
    private val pureAttributionTail = Regex(
        "^[\\u4e00-\\u9fa5A-Za-z0-9]{0,3}(?:${tailStripVerbs.joinToString("|")})[。！？!?…\\s]*$"
    )

    private fun stripSpeechAttribution(segment: String): String {
        // 只剥离「整段就是提示语」的情况；部分匹配会吞掉正常旁白文字
        // （如「她心里叹息一声又想：」里的「她心里叹」曾被误删）
        val s = segment.trim()
        if (pureAttributionHead.matches(s)) return ""
        if (pureAttributionTail.matches(s)) return ""
        return s
    }

    private fun narratorUtterance(text: String): Utterance {
        return Utterance(
            text = text,
            isQuote = false,
            speaker = Utterance.SPEAKER_NARRATOR,
            gender = Gender.UNKNOWN,
            emotion = EmotionTag.CALM,
            intensity = 0f
        )
    }

    /**
     * 将连续过短的旁白合并，减少引擎换音色次数
     */
    private fun mergeShortNarration(list: List<Utterance>, minLen: Int = 8): List<Utterance> {
        if (list.size <= 1) return list
        val out = mutableListOf<Utterance>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(narratorUtterance(buf.toString().trim()))
                buf.setLength(0)
            }
        }
        for (u in list) {
            if (!u.isQuote && u.speaker == Utterance.SPEAKER_NARRATOR) {
                if (buf.isEmpty()) buf.append(u.text)
                else buf.append(u.text)
                if (buf.length >= minLen) flush()
            } else {
                flush()
                out.add(u)
            }
        }
        flush()
        return out
    }
}
