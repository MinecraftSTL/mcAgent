package space.minecraftstl.mcagent.client.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import space.minecraftstl.mcagent.client.config.McagentConfig;

import java.util.ArrayList;
import java.util.List;

public class ConfigScreen extends Screen {
    private final Screen parent;
    private final List<ConfigEntry> entries = new ArrayList<>();
    private int scrollOffset = 0;
    private int contentHeight = 0;
    private ButtonWidget saveBtn;
    private ButtonWidget cancelBtn;

    private record ConfigEntry(String label, Object widget, int baseY) {}

    private static class CycleButton {
        private final String[] options;
        private int index;
        final ButtonWidget button;

        CycleButton(String label, String[] options, String current, int x, int y, int w) {
            this.options = options;
            this.index = 0;
            for (int i = 0; i < options.length; i++) {
                if (options[i].equals(current)) { this.index = i; break; }
            }
            this.button = ButtonWidget.builder(Text.literal(label + ": " + options[index]), b -> cycle(label))
                    .dimensions(x, y, w, 20).build();
        }

        private void cycle(String label) {
            index = (index + 1) % options.length;
            button.setMessage(Text.literal(label + ": " + options[index]));
        }

        String getValue() { return options[index]; }
    }

    public ConfigScreen(Screen parent) {
        super(Text.literal("mcAgent 配置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        entries.clear();
        scrollOffset = 0;
        McagentConfig cfg = McagentConfig.get();
        int w = Math.min(this.width - 40, 360);
        int x = (this.width - w) / 2;
        int y = 25;
        addCycleEntry("录音模式", new String[]{"push_to_talk", "push_to_send", "toggle"}, cfg.recordingMode, x, y, w); y += 24;
        addCycleEntry("语音模式", new String[]{"stt_then_llm", "direct_audio"}, cfg.voiceMode, x, y, w); y += 24;
        addCycleEntry("STT类型", new String[]{"online", "local"}, cfg.sttType, x, y, w); y += 24;
        addTextEntry("STT API地址", cfg.sttApiUrl, x, y, w); y += 24;
        addTextEntry("STT API Key", cfg.sttApiKey, x, y, w); y += 24;
        addTextEntry("STT模型", cfg.sttModel, x, y, w); y += 24;
        addTextEntry("本地Whisper路径", cfg.sttLocalPath, x, y, w); y += 24;
        addTextEntry("静音阈值", String.valueOf(cfg.silenceThreshold), x, y, w); y += 24;
        addTextEntry("最大静音(ms)", String.valueOf(cfg.maxSilenceMs), x, y, w); y += 24;
        addTextEntry("最小语音(ms)", String.valueOf(cfg.minSpeechMs), x, y, w); y += 24;
        addTextEntry("LLM API地址", cfg.llmApiUrl, x, y, w); y += 24;
        addTextEntry("LLM API Key", cfg.llmApiKey, x, y, w); y += 24;
        addTextEntry("LLM模型", cfg.llmModel, x, y, w); y += 24;
        addTextEntry("最大上下文轮数", String.valueOf(cfg.maxContextRounds), x, y, w); y += 24;
        addTextEntry("系统提示词", cfg.systemPrompt, x, y, w); y += 24;
        addCycleEntry("TTS开关", new String[]{"关闭", "开启"}, cfg.ttsEnabled ? "开启" : "关闭", x, y, w); y += 24;
        addTextEntry("TTS API地址", cfg.ttsApiUrl, x, y, w); y += 24;
        addTextEntry("TTS API Key", cfg.ttsApiKey, x, y, w); y += 24;
        addTextEntry("TTS模型", cfg.ttsModel, x, y, w); y += 24;
        addTextEntry("TTS语音角色", cfg.ttsVoice, x, y, w); y += 24;
        contentHeight = y + 10;
        int btnY = this.height - 28;
        saveBtn = ButtonWidget.builder(Text.literal("保存"), b -> save()).dimensions(this.width / 2 - 104, btnY, 100, 20).build();
        cancelBtn = ButtonWidget.builder(Text.literal("取消"), b -> close()).dimensions(this.width / 2 + 4, btnY, 100, 20).build();
        this.addDrawableChild(saveBtn);
        this.addDrawableChild(cancelBtn);
        applyScroll();
    }

    private void addCycleEntry(String label, String[] options, String current, int x, int y, int w) {
        CycleButton cb = new CycleButton(label, options, current, x, y, w);
        this.addDrawableChild(cb.button);
        entries.add(new ConfigEntry(label, cb, y));
    }

    private void addTextEntry(String label, String value, int x, int y, int w) {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y, w, 18, Text.literal(label));
        field.setMaxLength(512);
        field.setText(value);
        this.addDrawableChild(field);
        entries.add(new ConfigEntry(label, field, y));
    }
    private void applyScroll() {
        for (ConfigEntry entry : entries) {
            int newY = entry.baseY - scrollOffset;
            if (entry.widget instanceof CycleButton cb) {
                cb.button.setY(newY);
                cb.button.visible = newY >= 10 && newY < this.height - 40;
            } else if (entry.widget instanceof TextFieldWidget field) {
                field.setY(newY);
                field.visible = newY >= 10 && newY < this.height - 40;
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, contentHeight - (this.height - 50));
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int)(verticalAmount * 16)));
        applyScroll();
        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        for (ConfigEntry entry : entries) {
            if (entry.widget instanceof TextFieldWidget field) {
                if (!field.visible) continue;
                int fy = field.getY() + 5;
                int labelX = field.getX() - 5 - this.textRenderer.getWidth(entry.label);
                if (labelX < 2) labelX = 2;
                context.drawText(this.textRenderer, entry.label, labelX, fy, 0xFFAAAAAA, false);
            }
        }
    }
    private void save() {
        McagentConfig cfg = McagentConfig.get();
        int i = 0;
        cfg.recordingMode = ((CycleButton) entries.get(i++).widget).getValue();
        cfg.voiceMode = ((CycleButton) entries.get(i++).widget).getValue();
        cfg.sttType = ((CycleButton) entries.get(i++).widget).getValue();
        cfg.sttApiUrl = ((TextFieldWidget) entries.get(i++).widget).getText();
        cfg.sttApiKey = ((TextFieldWidget) entries.get(i++).widget).getText();
        cfg.sttModel = ((TextFieldWidget) entries.get(i++).widget).getText();
        cfg.sttLocalPath = ((TextFieldWidget) entries.get(i++).widget).getText();
        try { cfg.silenceThreshold = Integer.parseInt(((TextFieldWidget) entries.get(i).widget).getText()); } catch (NumberFormatException ignored) {}
        i++;
        try { cfg.maxSilenceMs = Integer.parseInt(((TextFieldWidget) entries.get(i).widget).getText()); } catch (NumberFormatException ignored) {}
        i++;
        try { cfg.minSpeechMs = Integer.parseInt(((TextFieldWidget) entries.get(i).widget).getText()); } catch (NumberFormatException ignored) {}
        i++;
        cfg.llmApiUrl = ((TextFieldWidget) entries.get(i++).widget).getText();
        cfg.llmApiKey = ((TextFieldWidget) entries.get(i++).widget).getText();
        cfg.llmModel = ((TextFieldWidget) entries.get(i++).widget).getText();
        try { cfg.maxContextRounds = Integer.parseInt(((TextFieldWidget) entries.get(i).widget).getText()); } catch (NumberFormatException ignored) {}
        i++;
        cfg.systemPrompt = ((TextFieldWidget) entries.get(i++).widget).getText();
        cfg.ttsEnabled = "开启".equals(((CycleButton) entries.get(i++).widget).getValue());
        cfg.ttsApiUrl = ((TextFieldWidget) entries.get(i++).widget).getText();
        cfg.ttsApiKey = ((TextFieldWidget) entries.get(i++).widget).getText();
        cfg.ttsModel = ((TextFieldWidget) entries.get(i++).widget).getText();
        cfg.ttsVoice = ((TextFieldWidget) entries.get(i++).widget).getText();
        McagentConfig.save();
        close();
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
