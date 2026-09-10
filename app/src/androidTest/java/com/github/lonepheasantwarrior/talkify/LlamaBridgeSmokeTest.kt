package com.github.lonepheasantwarrior.talkify

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.lonepheasantwarrior.talkify.llm.LlamaBridge
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * 手写 llama.cpp JNI 的真机冒烟测试。
 * 需要先把 GGUF 推到 files/models/qwen.gguf，否则 Assume 跳过（不判失败）。
 */
@RunWith(AndroidJUnit4::class)
class LlamaBridgeSmokeTest {

    private val tag = "LlamaSmoke"

    private fun modelFile(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // -e model qwen15.gguf 可切换模型，默认 0.5B
        val name = InstrumentationRegistry.getArguments().getString("model") ?: "qwen.gguf"
        // 真机 adb 非 root：内部私有目录要靠 run-as 塞，外部专属目录可直接 adb push，两个都找
        val candidates = listOf(
            File(ctx.filesDir, "models/$name"),
            File(ctx.getExternalFilesDir(null), "models/$name")
        )
        return candidates.firstOrNull { it.exists() } ?: candidates.first()
    }

    @Test
    fun nativeLibraryLoads() {
        assumeTrue("talkify_llm not built into this APK", LlamaBridge.ensureLoaded())
        Log.i(tag, "libtalkify_llm.so loaded ok")
        assertTrue(LlamaBridge.isAvailable)
    }

    @Test
    fun generateStreamsChineseWithoutMojibake() {
        assumeTrue("talkify_llm not built", LlamaBridge.ensureLoaded())
        val mf = modelFile()
        assumeTrue("no model at ${mf.absolutePath}", mf.exists())

        val opened = LlamaBridge.open(mf.absolutePath, nCtx = 2048, nThreads = 4)
        assumeTrue("nativeInit returned null", opened != null)
        val session = opened!!

        try {
            val chunks = mutableListOf<String>()
            val latch = CountDownLatch(1)
            val prompt = "<|im_start|>system\n你是一个中文朗读助手。只输出答案，不要解释。<|im_end|>\n" +
                    "<|im_start|>user\n用一句话介绍你自己。<|im_end|>\n" +
                    "<|im_start|>assistant\n"

            val out = session.generate(
                prompt = prompt,
                maxTokens = 48,
                temperature = 0.3f,
                topP = 0.9f,
                onToken = LlamaBridge.TokenCallback { piece ->
                    chunks += piece
                    if (chunks.size == 1) latch.countDown()
                    true
                }
            )

            Log.i(tag, "callback chunks=${chunks.size} out=[$out]")
            assertTrue("no output produced", out.isNotBlank())
            // 每次回调都必须是完整 UTF-8：若 JNI 切碎多字节字符，这里会出现 U+FFFD
            assertTrue("mojibake (U+FFFD) in output: [$out]", !out.contains('\uFFFD'))
            println("LLM_OUTPUT>>>$out")
        } finally {
            session.close()
        }
    }

    /** 吞吐基准：强制长输出，量 tok/s。结果打到 logcat 的 LlamaSmoke。 */
    @Test
    fun benchmarkThroughput() {
        assumeTrue("talkify_llm not built", LlamaBridge.ensureLoaded())
        val mf = modelFile()
        assumeTrue("no model at ${mf.absolutePath}", mf.exists())

        for (threads in intArrayOf(2, 4, 6)) {
            val session = LlamaBridge.open(mf.absolutePath, nCtx = 2048, nThreads = threads) ?: continue
            try {
                // 预热一次，避免把首次 mmap 缺页算进吞吐
                session.generate("请数数。", maxTokens = 4, temperature = 0f)

                var tokens = 0
                val t0 = System.nanoTime()
                session.generate(
                    "<|im_start|>user\n请从1数到80，用逗号分隔。<|im_end|>\n<|im_start|>assistant\n",
                    maxTokens = 160,
                    temperature = 0f,
                    onToken = LlamaBridge.TokenCallback { tokens += 1; true }
                )
                val sec = (System.nanoTime() - t0) / 1e9
                Log.i(tag, "BENCH threads=$threads chunks=$tokens ${"%.1f".format(tokens / sec)} chunk/s in ${"%.2f".format(sec)}s")
            } finally {
                session.close()
            }
        }
    }

