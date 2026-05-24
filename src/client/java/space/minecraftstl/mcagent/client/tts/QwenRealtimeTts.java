package space.minecraftstl.mcagent.client.tts;

import com.alibaba.dashscope.audio.qwen_tts_realtime.*;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.minecraftstl.mcagent.client.config.McagentConfig;

import javax.sound.sampled.*;
import java.util.Base64;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 使用 DashScope SDK 的 QwenTtsRealtime WSS 协议进行实时 TTS。
 * 与 Qwen 官方 realtime 例程一致。
 */
public class QwenRealtimeTts {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcagent");
    private static final QwenTtsRealtimeAudioFormat TTS_FORMAT = QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT;

    public static void speakBlocking(String text, McagentConfig cfg) throws Exception {
        String model = cfg.ttsModel;
        String apiKey = cfg.ttsApiKey;
        String wsUrl = cfg.ttsApiUrl;
        String voiceName = (cfg.ttsVoice != null && !cfg.ttsVoice.isBlank()) ? cfg.ttsVoice : "Cherry";
        LOGGER.info("[mcAgent] TTS WSS: model={}, voice={}, len={}", model, voiceName, text.length());

        QwenTtsRealtimeParam param = QwenTtsRealtimeParam.builder()
                .model(model).url(wsUrl).apikey(apiKey).build();

        CountDownLatch responseDoneLatch = new CountDownLatch(1);
        CountDownLatch sessionFinishLatch = new CountDownLatch(1);
        RealtimePcmPlayer audioPlayer = new RealtimePcmPlayer(24000);
        final AtomicReference<QwenTtsRealtime> ref = new AtomicReference<>(null);

        QwenTtsRealtime tts = new QwenTtsRealtime(param, new QwenTtsRealtimeCallback() {
            @Override public void onOpen() { LOGGER.info("[mcAgent] TTS WSS connected"); }
            @Override public void onEvent(JsonObject msg) {
                String type = msg.get("type").getAsString();
                switch (type) {
                    case "session.created": break;
                    case "response.audio.delta":
                        audioPlayer.write(msg.get("delta").getAsString()); break;
                    case "response.done":
                        responseDoneLatch.countDown(); break;
                    case "session.finished":
                        sessionFinishLatch.countDown(); break;
                    default: break;
                }
            }
            @Override public void onClose(int code, String reason) {
                responseDoneLatch.countDown();
                sessionFinishLatch.countDown();
            }
        });
        ref.set(tts);
        try { tts.connect(); } catch (NoApiKeyException e) { throw new RuntimeException(e); }

        QwenTtsRealtimeConfig config = QwenTtsRealtimeConfig.builder()
                .voice(voiceName).responseFormat(TTS_FORMAT).mode("commit").build();
        tts.updateSession(config);
        // commit 模式：appendText + commit 触发响应生成
        tts.appendText(text);
        tts.commit();
        // 等待 response.done（音频生成完毕）
        responseDoneLatch.await();
        // 结束会话
        tts.finish();
        sessionFinishLatch.await();
        // 等待音频播放完成
        audioPlayer.waitForComplete();
        audioPlayer.shutdown();
    }

    private static class RealtimePcmPlayer {
        private final int sampleRate;
        private final SourceDataLine line;
        private final Thread decoderThread;
        private final Thread playerThread;
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private final Queue<String> b64Buf = new ConcurrentLinkedQueue<>();
        private final Queue<byte[]> rawBuf = new ConcurrentLinkedQueue<>();

        RealtimePcmPlayer(int sampleRate) throws LineUnavailableException {
            this.sampleRate = sampleRate;
            AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(fmt); line.start();
            decoderThread = new Thread(() -> {
                while (!stopped.get()) {
                    String b = b64Buf.poll();
                    if (b != null) rawBuf.add(Base64.getDecoder().decode(b));
                    else try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                }
            });
            playerThread = new Thread(() -> {
                while (!stopped.get()) {
                    byte[] raw = rawBuf.poll();
                    if (raw != null) { int w=0; while(w<raw.length) w+=line.write(raw,w,raw.length-w);
                        try{Thread.sleep(Math.max(0,raw.length/(sampleRate*2/1000)-10));}catch(InterruptedException ignored){}
                    } else try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                }
            });
            decoderThread.start(); playerThread.start();
        }
        void write(String b64) { b64Buf.add(b64); }
        void waitForComplete() throws InterruptedException {
            while (!b64Buf.isEmpty()||!rawBuf.isEmpty()) Thread.sleep(50);
            line.drain();
        }
        void shutdown() throws InterruptedException {
            stopped.set(true); decoderThread.join(); playerThread.join();
            if (line!=null&&line.isRunning()){line.drain();line.close();}
        }
    }
}