import com.alibaba.dashscope.audio.omni.*;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Standalone test for DashScope ASR WebSocket connection.
 * Run with: gradlew runAsrTest -PapiKey=sk-xxx
 */
public class AsrConnectionTest {
    public static void main(String[] args) {
        String apiKey = System.getProperty("apiKey", System.getenv("DASHSCOPE_API_KEY"));
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("ERROR: No API key. Use -DapiKey=sk-xxx or set DASHSCOPE_API_KEY env var.");
            System.exit(1);
        }
        System.out.println("Using API key: " + apiKey.substring(0, 6) + "...");

        String model = "qwen3-asr-flash-realtime";
        String url = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("TIMEOUT");

        System.out.println("Connecting to: " + url);
        System.out.println("Model: " + model);

        OmniRealtimeParam param = OmniRealtimeParam.builder()
                .model(model)
                .url(url)
                .apikey(apiKey)
                .build();

        OmniRealtimeConversation conv = new OmniRealtimeConversation(param, new OmniRealtimeCallback() {
            @Override
            public void onOpen() {
                System.out.println("[OK] WebSocket connected!");
            }
            @Override
            public void onEvent(JsonObject msg) {
                String type = msg.get("type").getAsString();
                System.out.println("[EVENT] " + type + ": " + msg);
                if ("session.created".equals(type)) {
                    result.set("SUCCESS");
                    latch.countDown();
                }
            }
            @Override
            public void onClose(int code, String reason) {
                System.out.println("[CLOSE] code=" + code + ", reason=" + reason);
                if (!"SUCCESS".equals(result.get())) {
                    result.set("CLOSED: " + reason);
                }
                latch.countDown();
            }
        });

        try {
            conv.connect();
        } catch (NoApiKeyException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            System.err.println("ERROR: Interrupted during connect: " + e.getMessage());
            System.exit(1);
        }

        // Configure session
        OmniRealtimeTranscriptionParam tp = new OmniRealtimeTranscriptionParam();
        tp.setLanguage("zh");
        tp.setInputAudioFormat("pcm");
        tp.setInputSampleRate(16000);
        OmniRealtimeConfig config = OmniRealtimeConfig.builder()
                .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
                .transcriptionConfig(tp)
                .build();
        conv.updateSession(config);

        try {
            boolean done = latch.await(10, TimeUnit.SECONDS);
            if (!done) {
                System.err.println("[FAIL] Timed out waiting for session.created");
            } else {
                System.out.println("[RESULT] " + result.get());
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.exit(0);
    }
}