    /** 内存与上下文：确认 2048 ctx 能正常分配（真机上 KV cache 是主要内存增量）。 */
    @Test
    fun contextAllocatesAt2048() {
        assumeTrue("talkify_llm not built", LlamaBridge.ensureLoaded())
        val mf = modelFile()
        assumeTrue("no model at ${mf.absolutePath}", mf.exists())
        val s = LlamaBridge.open(mf.absolutePath, nCtx = 2048, nThreads = 4)
        assumeTrue("nativeInit returned null", s != null)
        s!!.close()
    }

    /**
     * 真正要验证的命题：本地小模型能不能顶替规则引擎做"角色/性别/情绪"抽取。
     * 用 3 段样例打分（期望说话人命中率），结果打进 logcat，便于横向比较 0.5B vs 1.5B。
     */
    @Test
    fun extractsSpeakerAsJson() {
        assumeTrue("talkify_llm not built", LlamaBridge.ensureLoaded())
        val mf = modelFile()
        assumeTrue("no model at ${mf.absolutePath}", mf.exists())

        val session = LlamaBridge.open(mf.absolutePath, nCtx = 2048, nThreads = 4)
        assumeTrue("nativeInit returned null", session != null)
        val s = session!!

        // 每项：原文 -> 必须出现的说话人名
        val cases = listOf(
            "裴钱咬了咬牙，低声道：“师父，我饿了。” 陈平安笑道：“前面就是小镇，忍一忍。”" to listOf("裴钱", "陈平安"),
            "宁姚皱眉道：“你不该来的。” 陈平安摇头道：“我非来不可。”" to listOf("宁姚", "陈平安"),
            "老剑条嗤笑道：“就凭你？” 少年挺直脊背道：“就凭我。”" to listOf("老剑条", "少年")
        )

        var hit = 0
        var total = 0
        // Qwen3 默认带思维链，会把 token 预算耗在思考上；-e nothink true 触发热切换
        val noThink = InstrumentationRegistry.getArguments().getString("nothink") == "true"
        // -e template gemma 切换对话模板（Gemma 用 <start_of_turn>，Qwen 用 ChatML）
        val tmpl = InstrumentationRegistry.getArguments().getString("template") ?: "chatml"
        val sys = "你是中文有声书助手。只输出 JSON 数组，不要任何解释。" +
                "每项格式：{\"speaker\":\"说话人\",\"gender\":\"male/female\",\"emotion\":\"CALM/JOY/ANGER/SADNESS/FEAR/SURPRISE\"}。" +
                "忽略旁白里的动作，只抽对话。"
        try {
            cases.forEachIndexed { i, (passage, expected) ->
                val prompt = if (tmpl == "gemma") {
                    "<start_of_turn>user\n$sys\n\n$passage<end_of_turn>\n<start_of_turn>model\n"
                } else {
                    "<|im_start|>system\n$sys<|im_end|>\n" +
                            "<|im_start|>user\n$passage<|im_end|>\n" +
                            if (noThink) "<|im_start|>assistant\n thinking\n\n<｜end▁of▁thinking｜>\n\n" else "<|im_start|>assistant\n"
                }

                val out = s.generate(prompt, maxTokens = 260, temperature = 0.1f, topP = 0.9f)
                val found = expected.filter { out.contains(it) }
                hit += found.size
                total += expected.size
                Log.i(tag, "EVAL case#$i tmpl=$tmpl nothink=$noThink found=${found.size}/${expected.size} raw=[${out.replace("\n", "\\n")}]")
            }
            val score = hit * 100 / total
            Log.i(tag, "EVAL RESULT model=${mf.name} tmpl=$tmpl nothink=$noThink score=$score% ($hit/$total)")
            println("EXTRACT_SCORE>>>${mf.name}=$score%")
            // 只要求"确实具备抽取能力"，具体精度用于横向比较，不在此处判死
            assertTrue("model extracted nothing at all", hit > 0)
        } finally {
            s.close()
        }
    }

