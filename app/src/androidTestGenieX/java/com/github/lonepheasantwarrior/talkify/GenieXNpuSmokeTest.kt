package com.github.lonepheasantwarrior.talkify

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.RuntimeIdValue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 高通 GenieX SDK 真机验证：把同一套"角色抽取"任务分别跑在 NPU / CPU 上，
 * 对比速度与准确率。只在 Snapdragon 8 Elite 系机型 + -PspikeGenieX 构建下有意义。
 *
 * 模型用 LOCALFS 方式喂入（避免自动下载耗流量）：
 *   adb push Qwen3-0.6B-Q4_0.gguf 到 app 的 files/models/ 后，用 -e localmodel 指定文件名。
 */
@RunWith(AndroidJUnit4::class)
class GenieXNpuSmokeTest {

    private val tag = "GenieXNpu"

    private fun ctx() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun localModel(): File? {
        val name = InstrumentationRegistry.getArguments().getString("localmodel")
            ?: "Qwen3-0.6B-Q4_0.gguf"
        val f = File(ctx().filesDir, "models/$name")
        return if (f.exists()) f else null
    }

    /** 初始化 SDK 并打印插件版本，确认 NPU 运行时被加载。 */
    @Test
    fun sdkInitializesAndLoadsPlugins() {
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            GenieXSdk.getInstance().init(ctx(), object : GenieXSdk.InitCallback {
                override fun onSuccess() {
                    Log.i(tag, "SDK init onSuccess")
                    latch.countDown()
                }
                override fun onFailure(reason: String) {
                    Log.e(tag, "SDK init onFailure: $reason")
                    latch.countDown()
                }
            })
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        } catch (t: Throwable) {
            Log.e(tag, "SDK init threw: ${t.javaClass.simpleName}: ${t.message}")
        }
        listOf("llama_cpp", "qairt").forEach { id ->
            val v = try { GenieXSdk.getInstance().getPluginVersion(id) } catch (t: Throwable) { "ERR:${t.message}" }
            Log.i(tag, "PLUGIN $id version=$v")
        }
    }

    /**
     * 核心实验：同一提示词、同一模型，跑 [computeUnits] 指定的计算单元，
     * 记录 token 吞吐 + 抽取得分。
     */
    @Test
    fun extractOnComputeUnit() {
        val units = (InstrumentationRegistry.getArguments().getString("units") ?: "npu,cpu")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val model = localModel()
        assumeTrue("no local model; push Q4_0 gguf to files/models/", model != null)

        val latch = java.util.concurrent.CountDownLatch(1)
        GenieXSdk.getInstance().init(ctx(), object : GenieXSdk.InitCallback {
            override fun onSuccess() { latch.countDown() }
            override fun onFailure(reason: String) { Log.e(tag, "init fail: $reason"); latch.countDown() }
        })
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)

        // 用 LOCALFS 把本地文件"导入"成 SDK 可管理的模型
        val name = "local/qwen3-0.6b-q4_0"
        runBlocking {
            try {
                ModelManagerWrapper.pullFlow(
                    ModelPullInput(
                        model_name = name,
                        hub = HubSource.LOCALFS,
                        local_path = model!!.absolutePath,
                    )
                ).collect { ev -> Log.i(tag, "PULL ${ev.javaClass.simpleName}") }
            } catch (t: Throwable) {
                Log.e(tag, "pull(LOCALFS) failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        val paths = runBlocking { runCatching { ModelManagerWrapper.getPaths(name) }.getOrNull() }
        Log.i(tag, "getPaths=$paths")
        // LOCALFS 导入在部分版本上不认裸 gguf，此时直接把文件路径喂给 LlmCreateInput
        val modelPath = paths?.model_path ?: model!!.absolutePath
        val tokenizerPath = paths?.tokenizer_path
        Log.i(tag, "using modelPath=$modelPath")

        val cases = listOf(
            "裴钱咬了咬牙，低声道：“师父，我饿了。” 陈平安笑道：“前面就是小镇，忍一忍。”" to listOf("裴钱", "陈平安"),
            "宁姚皱眉道：“你不该来的。” 陈平安摇头道：“我非来不可。”" to listOf("宁姚", "陈平安"),
            "老剑条嗤笑道：“就凭你？” 少年挺直脊背道：“就凭我。”" to listOf("老剑条", "少年")
        )

        units.forEach { unit ->
            var llm: LlmWrapper? = null
            try {
                val created = runBlocking {
                    LlmWrapper.builder()
                        .llmCreateInput(
                            LlmCreateInput(
                                model_path = modelPath,
                                tokenizer_path = tokenizerPath,
                                config = ModelConfig(nCtx = 2048, max_tokens = 260, enable_thinking = false),
                                runtime_id = RuntimeIdValue.LLAMA_CPP.value,
                                compute_unit = unit,
                            )
                        )
                        .build()
                }
                llm = created.getOrNull()
                if (llm == null) {
                    Log.e(tag, "UNIT=$unit build failed: ${created.exceptionOrNull()?.message}")
                    return@forEach
                }

                var hit = 0; var total = 0; var tokens = 0
                val t0 = System.nanoTime()
                runBlocking {
                    cases.forEach { (passage, expected) ->
                        val msgs = arrayOf(
                            ChatMessage("user", "你是中文有声书助手。只输出 JSON 数组，不要任何解释。" +
                                    "每项：{\"speaker\":\"说话人\",\"gender\":\"male/female\"," +
                                    "\"emotion\":\"CALM/JOY/ANGER/SADNESS/FEAR/SURPRISE\"}。" +
                                    "忽略旁白动作，只抽对话。\n\n$passage")
                        )
                        val templ = llm!!.applyChatTemplate(msgs, null, true, false)
                            .getOrElse { Log.e(tag, "template err: ${it.message}"); return@runBlocking }
                        val sb = StringBuilder()
                        llm!!.generateStreamFlow(
                            templ.formattedText,
                            GenerationConfig(maxTokens = 260),
                        ).collect { r ->
                            when (r) {
                                is LlmStreamResult.Token -> { sb.append(r.text); tokens++ }
                                is LlmStreamResult.Completed -> {}
                                is LlmStreamResult.Error -> Log.e(tag, "gen err: ${r.throwable}")
                            }
                        }
                        val out = sb.toString()
                        val found = expected.count { out.contains(it) }
                        hit += found; total += expected.size
                        Log.i(tag, "UNIT=$unit found=$found/${expected.size} raw=[${out.replace("\n", "\\n")}]")
                    }
                }
                val sec = (System.nanoTime() - t0) / 1e9
                Log.i(tag, "UNIT RESULT unit=$unit score=${hit * 100 / total}% tokens=$tokens " +
                        "tok/s=${"%.1f".format(tokens / sec)} in ${"%.1f".format(sec)}s")
            } catch (t: Throwable) {
                Log.e(tag, "UNIT=$unit threw: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                try { llm?.close() } catch (_: Throwable) {}
            }
        }
    }
}
