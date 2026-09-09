# book-multirole 分支说明

在 [wzx1205/TalkifyTTS](https://github.com/wzx1205/TalkifyTTS)（上游 [LonePheasantWarrior/TalkifyTTS](https://github.com/LonePheasantWarrior/TalkifyTTS)）上的**多角色听书**功能分支。

## 做了什么

1. **对白规则分析**（`book/pipeline`）  
   引号切分、提示语（说/道/喊…）抽说话人、性别线索、情感标签。
2. **角色声线路由**（`book/router`）  
   旁白 / 男 / 女 / 备用槽 → 内置 12 个 ZipVoice 参考音色。
3. **本地合成接入**（`LocalModelProvider`）  
   开关打开时按句换参考音频流式合成；关闭时走原单音色路径。
4. **主界面开关**（仅本地模型供应商显示）  
   「多角色听书」Switch，状态存 `BookTtsSettings`。

## 默认角色→音色

| 槽位 | 默认音色 |
|---|---|
| narrator | 磁性男解说 |
| male | 青年男声 |
| female | 清澈女声 |
| male2 | 口播男声 |
| female2 | 温淑女声 |

## 如何使用

1. Android Studio 打开本仓库（需本机 JetBrains JDK 21 / Android Studio 自带 JBR，与上游一致）。
2. 安装 Debug 包，选择供应商「本地模型」并下载 ZipVoice。
3. 打开「多角色听书」。
4. 系统设置 → 文字转语音 → 选 Talkify；开源阅读用系统 TTS。

## 本地逻辑冒烟（不依赖 Android SDK）

```powershell
# 需已安装 kotlinc 或自备编译器
kotlinc app/src/main/java/com/github/lonepheasantwarrior/talkify/book/model/Utterance.kt `
  app/src/main/java/com/github/lonepheasantwarrior/talkify/book/pipeline/RuleEngine.kt `
  app/src/main/java/com/github/lonepheasantwarrior/talkify/book/pipeline/DialogueAnalyzer.kt `
  book-smoke-test/SmokeTest.kt -include-runtime -d smoke.jar
java -Dfile.encoding=UTF-8 -jar smoke.jar
```

期望输出包含 `RULE_ENGINE_SMOKE_OK`。

## 后续（未做）

- B2 角色绑定 UI（改槽位音色）
- B3 可选 Qwen3.5-0.8B GGUF 更准对白分析
- B4 情感曲线细化 / CosyVoice3 第二引擎
- 提示语未抽出人名时的说话人消解（如「他皱眉道」会回落上一说话人）

## 许可

沿用上游仓库 License；ZipVoice 模型权重 Emilia CC-BY-NC-4.0，仅非商用。