    /**
     * 对比实验：同一个 1.5B，换"逐句、不许漏"的强提示 + 输出项数提示，
     * 看之前 66% 到底是模型上限，还是提示词没写对。
     */
    @Test
    fun extractsSpeakerStrongPrompt() {
        assumeTrue("talkify_llm not built", LlamaBridge.ensureLoaded())
        val mf = modelFile()
        assumeTrue("no model at ${mf.absolutePath}", mf.exists())
        val s = LlamaBridge.open(mf.absolutePath, nCtx = 2048, nThreads = 4)
        assumeTrue("nativeInit returned null", s != null)
        val session = s!!

        val cases = listOf(
            "裴钱咬了咬牙，低声道：“师父，我饿了。” 陈平安笑道：“前面就是小镇，忍一忍。”" to listOf("裴钱", "陈平安"),
            "宁姚皱眉道：“你不该来的。” 陈平安摇头道：“我非来不可。”" to listOf("宁姚", "陈平安"),
            "老剑条嗤笑道：“就凭你？” 少年挺直脊背道：“就凭我。”" to listOf("老剑条", "少年")
        )

        var hit = 0; var total = 0
        try {
            cases.forEachIndexed { i, (passage, expected) ->
                val n = passage.count { it == '“' }
                val prompt = "<|im_start|>system\n" +
                        "任务：从中文小说片段里逐条抽取对话。规则：\n" +
                        "1) 每一对引号内的对话都必须单独输出一项，不允许合并或遗漏；\n" +
                        "2) 本段共有 $n 处对话，输出数组长度必须等于 $n；\n" +
                        "3) speaker 用引号前紧邻的说话人姓名；\n" +
                        "4) emotion 从 CALM/JOY/ANGER/SADNESS/FEAR/SURPRISE 里选，依据提示语判断。\n" +
                        "只输出 JSON 数组，不要解释。\n<|im_end|>\n" +
                        "<|im_start|>user\n$passage<|im_end|>\n" +
                        "<|im_start|>assistant\n[\n"

                val out = "[" + session.generate(prompt, maxTokens = 240, temperature = 0.1f, topP = 0.9f)
                val found = expected.filter { out.contains(it) }
                hit += found.size; total += expected.size
                Log.i(tag, "EVAL2 case#$i n=$n found=${found.size}/${expected.size} raw=[$out]")
            }
            val score = hit * 100 / total
            Log.i(tag, "EVAL2 RESULT model=${mf.name} score=$score% ($hit/$total)")
            println("EXTRACT2_SCORE>>>${mf.name}=$score%")
            assertTrue(hit > 0)
        } finally {
            session.close()
        }
    }

    /** 加载后读 PSS，估真机内存占用（模型 mmap + KV cache）。 */    @Test
    fun reportMemoryFootprint() {
        assumeTrue("talkify_llm not built", LlamaBridge.ensureLoaded())
        val mf = modelFile()
        assumeTrue("no model at ${mf.absolutePath}", mf.exists())

        val before = readPssKb()
        val session = LlamaBridge.open(mf.absolutePath, nCtx = 2048, nThreads = 4)
        assumeTrue("nativeInit returned null", session != null)
        val s = session!!
        try {
            // 触发一次推理，让权重页真正被访问
            s.generate("你好", maxTokens = 8, temperature = 0f)
            val after = readPssKb()
            Log.i(tag, "MEM pss_before=${before}kB pss_after=${after}kB delta=${after - before}kB")
        } finally {
            s.close()
        }
    }

    /**
     * 探测手机自带 AI 能力。分三类，结果全部打进 logcat 的 LlamaSmoke：
     *  1) 标准 Android On-Device Intelligence（有没有 provider 实现）
     *  2) OPPO AIUnit 服务能否被第三方 App 绑定
     *  3) OPPO/系统自带的语音或文本服务清单
     */
    @Test
    fun probeBuiltInAi() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = ctx.packageManager

