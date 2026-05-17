# mcAgent

**AI 语音助手 Minecraft Mod** — 通过语音控制 Minecraft，让大模型帮你执行游戏指令。

> Fabric 客户端 Mod | Minecraft 1.21.10 | Java 21

## ✨ 功能特性

- 🎤 **语音输入**：按键录音，支持三种录音模式
- 🧠 **AI 驱动**：语音转文字后发送给 LLM，或直接发送音频
- ⚡ **自动执行**：大模型返回 Minecraft 指令，Mod 自动以客户端身份本地执行
- 🛡️ **风险确认**：危险指令需玩家语音确认后才执行
- 💬 **AI 聊天**：大模型可通过客户端聊天栏直接与玩家对话（带 `§d[AI]` 前缀区分）
- 📍 **上下文感知**：自动发送玩家位置、朝向、维度、世界时间等信息
- 🔄 **多步执行**：保留对话上下文，支持连续多步操作
- 📋 **指令回显**：执行结果自动反馈给大模型，便于多步决策
- 🎮 **全场景兼容**：单机、局域网、服务器均可使用
- ⚙️ **高度可配置**：通过配置文件或 ModMenu 界面调整所有参数

## 📦 安装

### 前置要求

- Minecraft 1.21.10
- [Fabric Loader](https://fabricmc.net/) >= 0.19.2
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 21+

### 可选依赖

- [Mod Menu](https://modrinth.com/mod/modmenu) — 提供游戏内图形化配置界面

### 安装步骤

1. 下载 Mod 的 `.jar` 文件
2. 放入 `.minecraft/mods/` 目录
3. 启动游戏

## 🎮 使用方法

### 按键

默认按键为 **V**，可在 Minecraft 按键设置中修改。

### 录音模式

| 模式 | 说明 |
|------|------|
| `push_to_talk` | 按住 V 说话，松开发送。按键时长需 ≥ 最小语音时长（默认 1 秒），防止误触 |
| `push_to_send` | 后台持续录音（30 秒环形缓冲区），按 V 提取最后一段语音发送 |
| `toggle` | 按 V 开启/关闭监听，检测到静音自动截断发送并继续监听 |

### 文字输入

也可以通过客户端命令直接发送文字：

```
/mcagent <消息内容>
```

### HUD 状态指示

- `● 录音中...` — 正在录音
- `◉ 监听中` — 后台监听中（push_to_send 模式）
- `⟳ 处理中...` / `⟳ AI思考中...` — 正在处理语音或等待 AI 响应

## ⚙️ 配置

配置文件位于 `.minecraft/config/mcagent.json`，首次启动自动生成。也可通过 Mod Menu 在游戏内修改。

### 配置项

```json
{
  "recordingMode": "push_to_talk",
  "voiceMode": "stt_then_llm",
  "keyBind": "key.keyboard.v",

  "sttType": "online",
  "sttApiUrl": "https://api.openai.com/v1/audio/transcriptions",
  "sttApiKey": "your-api-key",
  "sttModel": "whisper-1",
  "sttLocalPath": "",

  "llmApiUrl": "https://api.openai.com/v1/chat/completions",
  "llmApiKey": "your-api-key",
  "llmModel": "gpt-4o",
  "maxContextRounds": 64,

  "systemPrompt": "你是我的Minecraft助手，帮我执行各种游戏操作。请用简体中文回复。",

  "silenceThreshold": 500,
  "maxSilenceMs": 3000,
  "minSpeechMs": 500
}
```

### ⚠️ 关于默认 API Key

配置文件中预置的 API Key 为**限期限量的测试用 Key**，仅供体验和测试使用。该 Key 可能随时过期或达到用量上限，届时将无法正常使用。如需测试请尽早完成，过期后需自行配置 STT 和 LLM 的 API 地址与 Key。

### 配置说明

| 配置项 | 说明 |
|--------|------|
| `recordingMode` | 录音模式：`push_to_talk` / `push_to_send` / `toggle` |
| `voiceMode` | 语音处理模式：`stt_then_llm`（先转文字再发 LLM）/ `direct_audio`（直接发音频） |
| `sttType` | STT 类型：`online`（在线 API）/ `local`（本地 Whisper） |
| `sttApiUrl` | STT API 地址（兼容 OpenAI Whisper API 格式） |
| `sttApiKey` | STT API Key |
| `sttModel` | STT 模型名称 |
| `sttLocalPath` | 本地 Whisper 可执行文件路径（`sttType` 为 `local` 时使用） |
| `llmApiUrl` | LLM API 地址（兼容 OpenAI Chat Completions 格式） |
| `llmApiKey` | LLM API Key |
| `llmModel` | LLM 模型名称 |
| `maxContextRounds` | 最大上下文保留轮数 |
| `systemPrompt` | 自定义系统提示词（工具调用提示词会自动拼接在前面） |
| `silenceThreshold` | 静音检测阈值（音频振幅均值） |
| `maxSilenceMs` | 最大静音时长（ms），超过后自动截断 |
| `minSpeechMs` | 最小有效语音/按键时长（ms），低于此值视为误触 |

## 🏗️ 架构

```
src/client/java/space/minecraftstl/mcagent/client/
├── McagentClient.java          # 客户端入口，按键处理，录音模式调度
├── audio/
│   └── AudioRecorder.java      # 音频录制（普通录音、环形缓冲区后台录音）
├── command/
│   ├── CommandExecutor.java     # 指令执行（客户端命令/聊天命令）
│   └── CommandFeedbackCapture.java  # 指令执行结果捕获（Mixin）
├── config/
│   ├── McagentConfig.java       # 配置文件读写
│   └── ModMenuIntegration.java  # Mod Menu 集成
├── llm/
│   ├── LlmClient.java          # LLM API 调用，上下文管理，玩家信息收集
│   └── ToolHandler.java         # 工具调用处理（execute_command, ask_player, send_chat）
├── stt/
│   └── SpeechToText.java        # 语音转文字（在线 API / 本地 Whisper）
└── ui/
    ├── ConfigScreen.java        # 配置界面
    └── HudRenderer.java         # HUD 状态显示
```

## 🔧 开发

### 构建

```bash
./gradlew build
```

### 运行客户端

```bash
./gradlew runClient
```

### 依赖

- Fabric Loader 0.19.2
- Fabric API 0.138.4+1.21.10
- Mod Menu 14.0.0-beta.1（可选，编译时依赖）

## 📄 许可证

Copyright © 2026 MinecraftSTL. All rights reserved.