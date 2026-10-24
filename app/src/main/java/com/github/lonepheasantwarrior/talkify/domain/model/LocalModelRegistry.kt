package com.github.lonepheasantwarrior.talkify.domain.model

import com.github.lonepheasantwarrior.talkify.book.model.Gender

/**
 * 本地模型注册表
 *
 * 定义所有可用的本地 TTS 模型清单，作为模型元数据的单一真实来源。
 * 使用 HuggingFace 国内镜像 hf-mirror.com 作为默认下载源，
 * 保留 hf 原始地址作为备用源。
 *
 * 新增模型只需在此处添加一个 [LocalModelInfo] 条目即可。
 */
object LocalModelRegistry {

    /**
     * 默认 HuggingFace 镜像地址（国内加速）
     */
    const val DEFAULT_HF_MIRROR = "https://hf-mirror.com"

    /**
     * 备用原始 HuggingFace 地址
     */
    const val FALLBACK_HF_ORIGIN = "https://huggingface.co"

    /**
     * 获取默认下载基础 URL
     */
    fun getDefaultBaseUrl(): String = DEFAULT_HF_MIRROR

    /**
     * 获取备用下载基础 URL
     */
    fun getFallbackBaseUrl(): String = FALLBACK_HF_ORIGIN

    /**
     * ZipVoice-Distill 模型定义（sherpa-onnx 适配版，int8 量化）
     *
     * 来源：k2-fsa/sherpa-onnx 官方 Releases（tts-models / vocoder-models 标签）
     * 架构：ZipVoice（流匹配零样本 TTS，音色由参考音频定义）
     * 语言：中文 / 英文
     *
     * 许可注意：模型权重基于 Emilia 数据集训练（CC-BY-NC-4.0），仅限非商用。
     *
     * 下载架构说明：模型文件 + espeak-ng-data + 参考音频均打包在官方 tarball 中，
     * 走 archiveAssets 一次性下载解压；声码器 vocos_24khz.onnx 单独下载。
     * GitHub 资源由下载服务自动叠加国内加速代理链（ghfast.top → gh-proxy.com → 源站），
     * 大陆直连 GitHub Releases 会被连接重置或仅有百 KB 级速率，不可直接使用。
     * 未采用「hf-mirror 拆分下载」的原因：k2-fsa/ZipVoice 的 HuggingFace 仓库
     * 仅有 encoder/decoder/tokens，缺少 lexicon、espeak-ng-data、vocoder 与参考
     * 音频（已全站排查确认），tarball 是唯一完整的官方发布物。
     *
     * 临时音色说明：test_wavs/leijun-1.wav 为官方测试音频（真人声纹），
     * 仅用于本地合成链路验证，正式发布前必须替换为授权干净的音色包。
     */
    val ZIPVOICE_DISTILL = LocalModelInfo(
        id = "zipvoice_distill",
        displayName = "ZipVoice-Distill 中英混合",
        description = "ZipVoice-Distill 零样本流匹配 TTS（int8 量化），自然度显著优于 VITS/Kokoro，音色由参考音频克隆",
        downloadSizeBytes = 204_000_000L,
        downloadSizeDisplay = "~200 MB",
        md5 = "",  // 待实际下载后计算真实值
        downloadFileInfo = mapOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos_24khz.onnx" to "vocos_24khz.onnx"
        ),
        requiredLocalFiles = listOf(
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "tokens.txt",
            "lexicon.txt",
            "espeak-ng-data/phontab",
            "test_wavs/leijun-1.wav"
        ),
        archiveAssets = mapOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2" to ""
        ),
        voiceList = listOf(
            LocalModelVoice(
                voiceId = "temp_leijun",
                displayName = "雷军",
                language = "zh",
                referenceFileName = "test_wavs/leijun-1.wav",
                referenceText = "那还是三十六年前, 一九八七年. 我呢考上了武汉大学的计算机系."
            )
        ),
        sampleRate = 24000,
        supportedLanguages = listOf("zh", "en")
    )

    /**
     * MeloTTS-ZH 中英混合模型定义（sherpa-onnx 适配版，VITS 架构）
     *
     * 来源：k2-fsa/sherpa-onnx 官方 Releases（tts-models 标签）
     * 架构：VITS（非自回归，单次前向；与 ZipVoice 的流匹配不同），无需参考音频，
     *      中文分词/注音由包内 lexicon.txt + dict/（jieba 词典 + FST）完成
     * 语言：中文 / 英文（单女声）
     * 许可：MIT
     *
     * 为什么值得接入：官方基准显示 MeloTTS 在骁龙 8 Elite 上 NPU 全链路约 159ms，
     * 主机 CPU 实测 RTF ≈ 0.27；而 CosyVoice3 的扩散架构在手机上是 RTF 27×。
     * 对"听书"这种长文本连续合成，这是可实时与不可实时的区别。
     *
     * 下载说明：官方 tarball 内含 model.onnx + tokens.txt + lexicon.txt + dict/ +
     * phone.fst/date.fst/new_heteronym.fst，是唯一完整的官方发布物，一次解压即用。
     * 走 GitHub Releases，由下载服务自动叠加国内加速代理链，各机型通用（纯 onnxruntime）。
     */
    val MELO_TTS_ZH_EN = LocalModelInfo(
        id = "melotts_zh_en",
        displayName = "MeloTTS 中英混合",
        description = "MeloTTS VITS 端上 TTS（MIT），非自回归、CPU 即可实时，中英混读，内置中文分词与注音",
        downloadSizeBytes = 167_000_000L,
        downloadSizeDisplay = "~160 MB",
        md5 = "",  // 待实际下载后计算真实值
        downloadFileInfo = emptyMap(),
        requiredLocalFiles = listOf(
            "model.onnx",
            "tokens.txt",
            "lexicon.txt"
        ),
        archiveAssets = mapOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2" to ""
        ),
        voiceList = listOf(
            LocalModelVoice(
                voiceId = "melo_zh_female",
                displayName = "默认女声",
                language = "zh",
                speakerId = 0
            )
        ),
        sampleRate = 44100,
        supportedLanguages = listOf("zh", "en"),
        architecture = LocalModelArchitecture.MELO_VITS
    )

    /**
     * fanchen-C 多声线中文包（sherpa-onnx 官方渠道分发的社区 VITS 模型）
     *
     * 来源链（已核实）：
     * - 训练框架：Plachtaa/VITS-fast-fine-tuning（开源）
     * - 原始权重：HuggingFace Space lkz99/tts_model（zh/G_C.pth，无 license 标注）
     * - ONNX 导出与分发：csukuangfj，经 k2-fsa/sherpa-onnx Releases（tts-models 标签）
     *
     * 重要事实：
     * - "fanchen" 命名含义已不可考（包内无说明，勿臆测为某部具体作品）；
     * - 187 个说话人是训练数据自动聚类的**匿名声线**（config speakers 即 "0".."186"），
     *   无角色名册；本 App 的"女声·低柔"等标签来自我们自己的基频声学分析
     *   （29 男 / 158 女，按基频排序），非上游元数据；
     * - 采样率 16000 Hz，主机实测 RTF ≈ 0.23。
     *
     * 许可注意：上游未声明 license，默认仅限个人使用，商用需自行向权重发布者确认。
     *
     * 音色清单为"女声在前、男声在后、同类按基频升序"的固定排序，
     * voiceId (fanchen_c_<sid>) 与 speakerId 绑定上游 sid，保持稳定。
     */
    val VITS_ZH_FANCHEN_C = LocalModelInfo(
        id = "vits_zh_fanchen_c",
        displayName = "中文多声线包 · 187 音色",
        description = "社区多说话人中文 VITS（16kHz），187 条匿名声线（29 男 / 158 女），已按性别与音高标注；来源无 license，建议个人使用",
        downloadSizeBytes = 119_000_000L,
        downloadSizeDisplay = "~114 MB",
        md5 = "",
        downloadFileInfo = emptyMap(),
        requiredLocalFiles = listOf(
            "vits-zh-hf-fanchen-C.onnx",
            "tokens.txt",
            "lexicon.txt"
        ),
        archiveAssets = mapOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-fanchen-C.tar.bz2" to ""
        ),
        voiceList = listOf(
            LocalModelVoice(voiceId = "fanchen_c_174", displayName = "女声·低柔 1", language = "zh", speakerId = 174, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_102", displayName = "女声·低柔 2", language = "zh", speakerId = 102, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_151", displayName = "女声·低柔 3", language = "zh", speakerId = 151, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_181", displayName = "女声·低柔 4", language = "zh", speakerId = 181, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_183", displayName = "女声·低柔 5", language = "zh", speakerId = 183, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_175", displayName = "女声·低柔 6", language = "zh", speakerId = 175, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_32", displayName = "女声·低柔 7", language = "zh", speakerId = 32, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_23", displayName = "女声·低柔 8", language = "zh", speakerId = 23, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_48", displayName = "女声·低柔 9", language = "zh", speakerId = 48, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_177", displayName = "女声·低柔 10", language = "zh", speakerId = 177, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_83", displayName = "女声·低柔 11", language = "zh", speakerId = 83, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_38", displayName = "女声·低柔 12", language = "zh", speakerId = 38, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_36", displayName = "女声·低柔 13", language = "zh", speakerId = 36, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_29", displayName = "女声·低柔 14", language = "zh", speakerId = 29, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_80", displayName = "女声·低柔 15", language = "zh", speakerId = 80, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_162", displayName = "女声·低柔 16", language = "zh", speakerId = 162, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_148", displayName = "女声·低柔 17", language = "zh", speakerId = 148, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_140", displayName = "女声·低柔 18", language = "zh", speakerId = 140, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_144", displayName = "女声·低柔 19", language = "zh", speakerId = 144, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_7", displayName = "女声·低柔 20", language = "zh", speakerId = 7, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_131", displayName = "女声·低柔 21", language = "zh", speakerId = 131, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_169", displayName = "女声·低柔 22", language = "zh", speakerId = 169, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_178", displayName = "女声·低柔 23", language = "zh", speakerId = 178, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_13", displayName = "女声·低柔 24", language = "zh", speakerId = 13, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_27", displayName = "女声·低柔 25", language = "zh", speakerId = 27, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_96", displayName = "女声·低柔 26", language = "zh", speakerId = 96, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_57", displayName = "女声·清亮 27", language = "zh", speakerId = 57, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_59", displayName = "女声·清亮 28", language = "zh", speakerId = 59, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_88", displayName = "女声·清亮 29", language = "zh", speakerId = 88, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_113", displayName = "女声·清亮 30", language = "zh", speakerId = 113, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_149", displayName = "女声·清亮 31", language = "zh", speakerId = 149, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_176", displayName = "女声·清亮 32", language = "zh", speakerId = 176, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_186", displayName = "女声·清亮 33", language = "zh", speakerId = 186, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_47", displayName = "女声·清亮 34", language = "zh", speakerId = 47, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_9", displayName = "女声·清亮 35", language = "zh", speakerId = 9, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_94", displayName = "女声·清亮 36", language = "zh", speakerId = 94, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_180", displayName = "女声·清亮 37", language = "zh", speakerId = 180, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_4", displayName = "女声·清亮 38", language = "zh", speakerId = 4, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_19", displayName = "女声·清亮 39", language = "zh", speakerId = 19, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_106", displayName = "女声·清亮 40", language = "zh", speakerId = 106, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_112", displayName = "女声·清亮 41", language = "zh", speakerId = 112, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_117", displayName = "女声·清亮 42", language = "zh", speakerId = 117, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_24", displayName = "女声·清亮 43", language = "zh", speakerId = 24, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_56", displayName = "女声·清亮 44", language = "zh", speakerId = 56, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_70", displayName = "女声·清亮 45", language = "zh", speakerId = 70, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_77", displayName = "女声·清亮 46", language = "zh", speakerId = 77, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_81", displayName = "女声·清亮 47", language = "zh", speakerId = 81, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_82", displayName = "女声·清亮 48", language = "zh", speakerId = 82, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_132", displayName = "女声·清亮 49", language = "zh", speakerId = 132, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_158", displayName = "女声·清亮 50", language = "zh", speakerId = 158, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_133", displayName = "女声·清亮 51", language = "zh", speakerId = 133, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_138", displayName = "女声·清亮 52", language = "zh", speakerId = 138, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_179", displayName = "女声·清亮 53", language = "zh", speakerId = 179, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_6", displayName = "女声·清亮 54", language = "zh", speakerId = 6, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_62", displayName = "女声·清亮 55", language = "zh", speakerId = 62, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_146", displayName = "女声·清亮 56", language = "zh", speakerId = 146, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_147", displayName = "女声·清亮 57", language = "zh", speakerId = 147, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_84", displayName = "女声·清亮 58", language = "zh", speakerId = 84, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_93", displayName = "女声·清亮 59", language = "zh", speakerId = 93, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_114", displayName = "女声·清亮 60", language = "zh", speakerId = 114, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_165", displayName = "女声·清亮 61", language = "zh", speakerId = 165, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_30", displayName = "女声·清亮 62", language = "zh", speakerId = 30, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_12", displayName = "女声·清亮 63", language = "zh", speakerId = 12, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_18", displayName = "女声·清亮 64", language = "zh", speakerId = 18, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_51", displayName = "女声·清亮 65", language = "zh", speakerId = 51, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_108", displayName = "女声·清亮 66", language = "zh", speakerId = 108, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_120", displayName = "女声·清亮 67", language = "zh", speakerId = 120, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_164", displayName = "女声·清亮 68", language = "zh", speakerId = 164, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_184", displayName = "女声·清亮 69", language = "zh", speakerId = 184, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_3", displayName = "女声·清亮 70", language = "zh", speakerId = 3, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_49", displayName = "女声·清亮 71", language = "zh", speakerId = 49, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_104", displayName = "女声·清亮 72", language = "zh", speakerId = 104, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_16", displayName = "女声·清亮 73", language = "zh", speakerId = 16, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_37", displayName = "女声·清亮 74", language = "zh", speakerId = 37, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_71", displayName = "女声·清亮 75", language = "zh", speakerId = 71, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_87", displayName = "女声·清亮 76", language = "zh", speakerId = 87, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_91", displayName = "女声·清亮 77", language = "zh", speakerId = 91, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_129", displayName = "女声·清亮 78", language = "zh", speakerId = 129, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_160", displayName = "女声·清亮 79", language = "zh", speakerId = 160, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_155", displayName = "女声·清亮 80", language = "zh", speakerId = 155, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_11", displayName = "女声·清亮 81", language = "zh", speakerId = 11, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_35", displayName = "女声·清亮 82", language = "zh", speakerId = 35, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_41", displayName = "女声·清亮 83", language = "zh", speakerId = 41, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_67", displayName = "女声·清亮 84", language = "zh", speakerId = 67, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_79", displayName = "女声·清亮 85", language = "zh", speakerId = 79, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_128", displayName = "女声·清亮 86", language = "zh", speakerId = 128, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_168", displayName = "女声·清亮 87", language = "zh", speakerId = 168, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_54", displayName = "女声·清亮 88", language = "zh", speakerId = 54, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_90", displayName = "女声·清亮 89", language = "zh", speakerId = 90, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_99", displayName = "女声·清亮 90", language = "zh", speakerId = 99, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_101", displayName = "女声·清亮 91", language = "zh", speakerId = 101, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_115", displayName = "女声·清亮 92", language = "zh", speakerId = 115, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_123", displayName = "女声·清亮 93", language = "zh", speakerId = 123, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_137", displayName = "女声·清亮 94", language = "zh", speakerId = 137, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_170", displayName = "女声·清亮 95", language = "zh", speakerId = 170, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_61", displayName = "女声·清亮 96", language = "zh", speakerId = 61, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_63", displayName = "女声·清亮 97", language = "zh", speakerId = 63, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_22", displayName = "女声·清亮 98", language = "zh", speakerId = 22, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_64", displayName = "女声·清亮 99", language = "zh", speakerId = 64, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_73", displayName = "女声·清亮 100", language = "zh", speakerId = 73, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_78", displayName = "女声·清亮 101", language = "zh", speakerId = 78, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_89", displayName = "女声·清亮 102", language = "zh", speakerId = 89, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_109", displayName = "女声·清亮 103", language = "zh", speakerId = 109, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_111", displayName = "女声·清亮 104", language = "zh", speakerId = 111, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_122", displayName = "女声·清亮 105", language = "zh", speakerId = 122, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_134", displayName = "女声·清亮 106", language = "zh", speakerId = 134, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_142", displayName = "女声·清亮 107", language = "zh", speakerId = 142, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_159", displayName = "女声·清亮 108", language = "zh", speakerId = 159, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_161", displayName = "女声·清亮 109", language = "zh", speakerId = 161, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_172", displayName = "女声·清亮 110", language = "zh", speakerId = 172, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_2", displayName = "女声·清亮 111", language = "zh", speakerId = 2, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_20", displayName = "女声·清亮 112", language = "zh", speakerId = 20, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_31", displayName = "女声·清亮 113", language = "zh", speakerId = 31, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_55", displayName = "女声·清亮 114", language = "zh", speakerId = 55, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_68", displayName = "女声·清亮 115", language = "zh", speakerId = 68, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_85", displayName = "女声·清亮 116", language = "zh", speakerId = 85, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_145", displayName = "女声·清亮 117", language = "zh", speakerId = 145, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_154", displayName = "女声·清亮 118", language = "zh", speakerId = 154, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_166", displayName = "女声·清亮 119", language = "zh", speakerId = 166, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_185", displayName = "女声·清亮 120", language = "zh", speakerId = 185, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_1", displayName = "女声·清亮 121", language = "zh", speakerId = 1, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_26", displayName = "女声·清亮 122", language = "zh", speakerId = 26, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_118", displayName = "女声·清亮 123", language = "zh", speakerId = 118, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_0", displayName = "女声·清亮 124", language = "zh", speakerId = 0, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_17", displayName = "女声·清亮 125", language = "zh", speakerId = 17, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_28", displayName = "女声·清亮 126", language = "zh", speakerId = 28, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_34", displayName = "女声·清亮 127", language = "zh", speakerId = 34, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_50", displayName = "女声·清亮 128", language = "zh", speakerId = 50, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_126", displayName = "女声·清亮 129", language = "zh", speakerId = 126, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_130", displayName = "女声·清亮 130", language = "zh", speakerId = 130, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_150", displayName = "女声·清亮 131", language = "zh", speakerId = 150, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_152", displayName = "女声·清亮 132", language = "zh", speakerId = 152, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_182", displayName = "女声·清亮 133", language = "zh", speakerId = 182, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_173", displayName = "女声·清亮 134", language = "zh", speakerId = 173, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_125", displayName = "女声·清亮 135", language = "zh", speakerId = 125, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_141", displayName = "女声·清亮 136", language = "zh", speakerId = 141, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_5", displayName = "女声·清亮 137", language = "zh", speakerId = 5, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_8", displayName = "女声·清亮 138", language = "zh", speakerId = 8, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_44", displayName = "女声·清亮 139", language = "zh", speakerId = 44, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_45", displayName = "女声·清亮 140", language = "zh", speakerId = 45, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_103", displayName = "女声·清亮 141", language = "zh", speakerId = 103, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_135", displayName = "女声·清亮 142", language = "zh", speakerId = 135, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_157", displayName = "女声·清亮 143", language = "zh", speakerId = 157, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_39", displayName = "女声·清亮 144", language = "zh", speakerId = 39, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_86", displayName = "女声·清亮 145", language = "zh", speakerId = 86, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_97", displayName = "女声·清亮 146", language = "zh", speakerId = 97, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_127", displayName = "女声·清亮 147", language = "zh", speakerId = 127, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_43", displayName = "女声·清亮 148", language = "zh", speakerId = 43, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_53", displayName = "女声·清亮 149", language = "zh", speakerId = 53, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_139", displayName = "女声·清亮 150", language = "zh", speakerId = 139, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_14", displayName = "女声·清亮 151", language = "zh", speakerId = 14, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_66", displayName = "女声·清亮 152", language = "zh", speakerId = 66, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_92", displayName = "女声·清亮 153", language = "zh", speakerId = 92, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_121", displayName = "女声·清亮 154", language = "zh", speakerId = 121, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_33", displayName = "女声·清亮 155", language = "zh", speakerId = 33, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_42", displayName = "女声·清亮 156", language = "zh", speakerId = 42, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_105", displayName = "女声·清亮 157", language = "zh", speakerId = 105, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_116", displayName = "女声·清亮 158", language = "zh", speakerId = 116, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "fanchen_c_74", displayName = "男声·低沉 159", language = "zh", speakerId = 74, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_124", displayName = "男声·低沉 160", language = "zh", speakerId = 124, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_119", displayName = "男声·低沉 161", language = "zh", speakerId = 119, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_136", displayName = "男声·沉稳 162", language = "zh", speakerId = 136, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_69", displayName = "男声·沉稳 163", language = "zh", speakerId = 69, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_40", displayName = "男声·沉稳 164", language = "zh", speakerId = 40, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_58", displayName = "男声·沉稳 165", language = "zh", speakerId = 58, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_100", displayName = "男声·沉稳 166", language = "zh", speakerId = 100, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_46", displayName = "男声·沉稳 167", language = "zh", speakerId = 46, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_163", displayName = "男声·沉稳 168", language = "zh", speakerId = 163, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_60", displayName = "男声·沉稳 169", language = "zh", speakerId = 60, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_15", displayName = "男声·沉稳 170", language = "zh", speakerId = 15, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_76", displayName = "男声·沉稳 171", language = "zh", speakerId = 76, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_10", displayName = "男声·沉稳 172", language = "zh", speakerId = 10, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_107", displayName = "男声·沉稳 173", language = "zh", speakerId = 107, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_25", displayName = "男声·沉稳 174", language = "zh", speakerId = 25, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_167", displayName = "男声·沉稳 175", language = "zh", speakerId = 167, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_52", displayName = "男声·沉稳 176", language = "zh", speakerId = 52, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_153", displayName = "男声·沉稳 177", language = "zh", speakerId = 153, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_110", displayName = "男声·沉稳 178", language = "zh", speakerId = 110, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_21", displayName = "男声·沉稳 179", language = "zh", speakerId = 21, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_156", displayName = "男声·沉稳 180", language = "zh", speakerId = 156, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_171", displayName = "男声·沉稳 181", language = "zh", speakerId = 171, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_95", displayName = "男声·沉稳 182", language = "zh", speakerId = 95, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_98", displayName = "男声·沉稳 183", language = "zh", speakerId = 98, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_143", displayName = "男声·沉稳 184", language = "zh", speakerId = 143, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_65", displayName = "男声·沉稳 185", language = "zh", speakerId = 65, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_72", displayName = "男声·沉稳 186", language = "zh", speakerId = 72, gender = Gender.MALE),
            LocalModelVoice(voiceId = "fanchen_c_75", displayName = "男声·沉稳 187", language = "zh", speakerId = 75, gender = Gender.MALE),
        ),
        sampleRate = 16000,
        supportedLanguages = listOf("zh"),
        architecture = LocalModelArchitecture.MELO_VITS
    )

    /**
     * voistock-2890 二次元角色包（CjangCjengh/VITS 系，cjke_cleaners2 IPA 音素）
     *
     * 来源链（已核实）：
     * - 原始权重：HuggingFace Space skytnt/moe-tts saved_model/15（2890 角色多说话人 VITS）
     * - 本地转换：voistock-2890.pth → ONNX（torch.onnx.export，68 符号与 checkpoint
     *   text_embedding [68,192] 逐一对齐验证）；lexicon 由 pypinyin 单字/词组表经
     *   chinese_to_ipa 生成（与训练期 cjke_cleaners2 的 [ZH] 分支同源），共 68055 条，
     *   含 0-99 数字；dict/ 复用官方 MeloTTS 包的 cppjieba 词典
     *
     * 分发方式：**不做在线下载**（空下载映射）。模型文件由 PC 侧转换产物 adb 推送到
     * tts_models/vits_voistock_2890/deployed/，推送完成后下载状态自动变为已就绪；
     * 未推送时点「下载」会因文件校验失败报错，属预期。
     *
     * 音色为 63 个精选角色（speakerId 与 speakers.csv 对齐，完整 2890 角色表见
     * speakers.csv）：16 主角 + 47 路人池。路人池性别由声学基频判定
     * （合成固定中文句 → librosa.pyin 中位 F0，阈值 180Hz，实测男女区间
     * 103-161 / 202-425Hz 天然空档零歧义），音色带按 F0 排序分档。
     * 日语原生平模，中文由 IPA lexicon 前端驱动（试听通过，RTF≈0.2 PC）。
     *
     * 许可注意：模型在声优表演数据上训练，角色声音权利属原作方/声优，仅限个人使用，
     * 不得随 APK 分发或商用。
     */
    val VITS_VOISTOCK_2890 = LocalModelInfo(
        id = "vits_voistock_2890",
        displayName = "二次元角色包 · 2890 音色",
        description = "多说话人 VITS 角色音色库（22kHz），63 音色：16 主角 + 47 路人池（声学定男女），中文可读，个人使用",
        downloadSizeBytes = 138_000_000L,
        downloadSizeDisplay = "~136 MB",
        md5 = "",
        downloadFileInfo = emptyMap(),
        requiredLocalFiles = listOf(
            "voistock-2890.onnx",
            "tokens.txt",
            "lexicon.txt",
            "dict/jieba.dict.utf8"
        ),
        archiveAssets = emptyMap(),
        voiceList = listOf(
            // ---- 主角精选（16）----
            LocalModelVoice(voiceId = "voistock_1217", displayName = "亚丝娜（戸松遥）SAO", language = "zh", speakerId = 1217, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_1756", displayName = "加藤惠（安野希世乃）路人女主", language = "zh", speakerId = 1756, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_1222", displayName = "桐人（松岡禎丞）SAO", language = "zh", speakerId = 1222, gender = Gender.MALE),
            LocalModelVoice(voiceId = "voistock_590", displayName = "蕾姆（水瀬いのり）Re:0", language = "zh", speakerId = 590, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_588", displayName = "拉姆（村川梨衣）Re:0", language = "zh", speakerId = 588, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_573", displayName = "爱蜜莉雅（高橋李依）Re:0", language = "zh", speakerId = 573, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_121", displayName = "阿尔托利亚/Saber（川澄綾子）Fate", language = "zh", speakerId = 121, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_378", displayName = "远坂凛（植田佳奈）Fate", language = "zh", speakerId = 378, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_369", displayName = "卫宫士郎（杉山紀彰）Fate", language = "zh", speakerId = 369, gender = Gender.MALE),
            LocalModelVoice(voiceId = "voistock_1311", displayName = "时崎狂三（真田アサミ）约会大作战", language = "zh", speakerId = 1311, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_1310", displayName = "五河琴里（竹達彩奈）约会大作战", language = "zh", speakerId = 1310, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_1764", displayName = "初音未来（藤田咲）", language = "zh", speakerId = 1764, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_2330", displayName = "绫波丽（林原めぐみ）EVA", language = "zh", speakerId = 2330, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_2312", displayName = "式波·明日香（宮村優子）EVA", language = "zh", speakerId = 2312, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_2333", displayName = "葛城美里（三石琴乃）EVA", language = "zh", speakerId = 2333, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_2316", displayName = "渚薰（石田彰）EVA", language = "zh", speakerId = 2316, gender = Gender.MALE),
            // ---- 路人池·女声·低柔（16，实测 F0 202-297Hz 升序）----
            LocalModelVoice(voiceId = "voistock_1224", displayName = "诗乃（SAO）· 女声·低柔", language = "zh", speakerId = 1224, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_1228", displayName = "有纪（SAO）· 女声·低柔", language = "zh", speakerId = 1228, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_752", displayName = "维兹（为美好的世界）· 女声·低柔", language = "zh", speakerId = 752, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_931", displayName = "企业（碧蓝航线）· 女声·低柔", language = "zh", speakerId = 931, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_1220", displayName = "阿尔戈（SAO）· 女声·低柔", language = "zh", speakerId = 1220, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_807", displayName = "御坂美琴（超电磁炮）· 女声·低柔", language = "zh", speakerId = 807, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_940", displayName = "齐柏林（碧蓝航线）· 女声·低柔", language = "zh", speakerId = 940, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_862", displayName = "雪之下雪乃（春物）· 女声·低柔", language = "zh", speakerId = 862, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_857", displayName = "一色彩羽（春物）· 女声·低柔", language = "zh", speakerId = 857, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_1226", displayName = "米特（SAO）· 女声·低柔", language = "zh", speakerId = 1226, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_1527", displayName = "可可萝（公主连结）· 女声·低柔", language = "zh", speakerId = 1527, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_760", displayName = "理世（点兔）· 女声·低柔", language = "zh", speakerId = 760, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_754", displayName = "达克妮斯（为美好的世界）· 女声·低柔", language = "zh", speakerId = 754, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_73", displayName = "古河渚（CLANNAD）· 女声·低柔", language = "zh", speakerId = 73, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_74", displayName = "藤林杏（CLANNAD）· 女声·低柔", language = "zh", speakerId = 74, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_1526", displayName = "凯露（公主连结）· 女声·低柔", language = "zh", speakerId = 1526, gender = Gender.FEMALE),
            // ---- 路人池·女声·清亮（16，实测 F0 300-425Hz 升序）----
            LocalModelVoice(voiceId = "voistock_1218", displayName = "爱丽丝（SAO）· 女声·清亮", language = "zh", speakerId = 1218, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_1232", displayName = "莉兹贝特（SAO）· 女声·清亮", language = "zh", speakerId = 1232, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_751", displayName = "阿库娅（为美好的世界）· 女声·清亮", language = "zh", speakerId = 751, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_748", displayName = "惠惠（为美好的世界）· 女声·清亮", language = "zh", speakerId = 748, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_72", displayName = "琴美（CLANNAD）· 女声·清亮", language = "zh", speakerId = 72, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_922", displayName = "光辉（碧蓝航线）· 女声·清亮", language = "zh", speakerId = 922, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_1227", displayName = "唯（SAO）· 女声·清亮", language = "zh", speakerId = 1227, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_1225", displayName = "西莉卡（SAO）· 女声·清亮", language = "zh", speakerId = 1225, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_757", displayName = "智乃（点兔）· 女声·清亮", language = "zh", speakerId = 757, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_953", displayName = "标枪（碧蓝航线）· 女声·清亮", language = "zh", speakerId = 953, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_935", displayName = "绊爱 · 女声·清亮", language = "zh", speakerId = 935, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_860", displayName = "由比滨结衣（春物）· 女声·清亮", language = "zh", speakerId = 860, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_946", displayName = "萨拉托加（碧蓝航线）· 女声·清亮", language = "zh", speakerId = 946, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_750", displayName = "悠悠（为美好的世界）· 女声·清亮", language = "zh", speakerId = 750, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_1525", displayName = "佩可（公主连结）· 女声·清亮", language = "zh", speakerId = 1525, gender = Gender.FEMALE),
            LocalModelVoice(voiceId = "voistock_961", displayName = "涅普顿（超次元游戏）· 女声·清亮", language = "zh", speakerId = 961, gender = Gender.FEMALE),
            // ---- 路人池·男声·低沉（7，实测 F0 103-135Hz 升序）----
            LocalModelVoice(voiceId = "voistock_218", displayName = "崔斯坦（Fate）· 男声·低沉", language = "zh", speakerId = 218, gender = Gender.MALE),
            LocalModelVoice(voiceId = "voistock_75", displayName = "乙坂有宇（Charlotte）· 男声·低沉", language = "zh", speakerId = 75, gender = Gender.MALE),
            LocalModelVoice(voiceId = "voistock_278", displayName = "兰斯洛特（Fate）· 男声·低沉", language = "zh", speakerId = 278, gender = Gender.MALE),
            LocalModelVoice(voiceId = "voistock_55", displayName = "朽木白哉（BLEACH）· 男声·低沉", language = "zh", speakerId = 55, gender = Gender.MALE),
            LocalModelVoice(voiceId = "voistock_104", displayName = "阿喀琉斯（Fate）· 男声·低沉", language = "zh", speakerId = 104, gender = Gender.MALE),
            LocalModelVoice(voiceId = "voistock_183", displayName = "盖提亚（Fate）· 男声·低沉", language = "zh", speakerId = 183, gender = Gender.MALE),
            LocalModelVoice(voiceId = "voistock_136", displayName = "伊斯坎达尔（Fate）· 男声·低沉", language = "zh", speakerId = 136, gender = Gender.MALE),
            // ---- 路人池·男声·沉稳（8，实测 F0 137-161Hz 升序）----
            LocalModelVoice(voiceId = "voistock_117", displayName = "阿周那（Fate）· 男声·沉稳", language = "zh", speakerId = 117, gender = Gender.MALE),
            LocalModelVoice(voiceId = "voistock_861", displayName = "叶山隼人（春物）· 男声·沉稳", language = "zh", speakerId = 861, gender = Gender.MALE),
            LocalModelVoice(voiceId = "voistock_1221", displayName = "艾基尔（SAO）· 男声·沉稳", language = "zh", speakerId = 1221, gender = Gender.MALE),
            LocalModelVoice(voiceId = "voistock_262", displayName = "梅林（Fate）· 男声·沉稳", language = "zh", speakerId = 262, gender = Gender.MALE),
            LocalModelVoice(voiceId = "voistock_806", displayName = "一方通行（超电磁炮）· 男声·沉稳", language = "zh", speakerId = 806, gender = Gender.MALE),
            LocalModelVoice(voiceId = "voistock_1231", displayName = "尤吉欧（SAO）· 男声·沉稳", language = "zh", speakerId = 1231, gender = Gender.MALE),
            LocalModelVoice(voiceId = "voistock_214", displayName = "大卫（Fate）· 男声·沉稳", language = "zh", speakerId = 214, gender = Gender.MALE),
            LocalModelVoice(voiceId = "voistock_78", displayName = "夜神月（死亡笔记）· 男声·沉稳", language = "zh", speakerId = 78, gender = Gender.MALE),
        ),
        sampleRate = 22050,
        supportedLanguages = listOf("zh"),
        architecture = LocalModelArchitecture.MELO_VITS
    )

    /**
     * 所有已注册的本地模型列表
     */
    val ALL_MODELS: List<LocalModelInfo> = listOf(
        MELO_TTS_ZH_EN,
        VITS_ZH_FANCHEN_C,
        VITS_VOISTOCK_2890,
        ZIPVOICE_DISTILL
    )

    /**
     * 模型 ID → LocalModelInfo 映射（懒加载）
     */
    private val modelMap: Map<String, LocalModelInfo> by lazy {
        ALL_MODELS.associateBy { it.id }
    }

    /**
     * 根据模型 ID 获取模型元信息
     *
     * @param modelId 模型唯一标识符
     * @return 模型元信息，未找到时返回 null
     */
    fun getModel(modelId: String): LocalModelInfo? {
        return modelMap[modelId]
    }

    /**
     * 获取默认模型
     */
    fun getDefaultModel(): LocalModelInfo = ZIPVOICE_DISTILL
}
