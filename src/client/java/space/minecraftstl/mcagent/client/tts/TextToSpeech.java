package space.minecraftstl.mcagent.client.tts;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.minecraftstl.mcagent.client.config.McagentConfig;

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

    /**
     * 异步朗读文本。如果 TTS 未启用则不执行。
     */
    public static void speak(String text) {
        McagentConfig cfg = McagentConfig.get();
        if (!cfg.ttsEnabled || text == null || text.isBlank()) return;

        EXECUTOR.submit(() -> {
            try {
                byte[] audioData = requestTts(text, cfg);
                if (audioData != null) {
                    playWav(audioData);
                }
            } catch (Exception e) {
                LOGGER.error("[mcAgent] TTS播放失败", e);
            }
        });
    }

    private static byte[] requestTts(String text, McagentConfig cfg) {
        try {
            Map<String, String> body = Map.of(
                    "model", cfg.ttsModel,
                    "input", text,
                    "voice", cfg.ttsVoice,
                    "response_format", "pcm"
            );

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.ttsApiUrl))
                    .header("Authorization", "Bearer " + cfg.ttsApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();

            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                return resp.body();
            } else {
                LOGGER.warn("[mcAgent] TTS API返回错误: {} {}", resp.statusCode(), new String(resp.body()));
                return null;
            }
        } catch (Exception e) {
            LOGGER.error("[mcAgent] TTS请求失败", e);
            return null;
        }
    }

    private static void playWav(byte[] pcmData) {
        try {
            // 停止上一个正在播放的音频
            Clip old = currentClip.getAndSet(null);
            if (old != null && old.isRunning()) {
                old.stop();
                old.close();
            }

            // OpenAI兼容TTS的pcm格式: 24kHz, 16bit, mono, signed, little-endian
            AudioFormat format = new AudioFormat(24000, 16, 1, true, false);
            AudioInputStream ais = new AudioInputStream(
                    new ByteArrayInputStream(pcmData), format, pcmData.length / format.getFrameSize());
            Clip clip = AudioSystem.getClip();
            clip.open(ais);
            currentClip.set(clip);
            clip.start();
            // 等待播放完成
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
}