package com.github.lonepheasantwarrior.talkify.llm

import com.github.lonepheasantwarrior.talkify.book.model.EmotionTag
import com.github.lonepheasantwarrior.talkify.book.model.Gender
import com.github.lonepheasantwarrior.talkify.book.model.Utterance
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import org.json.JSONObject

/**
 * 用端上 LLM 校正对白分析结果
 *
 * **设计要点：LLM 不负责切句。**
 * 切句仍由 [com.github.lonepheasantwarrior.talkify.book.pipeline.RuleEngine] 完成
 * （它保证字符不丢，有重建覆盖测试兜底）。LLM 只拿到「已切好的对白列表」，
 * 按序号回填说话人 / 性别 / 情绪三项。这样做的原因：
 *
 * 1. **零吞字风险**：输出不再包含正文，模型没有机会漏抄或改写原文；
 * 2. **解析稳定**：小模型复述长文本时容易截断/复读，改成枚举序号后
 *    输出短、可校验，个别条目缺失也不影响其它句；
 * 3. **可控降级**：任何环节失败（模型没下、超时、JSON 非法）都原样返回规则结果。
 *
 * 情绪判定是这次增强的主要收益：规则层靠关键词（"怒/吼/笑"），
 * LLM 能识别"嗤笑"→ANGER、"挺直脊背道"→JOY 这类需要语义的暗示。
 */
object LlmBookExtractor {

    private const val TAG = "LlmBookExtractor"

    /** 单次最多交给 LLM 判定的对白条数，防止超长段落拖慢听书 */
    private const val MAX_QUOTES = 24

    /**
     * 一条校正结果；字段为 null 表示模型没给或给的值无法识别，保持规则原值
     */
    data class Correction(
        val speaker: String? = null,
        val gender: Gender? = null,
        val emotion: EmotionTag? = null
    )

    /**
     * 构造提示词
     *
     * **关键**：说话人归属通常写在"对白前面的旁白"里（「裴钱咬了咬牙，低声道：」），
     * 只给对白正文模型无从判断。因此每句都附上紧邻的上文线索。
     * 只给最少必要信息，并要求"只输出 JSON 数组、条数一致"。
     */
    fun buildPrompt(utterances: List<Utterance>): String {
        // 逐句收集「上文线索 → 对白」，序号的递增只发生在这里，
        // 必须与 apply() 里对 quotes 的计数顺序完全一致
        val items = ArrayList<Pair<String, String>>()
        utterances.forEachIndexed { idx, u ->
            if (u.isQuote && u.speaker != Utterance.SPEAKER_NARRATOR) {
                // 只用"紧邻的旁白"作线索：说话人归属写在旁白里；
                // 前一句若也是对白，对判断说话人没有帮助，反而可能误导，故留空
                val prev = utterances.getOrNull(idx - 1)
                val ctx = if (prev != null && !prev.isQuote) prev.text.trim().takeLast(30) else ""
                items += ctx to u.text.trim()
            }
        }

        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append("你是中文小说对白分析助手。用户给出若干条对白，每条附有【上文】线索。")
        sb.append("请为每一条判断：speaker（说话人姓名）、gender（male/female）、")
        sb.append("emotion（CALM/JOY/ANGER/SADNESS/FEAR/SURPRISE）。\n")
        sb.append("规则：\n")
        sb.append("1) 只输出一个 JSON 数组，不要解释、不要 markdown 代码块；\n")
        sb.append("2) 数组必须正好 ").append(items.size).append(" 条，格式 ")
        sb.append("{\"i\":序号,\"speaker\":\"姓名\",\"gender\":\"male\",\"emotion\":\"CALM\"}；\n")
        sb.append("3) speaker 优先取【上文】里的人名（如「裴钱咬了咬牙，低声道：」→裴钱），")
        sb.append("上文没有名字时用「未知」；不要把动作词（咬牙、皱眉、笑道）当成人名；\n")
        sb.append("4) gender 不确定时按语境推断，尽量给 male 或 female；\n")
        sb.append("5) emotion 依据上文提示语判断：笑/欢呼→JOY，怒/吼/冷哼→ANGER，")
        sb.append("哭/叹/低哑→SADNESS，颤/惊惧→FEAR，惊讶/愣→SURPRISE，其余 CALM。\n")
        sb.append("<|im_end|>\n")
        sb.append("<|im_start|>user\n")
        items.forEachIndexed { i, (ctx, text) ->
            sb.append(i).append(". ")
            if (ctx.isNotBlank()) sb.append("【上文】").append(ctx).append(' ')
            sb.append("【对白】").append(text).append('\n')
        }
        sb.append("<|im_end|>\n<|im_start|>assistant\n")
        // Qwen3 默认开思维链，这里硬关掉，避免 token 全烧在思考上
        sb.append(" thinking\n\n<｜end▁of▁thinking｜>\n\n")
        return sb.toString()
    }

