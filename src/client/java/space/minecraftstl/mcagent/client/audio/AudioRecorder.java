package space.minecraftstl.mcagent.client.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.minecraftstl.mcagent.client.config.McagentConfig;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class AudioRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcagent");
    private static final AudioFormat FORMAT = new AudioFormat(16000, 16, 1, true, false);
    private static final int CHUNK_SIZE = 3200; // 100ms at 16kHz 16bit mono
    private static final int RING_BUFFER_SECONDS = 30;
    private static final int RING_BUFFER_SIZE = 16000 * 2 * RING_BUFFER_SECONDS; // 30s

    private TargetDataLine line;
    private volatile boolean recording = false;
    private volatile boolean paused = false;
    private ByteArrayOutputStream buffer;
    private Thread recordThread;

    // Ring buffer for send mode
    private volatile boolean backgroundRecording = false;
    private byte[] ringBuffer;
    private volatile int ringWritePos = 0;
    private volatile int ringDataLen = 0; // how much valid data in ring buffer
    private Thread backgroundThread;

    public boolean isRecording() {
        return recording;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isBackgroundRecording() {
        return backgroundRecording;
    }

    /**
     * Pause recording/background recording. Data read from the audio line will be discarded while paused.
     */
    public void pauseRecording() {
        paused = true;
        LOGGER.info("[mcAgent] 录音已暂停");
    }

    /**
     * Resume recording/background recording after a pause.
     */
    public void resumeRecording() {
        paused = false;
        LOGGER.info("[mcAgent] 录音已恢复");
    }

    public void startRecording() {
        if (recording) return;
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                LOGGER.warn("[mcAgent] 音频线路不支持: {}", FORMAT);
                return;
            }
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(FORMAT);
            line.start();
            recording = true;
            buffer = new ByteArrayOutputStream();
            recordThread = new Thread(this::recordLoop, "mcagent-audio");
            recordThread.setDaemon(true);
            recordThread.start();
            LOGGER.info("[mcAgent] 录音线路已打开");
        } catch (LineUnavailableException e) {
            LOGGER.error("[mcAgent] 音频线路不可用", e);
        }
    }

    public byte[] stopRecording() {
        recording = false;
        if (line != null && line.isOpen()) {
            line.stop();
            line.close();
        }
        try {
            if (recordThread != null) recordThread.join(1000);
        } catch (InterruptedException ignored) {}
        if (buffer == null || buffer.size() == 0) return null;
        byte[] raw = buffer.toByteArray();
        buffer = null;
        raw = trimSilence(raw);
        McagentConfig cfg = McagentConfig.get();
        int minBytes = cfg.minSpeechMs * 32; // 16kHz * 2bytes * 1channel = 32 bytes/ms
        if (raw.length < minBytes) return null;
        return toWav(raw);
    }

    /** Stop recording and return WAV without minimum duration check (for PTT where key duration is the gate). */
    public byte[] stopRecordingRaw() {
        recording = false;
        if (line != null && line.isOpen()) {
            line.stop();
            line.close();
        }
        try {
            if (recordThread != null) recordThread.join(1000);
        } catch (InterruptedException ignored) {}
        if (buffer == null || buffer.size() == 0) return null;
        byte[] raw = buffer.toByteArray();
        buffer = null;
        raw = trimSilence(raw);
        if (raw.length < 1600) return null; // absolute minimum ~50ms
        return toWav(raw);
    }

    /**
     * Start background continuous recording into ring buffer (for send mode).
     */
    public void startBackgroundRecording() {
        if (backgroundRecording) return;
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                LOGGER.warn("[mcAgent] 音频线路不支持: {}", FORMAT);
                return;
            }
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(FORMAT);
            line.start();
            ringBuffer = new byte[RING_BUFFER_SIZE];
            ringWritePos = 0;
            ringDataLen = 0;
            backgroundRecording = true;
            backgroundThread = new Thread(this::backgroundLoop, "mcagent-bg-audio");
            backgroundThread.setDaemon(true);
            backgroundThread.start();
            LOGGER.info("[mcAgent] 后台录音已启动");
        } catch (LineUnavailableException e) {
            LOGGER.error("[mcAgent] 后台录音启动失败", e);
        }
    }

    public void stopBackgroundRecording() {
        backgroundRecording = false;
        if (line != null && line.isOpen()) {
            line.stop();
            line.close();
        }
        try {
            if (backgroundThread != null) backgroundThread.join(1000);
        } catch (InterruptedException ignored) {}
        ringBuffer = null;
        LOGGER.info("[mcAgent] 后台录音已停止");
    }

    /**
     * Extract the last speech segment from the ring buffer.
     * Scans backwards to skip trailing silence, then captures the continuous speech.
     */
    public byte[] extractLastSpeech() {
        if (ringBuffer == null || ringDataLen == 0) return null;
        McagentConfig cfg = McagentConfig.get();

        // Copy valid data from ring buffer linearly
        byte[] data;
        synchronized (this) {
            int len = Math.min(ringDataLen, RING_BUFFER_SIZE);
            data = new byte[len];
            if (ringDataLen >= RING_BUFFER_SIZE) {
                // Buffer is full, read from writePos to end, then start to writePos
                int firstPart = RING_BUFFER_SIZE - ringWritePos;
                System.arraycopy(ringBuffer, ringWritePos, data, 0, firstPart);
                System.arraycopy(ringBuffer, 0, data, firstPart, ringWritePos);
            } else {
                System.arraycopy(ringBuffer, 0, data, 0, len);
            }
        }

        // Scan from end: skip trailing silence, then find speech start
        int chunkCount = data.length / CHUNK_SIZE;
        if (chunkCount == 0) return null;

        // Find end of speech (skip trailing silence)
        int speechEnd = chunkCount;
        int maxSilentChunks = cfg.maxSilenceMs / 100;
        int trailingSilent = 0;
        for (int i = chunkCount - 1; i >= 0; i--) {
            if (isSilent(data, i * CHUNK_SIZE, CHUNK_SIZE, cfg.silenceThreshold)) {
                trailingSilent++;
                if (trailingSilent > maxSilentChunks) {
                    speechEnd = i + maxSilentChunks + 1;
                    break;
                }
            } else {
                break;
            }
        }

        // Find start of speech (scan backwards from speechEnd until silence gap)
        int speechStart = 0;
        int silentCount = 0;
        for (int i = speechEnd - 1; i >= 0; i--) {
            if (isSilent(data, i * CHUNK_SIZE, CHUNK_SIZE, cfg.silenceThreshold)) {
                silentCount++;
                if (silentCount >= maxSilentChunks) {
                    speechStart = i + maxSilentChunks;
                    break;
                }
            } else {
                silentCount = 0;
            }
        }

        int startByte = speechStart * CHUNK_SIZE;
        int endByte = Math.min(speechEnd * CHUNK_SIZE, data.length);
        int minBytes = cfg.minSpeechMs * 32;
        if (endByte - startByte < minBytes) return null; // too short

        byte[] speech = new byte[endByte - startByte];
        System.arraycopy(data, startByte, speech, 0, speech.length);
        return toWav(speech);
    }

    private void recordLoop() {
        McagentConfig cfg = McagentConfig.get();
        byte[] buf = new byte[CHUNK_SIZE];
        int silentChunks = 0;
        int maxSilentChunks = cfg.maxSilenceMs / 100;
        boolean autoStop = "toggle".equals(cfg.recordingMode);

        while (recording) {
            int read = line.read(buf, 0, buf.length);
            if (read > 0) {
                if (paused) {
                    // Discard data while paused, reset silence counter
                    silentChunks = 0;
                    continue;
                }
                if (autoStop && isSilent(buf, 0, read, cfg.silenceThreshold)) {
                    silentChunks++;
                    if (silentChunks >= maxSilentChunks) {
                        recording = false;
                        break;
                    }
                } else {
                    silentChunks = 0;
                }
                buffer.write(buf, 0, read);
            }
        }
    }

    private void backgroundLoop() {
        byte[] buf = new byte[CHUNK_SIZE];
        while (backgroundRecording) {
            int read = line.read(buf, 0, buf.length);
            if (read > 0) {
                if (paused) {
                    // Discard data while paused
                    continue;
                }
                synchronized (this) {
                    for (int i = 0; i < read; i++) {
                        ringBuffer[ringWritePos] = buf[i];
                        ringWritePos = (ringWritePos + 1) % RING_BUFFER_SIZE;
                    }
                    ringDataLen = Math.min(ringDataLen + read, RING_BUFFER_SIZE);
                }
            }
        }
    }

    private boolean isSilent(byte[] buf, int offset, int len, int threshold) {
        long sum = 0;
        for (int i = offset; i < offset + len - 1; i += 2) {
            short sample = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
            sum += Math.abs(sample);
        }
        return (sum / (len / 2)) < threshold;
    }

    private byte[] trimSilence(byte[] raw) {
        McagentConfig cfg = McagentConfig.get();
        int start = 0;
        // 只裁剪头部静音，保留尾部静音（供ASR识别语句结束）
        while (start + CHUNK_SIZE <= raw.length) {
            if (!isSilent(raw, start, CHUNK_SIZE, cfg.silenceThreshold)) break;
            start += CHUNK_SIZE;
        }
        if (start >= raw.length) return new byte[0];
        byte[] result = new byte[raw.length - start];
        System.arraycopy(raw, start, result, 0, raw.length - start);
        return result;
    }

    private byte[] toWav(byte[] pcm) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(pcm);
            AudioInputStream ais = new AudioInputStream(bais, FORMAT, pcm.length / FORMAT.getFrameSize());
            ByteArrayOutputStream wav = new ByteArrayOutputStream();
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wav);
            return wav.toByteArray();
        } catch (IOException e) {
            return pcm;
        }
    }
}