        // 1) 标准 ODI API：是否有人实现了 OnDeviceIntelligenceService
        val odiIntent = android.content.Intent("android.app.ondeviceintelligence.OnDeviceIntelligenceService")
        val odi = pm.queryIntentServices(odiIntent, android.content.pm.PackageManager.MATCH_ALL)
        Log.i(tag, "PROBE ODI providers=${odi.size} ${odi.joinToString { it.serviceInfo.packageName }}")

        // 2) OPPO AIUnit：尝试绑定，看是成功还是被权限挡住
        val unitIntent = android.content.Intent("oplus.intent.action.AIUNIT_SERVICE")
        val unitServices = pm.queryIntentServices(unitIntent, android.content.pm.PackageManager.MATCH_ALL)
        Log.i(tag, "PROBE AIUnit services=${unitServices.size} " +
                unitServices.joinToString { "${it.serviceInfo.packageName}/${it.serviceInfo.name} exported=${it.serviceInfo.exported}" })

        if (unitServices.isNotEmpty()) {
            val si = unitServices[0].serviceInfo
            val bind = android.content.Intent().setComponent(android.content.ComponentName(si.packageName, si.name))
            val latch = java.util.concurrent.CountDownLatch(1)
            val conn = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                    Log.i(tag, "PROBE AIUnit BIND OK: ${name} binder=${binder}")
                    latch.countDown()
                }
                override fun onServiceDisconnected(name: android.content.ComponentName?) {}
            }
            try {
                val ok = ctx.bindService(bind, conn, android.content.Context.BIND_AUTO_CREATE)
                Log.i(tag, "PROBE AIUnit bindService returned=$ok")
                if (ok) {
                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    ctx.unbindService(conn)
                }
            } catch (t: Throwable) {
                Log.e(tag, "PROBE AIUnit bind threw: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        // 2b) 绕开包可见性：按组件名直接绑定，区分「未导出」与「查询被过滤」
        listOf(
            "com.oplus.aiunit" to "com.oplus.aiunit.core.AIUnitService",
            "com.oplus.aiunit" to "com.oplus.aiunit.download.service.AIUnitDownloadService",
            "com.heytap.speechassist" to null
        ).forEach { (pkg, cls) ->
            val name = if (cls != null) android.content.ComponentName(pkg, cls)
            else android.content.ComponentName(pkg, pkg + ".speech.SpeechAssistService")
            val latch = java.util.concurrent.CountDownLatch(1)
            val conn = object : android.content.ServiceConnection {
                override fun onServiceConnected(n: android.content.ComponentName?, b: android.os.IBinder?) {
                    Log.i(tag, "PROBE direct BIND OK: $n"); latch.countDown()
                }
                override fun onServiceDisconnected(n: android.content.ComponentName?) {}
            }
            val ok = try {
                ctx.bindService(android.content.Intent().setComponent(name), conn, android.content.Context.BIND_AUTO_CREATE)
            } catch (t: Throwable) {
                Log.e(tag, "PROBE direct bind $name threw: ${t.javaClass.simpleName}: ${t.message}"); false
            }
            Log.i(tag, "PROBE direct bind $name returned=$ok")
            if (ok) { latch.await(4, java.util.concurrent.TimeUnit.SECONDS); try { ctx.unbindService(conn) } catch (_: Throwable) {} }
        }

        // 3) 带 ASR / TTS / LLM 关键字的服务
        listOf(
            "android.speech.RecognitionService",
            "android.speech.tts.TextToSpeechService",
            "android.service.textclassifier.TextClassifierService",
            "com.oplus.aiunit.AIUNIT_SERVICE"
        ).forEach { action ->
            val r = try { pm.queryIntentServices(android.content.Intent(action), android.content.pm.PackageManager.MATCH_ALL) } catch (t: Throwable) { emptyList() }
            Log.i(tag, "PROBE action=$action count=${r.size} ${r.joinToString { it.serviceInfo.packageName }}")
        }
        assertTrue(true)
    }

    private fun readPssKb(): Int {
        return try {
            File("/proc/self/smaps_rollup").readLines()
                .firstOrNull { it.startsWith("Pss:") }
                ?.filter { it.isDigit() }?.toInt() ?: -1
        } catch (t: Throwable) {
            -1
        }
    }
}