    /**
     * 解析模型输出为「序号 → 校正」
     *
     * **为什么不用 JSONArray**：小模型经常在数组元素之间漏掉逗号
     * （实测 `{...}\n{...}`），或把同一份答案在 `</think>` 之后回显一遍，
     * 这些都让严格的 JSON 解析器整体失败——而内容其实是对的。
     * 因此改为逐个「按花括号配平」扫描 JSON 对象，再单独解析：
     * 漏逗号、夹带文本、重复回显、顺序错乱都能容忍，坏掉的单条只丢自己。
     *
     * 冲突时保留首次出现的值（首次输出是模型的最终答案，回显在后）。
     */
    fun parse(raw: String): Map<Int, Correction> {
        if (raw.isBlank()) return emptyMap()

        val result = LinkedHashMap<Int, Correction>()
        for (obj in extractJsonObjects(raw)) {
            val idx = obj.optInt("i", obj.optInt("index", -1))
            if (idx < 0) continue
            val correction = Correction(
                speaker = obj.optString("speaker").trim().takeIf { it.isNotBlank() && it != "未知" },
                gender = parseGender(obj.optString("gender")),
                emotion = parseEmotion(obj.optString("emotion"))
            )
            if (!result.containsKey(idx)) result[idx] = correction
        }
        return result
    }

    /**
     * 按花括号配平从任意文本里抠出所有 JSON 对象
     *
     * 需要跳过字符串字面量内的花括号，因此单独跟踪引号与转义状态。
     */
    private fun extractJsonObjects(raw: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var i = 0
        while (i < raw.length) {
            if (raw[i] != '{') {
                i++
                continue
            }
            var depth = 0
            var inString = false
            var escaped = false
            var j = i
            var closed = false
            while (j < raw.length) {
                val c = raw[j]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                closed = true
                            }
                        }
                    }
                }
                if (closed) break
                j++
            }
            if (closed) {
                val candidate = raw.substring(i, j + 1)
                runCatching { JSONObject(candidate) }.getOrNull()?.let { out += it }
                i = j + 1
            } else {
                // 未闭合（多半是被超时截断），后续不可能再有完整对象
                break
            }
        }
        return out
    }

    private fun parseGender(s: String): Gender? = when (s.trim().lowercase()) {
        "male", "m", "男" -> Gender.MALE
        "female", "f", "女" -> Gender.FEMALE
        else -> null
    }

    private fun parseEmotion(s: String): EmotionTag? = when (s.trim().uppercase()) {
        "CALM" -> EmotionTag.CALM
        "JOY", "HAPPY" -> EmotionTag.JOY
        "ANGER", "ANGRY" -> EmotionTag.ANGER
        "SADNESS", "SAD" -> EmotionTag.SADNESS
        "FEAR", "AFRAID" -> EmotionTag.FEAR
        "SURPRISE", "SURPRISED" -> EmotionTag.SURPRISE
        else -> null
    }

    /**
     * 把校正结果套回 utterances
     *
     * 只改对白项，**文本与切分完全不动**；模型没给或给不出合法值的字段保持规则原值。
     *
     * 三个字段的信任级别不同：
     * - **speaker**：采纳模型结果——识别长尾假名（「咬牙」→「裴钱」）正是本次增强的主要收益；
     * - **emotion**：采纳模型结果——关键词法判不出反讽、隐忍等语义线索；
     * - **gender**：**仅当规则/跨段投票仍为 UNKNOWN 时才采纳**。性别有跨段累计多数派
     *   兜底，比单句推理稳；实测 0.6B 会把「陈平安」误判成 female，不能让它覆盖投票结果。
     */
    fun apply(utterances: List<Utterance>, corrections: Map<Int, Correction>): List<Utterance> {
        if (corrections.isEmpty()) return utterances
        var qIdx = -1
        return utterances.map { u ->
            if (!u.isQuote || u.speaker == Utterance.SPEAKER_NARRATOR) {
                u
            } else {
                qIdx++
                val c = corrections[qIdx] ?: return@map u
                u.copy(
                    speaker = c.speaker ?: u.speaker,
                    gender = if (u.gender == Gender.UNKNOWN) (c.gender ?: u.gender) else u.gender,
                    emotion = c.emotion ?: u.emotion
                )
            }
        }
    }

    /**
     * 对规则分析结果做 LLM 增强
     *
     * [generate] 注入推理实现，便于单测；入参为 (prompt, 期望对象数) → 模型原始输出。
     * 期望对象数用于让推理侧"收够即停"，避免小模型刷结束符拖长耗时。
     * 对白条数为 0 或过多时直接跳过，避免无谓推理。
     */
    fun enhance(
        utterances: List<Utterance>,
        generate: (String, Int) -> String
    ): List<Utterance> {
        val quotes = utterances.filter { it.isQuote && it.speaker != Utterance.SPEAKER_NARRATOR }
        if (quotes.isEmpty()) return utterances
        if (quotes.size > MAX_QUOTES) {
            TtsLogger.d("Too many quotes (${quotes.size}), skip LLM enhancement", tag = TAG)
            return utterances
        }

        val raw = try {
            generate(buildPrompt(utterances), quotes.size)
        } catch (e: Exception) {
            TtsLogger.w("LLM generate failed, keep rule result: ${e.message}", tag = TAG)
            return utterances
        }
        val corrections = parse(raw)
        if (corrections.isEmpty()) {
            TtsLogger.d("LLM returned no usable corrections", tag = TAG)
            return utterances
        }
        TtsLogger.i("LLM corrected ${corrections.size}/${quotes.size} quotes", tag = TAG)
        return apply(utterances, corrections)
    }
}
