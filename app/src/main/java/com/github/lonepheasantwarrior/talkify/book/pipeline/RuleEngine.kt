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
                // 强调/拟声引号（招式名"万花劫"、象声词"嗡嗡"）不是说话，并回旁白正文
                if (isEmbeddedEmphasis(normalized, span, attributionWindow)) {
                    // 以旁白身份加回，mergeShortNarration 会把它与前后旁白拼回原句
                    result.add(narratorUtterance(inner))
                    cursor = span.end
                    continue
                }
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
     * 强调/拟声引号判定（网文里引号还用于招式名、象声词，如
     * 「满天花雨"万花劫"动了」「发出轻轻的"嗡嗡"声」）
     *
     * 三条同时满足才判重点（宁漏勿误，别把真对白读成旁白）：
     * 1. 开引号嵌在句中：前一字符是汉字/字母/数字（真对白前通常是冒号/标点/行首）
     * 2. 引号前 20 字内无说/道动词（有动词说明是省略冒号的对白，如 轻声道"好"）
     * 3. 引号内短（≤8字）且无句末标点（"走吧。"这种有标点的仍是对白）
     */
    private fun isEmbeddedEmphasis(text: String, span: QuoteSpan, attributionWindow: String): Boolean {
        if (span.start == 0) return false
        val prev = text[span.start - 1]
        val prevContinuesSentence = prev.code in 0x4E00..0x9FFF || prev.isLetterOrDigit()
        if (!prevContinuesSentence) return false
        if (findLastSpeechVerb(attributionWindow.takeLast(20)) != null) return false
        val inner = text.substring(span.innerStart, span.innerEnd).trim()
        if (inner.isEmpty() || inner.length > 8) return false
        val lastChar = inner.last()
        return lastChar !in "！？。，；：…!?,.;:"
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
     * 从提示语前缀抽取主语人名。
     *
     * 顺序：整小句即名（≤4字，如「秘书小玉」）→ 长小句取尾3/尾2
     * （「落魄山陈平安」→陈平安）→ 前一小句开头（「少女快步走来，脆声喊道」→少女）。
     * 每个候选都过 [validateName] 校验；整句校验失败再试句首前缀
     * （「林风皱眉道」→林风）。
     */
    private fun extractSubjectName(prefix: String): String? {
        val clause = lastClause(prefix)
        if (clause.isEmpty()) return null

        if (clause.length <= 4) {
            validateName(clause)?.let { return it }
            for (len in intArrayOf(3, 2)) {
                if (len >= clause.length) break
                validateName(clause.take(len))?.let { return it }
            }
        } else {
            // 尾切优先（名字紧邻动词），头切兜底（名字在句首、后接状语）
            validateName(clause.takeLast(3))?.let { return it }
            validateName(clause.takeLast(2))?.let { return it }
            validateName(clause.take(3))?.let { return it }
            validateName(clause.take(2))?.let { return it }
        }
        return prevClauseHead(prefix)
    }

    /**
     * 前一小句的句首主语：「少女快步走来，脆声喊道：」→「少女」
     */
    private fun prevClauseHead(prefix: String): String? {
        val trimmed = prefix.trimEnd('，', '。', '！', '？', '：', ':', '、', '；', ';', ' ', '\n', '\t')
        val cut = trimmed.lastIndexOfAny(charArrayOf('，', '。', '！', '？', '、', '；', ';', '\n'))
        if (cut <= 0) return null
        val seg = trimmed.substring(0, cut)
        val prevCut = seg.lastIndexOfAny(charArrayOf('，', '。', '！', '？', '、', '；', ';', '\n'))
        var s = (if (prevCut >= 0) seg.substring(prevCut + 1) else seg).trim()
            .trimStart('“', '”', '「', '」', '『', '』', '"', '\'', '‘', '’', '：', ':')
            .trim()
        s = s.removePrefix("只见").removePrefix("这时").removePrefix("此时")
            .removePrefix("随后").removePrefix("接着").removePrefix("然后")
            .removePrefix("他").removePrefix("她").removePrefix("它")
        if (s.isEmpty()) return null
        for (len in intArrayOf(3, 2, 4)) {
            if (s.length < len) break
            validateName(s.take(len))?.let { return it }
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

    /** 人名末字不能是这些（候选切进了状语/动词） */
    private val nameStopLastChars = charArrayOf(
        '突', '忽', '忙', '又', '再', '才', '便', '还', '轻', '缓', '冷', '苦', '微', '低',
        '沉', '厉', '颤', '怒', '叹', '笑', '说', '道', '喊', '叫', '问', '呼', '嘀', '喃',
        '嘟', '啧', '着', '了', '的', '地', '得', '声', '是', '快', '大', '无', '性', '起',
        '头', '言', '语', '气', '看', '望', '感', '惊', '愣', '立', '坐', '步', '吟', '嘲',
        '劝', '慰', '骂', '嚷', '至', '过', '住', '开', '手', '眼', '心',
        '摇', '皱', '抬', '垂', '俯', '仰', '瞪', '瞥', '瞄', '眨', '撇', '咧',
        '抿', '挥', '摆', '搂', '抱', '扯', '推', '拍', '敲', '指', '自',
        '叨', '恼', '没', '眉', '脸', '喃', '嘟'
    )

    /** 名字首字不能是这些（虚词/介词/副词，几乎不入名） */
    private val nameStopFirstChars = charArrayOf(
        '的', '了', '着', '就', '也', '都', '还', '又', '再', '而', '“', '”', '「', '』',
        '听', '走', '站', '立', '坐', '回', '进', '刚', '翻', '拉', '不', '很', '太', '真',
        '更', '最', '挺', '稍', '略', '以', '便', '才', '连', '被', '把', '向', '往', '从',
        '即', '是', '则', '却', '竟', '乃', '这', '那', '其', '之', '与', '和', '同', '跟',
        '没', '在', '于', '给', '让', '使', '虽', '但', '只', '未', '别', '然'
    )

    /** 候选含这些字多为拟声/语气词，不是人名 */
    private val nameStopAnyChars = charArrayOf(
        '哈', '呵', '嘻', '啦', '哟', '嘿', '哦', '喔', '咦', '唉', '呀', '呗', '咯',
        '噢', '嗡', '咔', '啪', '咚', '哝', '嗤', '呜', '哼'
    )

    private val nameBlacklist = listOf(
        "剑柄", "长剑", "手中", "身后", "面前", "心里", "脸上", "眼中", "身上",
        "沉默", "知道", "必须", "危险", "没有", "可以", "这个", "那个", "什么",
        "一起", "出来", "起来", "过去", "过来", "下来", "上去", "皱眉", "低头",
        "抬头", "摇头", "点头", "握紧", "皱着", "叹气", "叹道", "笑道", "说道",
        "听完", "说完", "看完", "想完", "回到", "走进", "拉出", "翻出",
        // 联调与《剑来》全书扫描发现的状语/动作/神态短语
        "轻声", "小声", "大声", "低声", "沉声", "厉声", "柔声", "颤声", "高声",
        "随口", "顺口", "开口", "转头", "回头", "好奇", "年轻", "年迈", "只是",
        "顿时", "立刻", "随即", "马上", "连忙", "急忙", "悄悄", "默默", "缓缓",
        "轻轻", "渐渐", "慢慢", "喃喃", "啧啧", "笑眯", "面色", "脸色", "神情",
        "眼神", "语气", "口吻", "模样", "样子", "忽然", "依然", "仍然", "依旧",
        "似乎", "好像", "显然", "果然", "竟然", "居然", "几乎", "心中", "心底",
        "一旁", "身旁", "身边", "半晌", "良久", "许久", "片刻", "一时", "此时",
        "此刻", "眼前", "脑海", "胸口", "心口", "一下", "小心翼翼",
        "蹑手蹑脚", "恍恍惚惚", "隐隐约约", "清清楚楚", "一五一十",
        // 《剑来》第二轮扫描补充
        "突然", "无奈", "感慨", "疑惑", "犹豫", "迟疑", "沉吟", "认真", "严肃",
        "郑重", "敷衍", "苦笑", "大笑", "微笑", "冷笑", "轻笑", "失笑", "调侃",
        "揶揄", "打趣", "解释", "回答", "回应", "附和", "接话", "插话", "起身",
        "自言自语", "自顾自", "自嘲", "忍不住", "不由得", "不禁", "想了想",
        "琢磨", "考虑", "继续", "试探", "没好气", "气鼓鼓", "不耐烦",
        "不紧不慢", "似笑非笑", "一本正经", "半信半疑", "欲言又止",
        "意味深长", "语重心长", "郑重其事", "若有所思", "不动声色", "神兽"
    )

    /**
     * 候选是否像人名：长度/首末字/黑名单/叠字（喃喃、缓缓）多重校验
     */
    private fun validateName(token: String): String? {
        if (token.length !in 2..4) return null
        if (token.any { it == '说' || it == '道' || it == '喊' || it == '吼' }) return null
        if (token.first() in nameStopFirstChars) return null
        if (token.last() in nameStopLastChars) return null
        if (token.any { it in nameStopAnyChars }) return null
        if (token.contains('完') || token.contains('着')) return null
        if (token.length == 2 && token[0] == token[1]) return null
        // AABB 叠词（小心翼翼/隐隐约约/心翼翼…）与 ABB 尾叠词（怯生生/慢腾腾…）
        if (token.length == 4 && (token[0] == token[1] || token[2] == token[3])) return null
        if (token.length == 3 && token[1] == token[2]) return null
        if (nameBlacklist.any { token.contains(it) }) return null
        return token
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
     * 合并连续旁白，减少引擎换音色次数。
     * 旁白之间一律续写（"满天花雨"/"声。"这类被强调引号切碎的片段重新拼回），
     * 只有紧邻对白时才必须切段
     */
    private fun mergeShortNarration(list: List<Utterance>): List<Utterance> {
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
                buf.append(u.text)
            } else {
                flush()
                out.add(u)
            }
        }
        flush()
        return out
    }
}
