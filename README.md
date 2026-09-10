<!-- Mascot on the Right (Float) -->
<img src="doc/images/Screenshot_talkify.webp" align="right" width="25%" alt="Talkify Screenshot" style="margin-left: 20px; margin-bottom: 20px; border-radius: 8px;">

# Talkify

#### 云端大模型 + 本地 AI 驱动的 Android TTS 引擎

> [!IMPORTANT]
> **这是 [LonePheasantWarrior/TalkifyTTS](https://github.com/LonePheasantWarrior/TalkifyTTS) 的 Fork**（`book-multirole` 分支），在上游「本地模型」能力之上新增了**多角色听书**体系：EPUB 全书角色扫描、角色册音色绑定、旁白独立声线、小米 MiMo 逐句情感，以及「MiMo 念旁白 + Edge 免费音色念角色」的混合引擎。详见 [多角色听书（本 Fork 新增）](#-多角色听书本-fork-新增)。
>
> **关于文末赞赏**：README 中的收款码是**原作者**的，扫码打赏的钱会给到上游作者 LonePheasantWarrior，与本 Fork 维护者无关。

Talkify 是一款基于 Android 的现代化 TTS 连接器。它将云端顶尖大模型（微软、通义千问、豆包、腾讯云、MiniMax、小米 MiMo）的高质量拟人语音合成能力，以及**完全离线运行的本地 AI 语音合成引擎**（ZipVoice，含 12 种精选音色），通过 Android 标准 Text-to-Speech 接口赋予您的系统和阅读软件。

<p>
  <img src="https://img.shields.io/badge/Language-Kotlin-7f52ff?style=flat-square&logo=kotlin" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat-square&logo=jetpackcompose" alt="Compose"/>
  <img src="https://img.shields.io/badge/Material-Design%203-6200EE?style=flat-square&logo=materialdesign" alt="Material 3"/>
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android" alt="Android"/>
</p>

让您的电子书朗读不再机械冰冷，而是充满情感与温度。

<br clear="both"/>

## ✨ 核心特性

- **🔌 多供应商支持**：内置 **阿里云通义千问**、**火山引擎豆包**、**腾讯云**、**微软 Azure**、**MiniMax** 和 **小米 MiMo**，支持一键切换。
- **🆓 本地离线合成**：内置 ZipVoice 本地 AI 引擎与 12 种精选中文音色，无需 API Key、零调用费用、断网可用（详见 [本地 AI 语音合成](#️-本地-ai-语音合成)）。
- **⚙️ 自定义配置**：支持为每个供应商自定义 API 地址和模型 ID，适配自建代理、私有化部署等高级场景。
- **📱 系统级集成**：实现标准 Android TTS 接口，无缝支持 Legado（阅读）、Google Play图书 等任意支持调用TTS引擎的阅读软件。
- **⚡️ 流式合成**：采用流式传输技术（Streaming），大幅降低首字延迟，实现近乎实时的响应速度。
- **🛡️ 稳定后台**：完善的权限引导（网络、电池优化、通知），确保在后台长时间朗读不中断。
- **🎨 现代设计**：完全基于 Jetpack Compose 构建，遵循最新的 Material 3 Expressive 设计规范。

## 📖 多角色听书（本 Fork 新增）

> 全部在 `book-multirole` 分支实现，选择 **本地模型 / 小米 MiMo / MiMo+Edge 混合** 任一供应商后，打开「多角色听书」开关即可生效。

- **🎭 自动角色分声**：逐句分析小说文本（对白切分 → 说话人识别 → 性别判断 → 情感标注），旁白与不同角色自动切换声线；相邻两句对白自动防撞声线，双男/双女对手戏不再「同人异口」。
- **📚 EPUB 角色册**：导入 EPUB 一键全书扫描（百万字级约 4 秒），聚合角色对白量、性别投票与样例台词；角色/旁白分别绑定音色，支持本地、MiMo、Edge 三套音色方案一键转换。
- **🧭 旁白独立声线**：旁白在角色册中置顶单独选择，角色再多也不会被淹没。
- **🈶 小米 MiMo 逐句情感**：情感标签映射为 MiMo 风格指令（愤怒/悲伤/欢快/恐惧/惊讶），旁白与角色台词带语气朗读。
- **🎛️ MiMo+Edge 混合引擎**：旁白走 MiMo 冰糖（稳定有质感），对白走 Edge 免费神经音色（云希/云健/晓伊/晓双童声等，零成本、音色多）；双引擎互为备胎，网络抖动不断流。
- **🧩 说话人名清洗**：动作/神态短语（抱拳、叹了口气、笑呵呵…）不再被误当人名；人名截断与带尾巴说话人自动归并到同一角色；「叹了口气道」等语气描写保留朗读不吞字。

## 🧠 支持的供应商

| 默认模型 | 供应商 | 语言支持 | 特点    |
|:---:|:---|:---|:------|
| **microsoft-tts** | Azure | 🇨🇳 🇺🇸 🇬🇧 🇯🇵 🇰🇷 <br>🇫🇷 🇩🇪 🇪🇸 | 40+种音色，无需API Key |
| **seed-tts-2.0** | 火山引擎 | 🇨🇳 🇺🇸 | 16种音色，人声更自然 |
| **tencent-tts** | 腾讯云 | 🇨🇳 🇺🇸 | 47种音色（超自然/大模型/精品） |
| **qwen3-tts-flash** | 阿里云百炼 | 🇨🇳 🇺🇸 🇩🇪 🇫🇷 🇪🇸 <br>🇵🇹 🇮🇹 🇯🇵 🇰🇷 🇷🇺 | 48种音色，多语种支持 |
| **speech-2.8-turbo** | MiniMax | 🇨🇳 🇺🇸 | 32kHz 高采样率，WebSocket 流式 |
| **mimo-v2.5-tts** | 小米 | 🇨🇳 🇺🇸 | OpenAI API 兼容 |

## 🎙️ 本地 AI 语音合成

除了云端供应商，Talkify 还内置了**完全离线运行**的本地语音合成引擎——无需申请任何 API Key，模型下载完成后断网也能朗读。

- **🧠 高质量模型**：基于 [ZipVoice-Distill](https://github.com/k2-fsa/ZipVoice) 零样本流匹配 TTS（int8 量化），由 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 本地推理，支持中英文混读，24kHz 采样率，自然度显著优于传统 VITS 方案。
- **🎭 精选音色**：内置 12 种精选中文音色（涵盖女声、男声、解说等多种风格）。
- **📥 按需下载**：模型（约 200 MB）在首次使用时由 App 引导下载，不增加 APK 体积；针对中国大陆网络环境自动叠加 GitHub 加速链路（ghfast.top → gh-proxy.com → 源站），无需科学上网。
- **🚫 零成本零依赖**：合成全程在设备端完成，无网络请求、无调用计费、无限流配额，隐私数据不出设备。
- **⚡ 句级流式**：按句切分流式合成、边合成边播放，长文本朗读无需整段等待。

> **许可说明**：ZipVoice 模型权重基于 Emilia 数据集训练（CC-BY-NC-4.0），本地合成能力仅供个人学习等非商业用途。

## 🛠️ 技术栈

- **语言**: Kotlin 2.4.10
- **架构**: MVVM (Model-View-ViewModel) + Clean Architecture
- **UI**: Jetpack Compose (BOM 2026.06.01) + Material 3 Expressive
- **网络**: OkHttp 4.12.0 (HTTP/2, WebSocket, Streaming)
- **本地推理**: sherpa-onnx v1.13.1 (ONNX Runtime, CPU int8) + ZipVoice-Distill
- **最低兼容**: Android 11 (API 30)
- **目标版本**: Android 17 (API 37)

## 🚀 快速开始

### 前置准备

> **提示**：Azure 供应商 **无需任何配置**，开箱即用！本地 AI 语音合成同样无需配置，在 App 内选择本地模型并按引导下载（约 200 MB）即可。

1. **火山引擎**：前往 [控制台](https://console.volcengine.com/speech/new/setting/apikeys) 申请 API Key。
2. **腾讯云**：前往 [控制台](https://console.cloud.tencent.com/cam/capi) 获取 AppID、SecretID 和 SecretKey。
3. **阿里云百炼**：前往 [控制台](https://bailian.console.aliyun.com/) 申请 API Key。
4. **MiniMax**：前往 [控制台](https://platform.minimaxi.com/) 申请 API Key。
5. **小米 MiMo**：前往 [控制台](https://api.xiaomimimo.com/) 申请 API Key。

### 构建与运行

```bash
# 1. 克隆本 Fork（book-multirole 分支）
git clone -b book-multirole https://github.com/wzx1205/TalkifyTTS.git
cd TalkifyTTS

# 2. 检查代码
./gradlew lint

# 3. 编译 Debug 包
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/Talkify-v*-universal-debug.apk
```

### 多角色听书快速上手

1. 系统设置 → 文字转语音（TTS）→ 引擎选 **Talkify**；
2. 打开 Talkify → 供应商选 **本地模型**（离线）/ **小米**（需 API Key）/ **MiMo+Edge 混合**（角色免费）；
3. 点「管理角色 ›」→ 导入 EPUB → 全书扫描后自动分配音色，可手动微调，旁白单独选声线；
4. 打开「多角色听书」开关；
5. 回到阅读 App（如 Legado）→ 朗读，即可享受分角色听书。

### 推荐搭配

Talkify 作为一个 TTS 连接器，最佳的使用场景是搭配优秀的电子书阅读器：
*   ~~[Legado / 开源阅读](https://github.com/gedoor/legado)~~（已停止维护）
*   [Google Play Books / Google Play 图书](https://play.google.com/store/apps/details?id=com.google.android.apps.books)

### 其他电子书阅读器推荐

*   [Readest](https://github.com/readest/readest) （内置微软 EdgeTTS 引擎，可直接免费调用。朗读效果稍逊但好在可以白嫖，也是个不错的选择～）

## 📚 文档

详细的架构设计、代码规范和扩展指南，请参阅 [doc/开发指南.md](doc/开发指南.md)。

## ☕️ Buy Me a Mixue（原作者收款码）

> [!WARNING]
> **下方收款码属于原作者 [LonePheasantWarrior](https://github.com/LonePheasantWarrior)，并非本 Fork 维护者。**
> 扫码打赏的资金将全部支持上游作者——多角色听书 Fork 本身免费、也不接受打赏。如果你想支持本 Fork，欢迎提 Issue、PR 或点个 Star ⭐。

如果您觉得 Talkify 对您有帮助，欢迎请原作者喝杯蜜雪冰城 🍦

<table>
  <tr>
    <td style="text-align: center;">
      <img src="doc/images/alipay_1769136488503.webp" width="200" alt="支付宝"/>
      <br>支付宝
    </td>
    <td style="text-align: center;">
      <img src="doc/images/wechat_1769136466823.webp" width="200" alt="微信"/>
      <br>微信
    </td>
  </tr>
</table>

## 🤝 致谢

*   **[LonePheasantWarrior](https://github.com/LonePheasantWarrior)** —— 上游 TalkifyTTS 的作者，本 Fork 的全部改动建立在其成果之上
*   [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 与 [ZipVoice](https://github.com/k2-fsa/ZipVoice) —— 本地离线语音推理
*   [Trae](https://www.trae.cn)
*   [CodeBuddy](https://www.codebuddy.cn)
*   [Gemini CLI](https://geminicli.com)
*   [DeepSeek](https://platform.deepseek.com)
*   [MiniMax](https://www.minimaxi.com)
*   [Gemini](https://deepmind.google/models/gemini)
