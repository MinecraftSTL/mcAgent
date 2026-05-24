package space.minecraftstl.mcagent.client.stt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.minecraftstl.mcagent.client.config.McagentConfig;

import javax.net.ssl.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Qwen Realtime ASR using raw OkHttp WebSocket client.
 * Bypasses DashScope SDK's OmniRealtimeConversation to avoid class-loading
 * conflicts in the Minecraft runtime environment.
 */
public class QwenRealtimeAsr {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcagent");
    private static final int AUDIO_CHUNK_SIZE = 3200; // 100ms of 16kHz 16-bit mono PCM
    private static final int TIMEOUT_SECONDS = 30;

    public static String transcribeBlocking(byte[] audioData, McagentConfig cfg) {
        try {
            String model = cfg.sttModel;
            String apiKey = cfg.sttApiKey;
            // 拼接 model 到 URL 查询参数（官方协议要求）
            String baseUrl = cfg.sttApiUrl;
            String wsUrl = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "model=" + model;
            LOGGER.info("[mcAgent] ASR raw WS: url={}, model={}, audioSize={}", wsUrl, model, audioData.length);

            // 配置 SSL（确保在 Fabric Knot 类加载器环境下正常 TLS 握手）
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());

            OkHttpClient client = new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) new X509TrustManager() {
                        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    })
                    .hostnameVerifier((hostname, session) -> true)
                    .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build();

            Request request = new Request.Builder()
                    .url(wsUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-DashScope-OmniRTC", "v1")
                    .build();

            CountDownLatch sessionLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(1);
            AtomicReference<String> resultText = new AtomicReference<>("");
            AtomicReference<WebSocket> wsRef = new AtomicReference<>();
            StringBuilder transcript = new StringBuilder();

            WebSocketListener listener = new WebSocketListener() {
                @Override
                public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                    LOGGER.info("[mcAgent] ASR WSS connected, status={}", response.code());
                    wsRef.set(webSocket);

                    // Send session.update to configure transcription
                    JsonObject sessionUpdate = new JsonObject();
                    sessionUpdate.addProperty("type", "session.update");

                    JsonObject session = new JsonObject();

                    JsonArray modalities = new JsonArray();
                    modalities.add("text");
                    session.add("modalities", modalities);

                    JsonObject inputAudioTranscription = new JsonObject();
                    inputAudioTranscription.addProperty("language", "zh");
                    inputAudioTranscription.addProperty("model", model);
                    session.add("input_audio_transcription", inputAudioTranscription);

                    JsonObject inputAudioFormat = new JsonObject();
                    inputAudioFormat.addProperty("type", "pcm");
                    inputAudioFormat.addProperty("sample_rate", 16000);
                    inputAudioFormat.addProperty("sample_size_in_bits", 16);
                    inputAudioFormat.addProperty("channels", 1);
                    session.add("input_audio", inputAudioFormat);

                    sessionUpdate.add("session", session);

                    String msg = sessionUpdate.toString();
                    LOGGER.debug("[mcAgent] ASR sending session.update: {}", msg);
                    webSocket.send(msg);
                }

                @Override
                public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                    try {
                        JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
                        String type = msg.has("type") ? msg.get("type").getAsString() : "unknown";
                        LOGGER.debug("[mcAgent] ASR event: {}", type);

                        switch (type) {
                            case "session.created":
                            case "session.updated":
                                LOGGER.info("[mcAgent] ASR session ready: {}", type);
                                sessionLatch.countDown();
                                break;

                            case "conversation.item.input_audio_transcription.completed":
                                if (msg.has("transcript")) {
                                    String t = msg.get("transcript").getAsString();
                                    transcript.append(t);
                                    LOGGER.info("[mcAgent] ASR transcript chunk: {}", t);
                                }
                                break;

                            case "response.audio_transcript.delta":
                                if (msg.has("delta")) {
                                    transcript.append(msg.get("delta").getAsString());
                                }
                                break;

                            case "session.finished":
                                LOGGER.info("[mcAgent] ASR session.finished");
                                resultText.set(transcript.toString());
                                finishLatch.countDown();
                                break;

                            case "response.done":
                                LOGGER.info("[mcAgent] ASR response.done");
                                if (transcript.length() > 0) {
                                    resultText.set(transcript.toString());
                                }
                                finishLatch.countDown();
                                break;

                            case "error":
                                String errMsg = msg.has("error") ? msg.get("error").toString() : text;
                                LOGGER.error("[mcAgent] ASR error: {}", errMsg);
                                finishLatch.countDown();
                                break;

                            default:
                                LOGGER.debug("[mcAgent] ASR unhandled event: {} -> {}", type, text);
                                break;
                        }
                    } catch (Exception e) {
                        LOGGER.error("[mcAgent] ASR message parse error: {}", e.getMessage());
                    }
                }

                @Override
                public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
                    LOGGER.error("[mcAgent] ASR WebSocket failure: {}", t.getMessage(), t);
                    sessionLatch.countDown();
                    finishLatch.countDown();
                }

                @Override
                public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                    LOGGER.info("[mcAgent] ASR WebSocket closed: code={}, reason={}", code, reason);
                    if (transcript.length() > 0) {
                        resultText.set(transcript.toString());
                    }
                    finishLatch.countDown();
                }
            };

            client.newWebSocket(request, listener);

            // Wait for session to be established
            if (!sessionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.error("[mcAgent] ASR timeout waiting for session");
                return "";
            }

            WebSocket ws = wsRef.get();
            if (ws == null) {
                LOGGER.error("[mcAgent] ASR WebSocket is null after session");
                return "";
            }

            // Send audio data in chunks as input_audio_buffer.append
            for (int offset = 0; offset < audioData.length; offset += AUDIO_CHUNK_SIZE) {
                int end = Math.min(offset + AUDIO_CHUNK_SIZE, audioData.length);
                byte[] chunk = new byte[end - offset];
                System.arraycopy(audioData, offset, chunk, 0, chunk.length);

                String b64 = Base64.getEncoder().encodeToString(chunk);

                JsonObject audioAppend = new JsonObject();
                audioAppend.addProperty("type", "input_audio_buffer.append");
                audioAppend.addProperty("audio", b64);
                ws.send(audioAppend.toString());

                // Small delay to avoid overwhelming the server
                Thread.sleep(10);
            }

            // Signal end of audio input
            JsonObject audioCommit = new JsonObject();
            audioCommit.addProperty("type", "input_audio_buffer.commit");
            ws.send(audioCommit.toString());
            LOGGER.info("[mcAgent] ASR audio committed, waiting for transcription...");

            // Wait for transcription result
            if (!finishLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("[mcAgent] ASR timeout waiting for transcription result");
            }

            // Close WebSocket
            ws.close(1000, "done");
            client.dispatcher().executorService().shutdown();

            String result = resultText.get();
            LOGGER.info("[mcAgent] ASR final result: '{}'", result);
            return result;

        } catch (Exception e) {
            LOGGER.error("[mcAgent] ASR transcribeBlocking error", e);
            return "";
        }
    }
}