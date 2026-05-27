package space.minecraftstl.mcagent.client.tts;

import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.FloatControl;
import javax.sound.sampled.Line;

/**
 * 从 Minecraft 的声音设置中读取 VOICE（语音）和 MASTER（主音量）的音量值，
 * 并将其应用到 javax.sound.sampled 的音频输出上。
 */
public class TtsVolumeHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcagent");

    /**
     * 获取 Minecraft VOICE 通道的有效音量（MASTER × VOICE），范围 0.0 ~ 1.0。
     */
    public static float getVoiceVolume() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.options == null) return 1.0f;
            float master = mc.options.getSoundVolume(SoundCategory.MASTER);
            float voice = mc.options.getSoundVolume(SoundCategory.VOICE);
            return master * voice;
        } catch (Exception e) {
            LOGGER.warn("[mcAgent] 无法读取Minecraft音量设置，使用默认音量", e);
            return 1.0f;
        }
    }

    /**
     * 将 Minecraft 的 VOICE 音量应用到 javax.sound.sampled 的 Line 上。
     * 支持 Clip 和 SourceDataLine。
     *
     * @param line 音频输出线路
     */
    public static void applyVoiceVolume(Line line) {
        float volume = getVoiceVolume();
        applyVolume(line, volume);
    }

    /**
     * 将指定音量（0.0~1.0）应用到 javax.sound.sampled 的 Line 上。
     *
     * @param line   音频输出线路
     * @param volume 音量，范围 0.0 ~ 1.0
     */
    public static void applyVolume(Line line, float volume) {
        try {
            if (line == null || !line.isOpen()) return;

            // 优先使用 MASTER_GAIN（dB 增益）
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gainControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                if (volume <= 0.0f) {
                    gainControl.setValue(gainControl.getMinimum());
                } else {
                    // 将线性音量转换为 dB：dB = 20 * log10(volume)
                    float dB = (float) (20.0 * Math.log10(volume));
                    // 限制在控件允许的范围内
                    dB = Math.max(dB, gainControl.getMinimum());
                    dB = Math.min(dB, gainControl.getMaximum());
                    gainControl.setValue(dB);
                }
                LOGGER.debug("[mcAgent] TTS音量已设置: volume={}, dB={}", volume,
                        volume <= 0 ? "MIN" : String.format("%.1f", 20.0 * Math.log10(volume)));
            }
            // 备选：使用 VOLUME 控件（线性 0.0~1.0）
            else if (line.isControlSupported(FloatControl.Type.VOLUME)) {
                FloatControl volumeControl = (FloatControl) line.getControl(FloatControl.Type.VOLUME);
                float val = Math.max(volumeControl.getMinimum(), Math.min(volume, volumeControl.getMaximum()));
                volumeControl.setValue(val);
                LOGGER.debug("[mcAgent] TTS音量已设置(VOLUME控件): {}", val);
            } else {
                LOGGER.debug("[mcAgent] 音频线路不支持音量控制");
            }
        } catch (Exception e) {
            LOGGER.warn("[mcAgent] 设置TTS音量失败", e);
        }
    }
}