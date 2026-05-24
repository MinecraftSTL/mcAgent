package space.minecraftstl.mcagent.client.stt;

import com.alibaba.dashscope.audio.omni.*;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.minecraftstl.mcagent.client.config.McagentConfig;

import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class QwenRealtimeAsr {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcagent");
    private static final int AUDIO_CHUNK_SIZE = 1024;
    private static final int SLEEP_INTERVAL_MS = 30;

    public static String transcribeBlocking(byte[] audioData, McagentConfig cfg) {
        try {
            String model = cfg.sttModel;
            String apiKey = cfg.sttApiKey;
            String wsUrl = cfg.sttApiUrl;
            LOGGER.info("[mcAgent] ASR OmniRealtime: model={}, audioSize={}", model, audioData.length);
            OmniRealtimeParam param = OmniRealtimeParam.builder().model(model).url(wsUrl).apikey(apiKey).build();
            CountDownLatch finishLatch = new CountDownLatch(1);
            AtomicReference<String> resultText = new AtomicReference<>("");
            OmniRealtimeConversation conv = new OmniRealtimeConversation(param, new OmniRealtimeCallback() {
                @Override public void onOpen() { LOGGER.info("[mcAgent] ASR WSS connected"); }
                @Override public void onEvent(JsonObject msg) { handleEvent(msg, resultText, finishLatch); }
                @Override public void onClose(int code, String reason) { LOGGER.info("[mcAgent] ASR closed: {}", reason); finishLatch.countDown(); }
            });
            try { conv.connect(); } catch (NoApiKeyException e) { throw new RuntimeException(e); }
            OmniRealtimeTranscriptionParam tp = new OmniRealtimeTranscriptionParam();
            tp.setLanguage("zh");
            tp.setInputAudioFormat("pcm");
            tp.setInputSampleRate(16000);
            OmniRealtimeConfig config = OmniRealtimeConfig.builder()
                    .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
                    .transcriptionConfig(tp).build();
            conv.updateSession(config);
            int offset = 0;
            while (offset < audioData.length) {
                int len = Math.min(AUDIO_CHUNK_SIZE, audioData.length - offset);
                byte[] chunk = new byte[len];
                System.arraycopy(audioData, offset, chunk, 0, len);
                conv.appendAudio(Base64.getEncoder().encodeToString(chunk));
                offset += len;
                Thread.sleep(SLEEP_INTERVAL_MS);
            }
            conv.endSession();
            finishLatch.await();
            return resultText.get();
        } catch (Exception e) {
            LOGGER.error("[mcAgent] ASR OmniRealtime error", e);
            return "";
        }
    }

    private static void handleEvent(JsonObject msg, AtomicReference<String> result, CountDownLatch latch) {
        String type = msg.get("type").getAsString();
        switch (type) {
            case "session.created":
                LOGGER.info("[mcAgent] ASR session: {}", msg.get("session").getAsJsonObject().get("id").getAsString());
                break;
            case "conversation.item.input_audio_transcription.completed":
                String text = msg.get("transcript").getAsString();
                LOGGER.info("[mcAgent] ASR result: {}", text);
                result.set(text);
                latch.countDown();
                break;
            case "input_audio_buffer.speech_started":
                LOGGER.debug("[mcAgent] VAD start"); break;
            case "input_audio_buffer.speech_stopped":
                LOGGER.debug("[mcAgent] VAD stop"); break;
            default: break;
        }
    }
}