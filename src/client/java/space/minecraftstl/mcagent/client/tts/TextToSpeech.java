package space.minecraftstl.mcagent.client.tts;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.minecraftstl.mcagent.client.config.McagentConfig;
import space.minecraftstl.mcagent.client.stt.SpeechToText;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class TextToSpeech {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcagent");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcagent-tts");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicReference<Clip> currentClip = new AtomicReference<>();

    public static void speak(String text) {
        McagentConfig cfg = McagentConfig.get();
        if (!cfg.ttsEnabled || text == null || text.isBlank()) return;
        EXECUTOR.submit(() -> {
            try {
                if (SpeechToText.isQwenModel(cfg.ttsModel)) {
                    // Qwen模型走WebSocket实时TTS（直连DashScope，流式播放）
                    QwenRealtimeTts.speakBlocking(text, cfg);
                } else {
                    byte[] audioData = requestTts(text, cfg);
                    if (audioData != null) playWav(audioData);
                }
            } catch (Exception e) {
                LOGGER.error("[mcAgent] TTS播放失败", e);
            }
        });
    }

    private static byte[] requestTts(String text, McagentConfig cfg) throws Exception {
        String voice = (cfg.ttsVoice != null && !cfg.ttsVoice.isBlank()) ? cfg.ttsVoice : "alloy";
        String jsonBody = GSON.toJson(Map.of(
                "model", cfg.ttsModel,
                "input", text,
                "voice", voice,
                "response_format", "pcm"
        ));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(cfg.ttsApiUrl))
                .header("Authorization", "Bearer " + cfg.ttsApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() == 200) {
            return resp.body();
        }
        LOGGER.warn("[mcAgent] TTS请求失败: {} {}", resp.statusCode(), new String(resp.body()));
        return null;
    }

    private static void playWav(byte[] audioData) {
        try {
            stopCurrent();
            // OpenAI兼容TTS的pcm格式: 24kHz, 16bit, mono, signed, little-endian
            AudioFormat format = new AudioFormat(24000, 16, 1, true, false);
            AudioInputStream ais = new AudioInputStream(
                    new ByteArrayInputStream(audioData), format, audioData.length / format.getFrameSize());
            Clip clip = AudioSystem.getClip();
            clip.open(ais);
            currentClip.set(clip);
            clip.start();
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    clip.close();
                    currentClip.compareAndSet(clip, null);
                }
            });
        } catch (Exception e) {
            LOGGER.error("[mcAgent] 音频播放失败", e);
        }
    }

    public static void stopCurrent() {
        Clip clip = currentClip.getAndSet(null);
        if (clip != null && clip.isRunning()) {
            clip.stop();
            clip.close();
        }
    }
}