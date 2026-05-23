package space.minecraftstl.mcagent.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class McagentConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("mcagent.json");
    private static McagentConfig INSTANCE;

    // 录音模式: push_to_talk, push_to_send, toggle
    public String recordingMode = "push_to_talk";
    // 语音处理模式: stt_then_llm, direct_audio
    public String voiceMode = "stt_then_llm";
    // 按键绑定
    public String keyBind = "key.keyboard.v";

    // STT 配置
    public String sttType = "online"; // online 或 local
    public String sttApiUrl = "https://api.example.invalid:3000/v1/audio/transcriptions";
    public String sttApiKey = "";
    public String sttModel = "whisper-1";
    public String sttLocalPath = ""; // 本地whisper可执行文件路径

    // LLM 配置
    public String llmApiUrl = "https://api.example.invalid:3000/v1/chat/completions";
    public String llmApiKey = "";
    public String llmModel = "claude-opus-4-6";
    public int maxContextRounds = 64;

    // 提示词
    public String systemPrompt = "你是我的Minecraft助手，帮我执行各种游戏操作。请用简体中文回复。";

    // TTS 配置
    public boolean ttsEnabled = false;
    public String ttsApiUrl = "https://api.example.invalid:3000/v1/audio/speech";
    public String ttsApiKey = "";
    public String ttsModel = "kokoro";
    public String ttsVoice = "alloy";

    // 音频参数
    public int silenceThreshold = 500;
    public int maxSilenceMs = 3000;
    public int minSpeechMs = 500; // 最小有效语音时长(ms)，低于此值视为误触

    public static McagentConfig get() {
        if (INSTANCE == null) load();
        return INSTANCE;
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                INSTANCE = GSON.fromJson(Files.readString(CONFIG_PATH), McagentConfig.class);
                return;
            } catch (IOException ignored) {}
        }
        INSTANCE = new McagentConfig();
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(INSTANCE));
        } catch (IOException ignored) {}
    }
}
