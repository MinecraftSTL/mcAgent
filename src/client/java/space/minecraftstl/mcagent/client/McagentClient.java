package space.minecraftstl.mcagent.client;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.minecraftstl.mcagent.client.audio.AudioRecorder;
import space.minecraftstl.mcagent.client.config.McagentConfig;
import space.minecraftstl.mcagent.client.llm.LlmClient;
import space.minecraftstl.mcagent.client.llm.ToolHandler;
import space.minecraftstl.mcagent.client.stt.SpeechToText;
import space.minecraftstl.mcagent.client.ui.HudRenderer;

public class McagentClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcagent");
    private KeyBinding voiceKey;
    private final AudioRecorder recorder = new AudioRecorder();
    private final LlmClient llmClient = new LlmClient();
    private final ToolHandler toolHandler = new ToolHandler(llmClient);
    private final HudRenderer hudRenderer = new HudRenderer();

    // talk state
    private boolean pttPressed = false;
    private long lastStopTime = 0;
    private long pttPressStart = 0;

    // toggle state (auto-listen with silence detection, press to start/stop)
    private boolean toggleActive = false;

    // send state (background recording always on)
    private boolean pushToSendInitialized = false;

    // track active mode to detect switches
    private String activeMode = "";

    // pause state for ESC menu
    private boolean pausedByScreen = false;

    @Override
    public void onInitializeClient() {
        McagentConfig.load();

        voiceKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mcagent.voice", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.category.mcagent.mcagent"
        ));

        HudRenderCallback.EVENT.register(hudRenderer);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("mcagent")
                    .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String text = StringArgumentType.getString(ctx, "message");
                                Thread.startVirtualThread(() -> {
                                    if (!llmClient.tryStartProcessing()) {
                                        llmClient.queueUserMessage(text);
                                        return;
                                    }
                                    hudRenderer.setStatus("⟳ AI思考中...");
                                    try {
                                        JsonObject response = llmClient.sendText(text);
                                        toolHandler.processResponse(response);
                                    } catch (Exception e) {
                                        sendClientMessage("§c[mcAgent] 错误: " + e.getMessage());
                                    } finally {
                                        llmClient.setProcessing(false);
                                        hudRenderer.setStatus(null);
                                    }
                                });
                                return 1;
                            })));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            McagentConfig cfg = McagentConfig.get();
            String mode = cfg.recordingMode;

            // Handle screen open
            if (client.currentScreen != null) {
                if (isExitScreen(client.currentScreen)) {
                    // 主菜单、断开连接等 → 完全停止录音
                    if (toggleActive && recorder.isRecording()) {
                        toggleActive = false;
                        recorder.stopRecording(); // discard
                        hudRenderer.setStatus(null);
                    }
                    if (pttPressed && recorder.isRecording()) {
                        recorder.stopRecording(); // discard
                    }
                    if (recorder.isBackgroundRecording()) {
                        recorder.stopBackgroundRecording();
                        pushToSendInitialized = false;
                    }
                    pausedByScreen = false;
                    pttPressed = false;
                    return;
                }
                if (isGameMenuScreen(client.currentScreen)) {
                    // ESC菜单 → 暂停录音（toggle/send），停止录音（talk）
                    if (!pausedByScreen) {
                        if ("talk".equals(mode)) {
                            // PTT模式：停止录音并丢弃
                            if (pttPressed && recorder.isRecording()) {
                                recorder.stopRecording(); // discard
                            }
                        } else {
                            // toggle / send 模式：暂停录音
                            if (recorder.isRecording() || recorder.isBackgroundRecording()) {
                                recorder.pauseRecording();
                                hudRenderer.setStatus("⏸ 已暂停");
                            }
                        }
                        pausedByScreen = true;
                    }
                    pttPressed = false;
                    return;
                }
                // 游戏内界面（物品栏、聊天等）→ V键不生效，但toggle/send继续工作
                if ("toggle".equals(mode) && toggleActive) {
                    handleToggleAutoRestart();
                }
                if ("send".equals(mode) && !recorder.isBackgroundRecording()) {
                    recorder.startBackgroundRecording();
                    hudRenderer.setStatus("◉ 监听中");
                }
                pttPressed = false;
                return;
            }

            // Returned from ESC menu - resume if paused
            if (pausedByScreen) {
                pausedByScreen = false;
                if (recorder.isPaused()) {
                    recorder.resumeRecording();
                    if ("toggle".equals(mode) && toggleActive) {
                        hudRenderer.setStatus("● 录音中...");
                    } else if ("send".equals(mode) && recorder.isBackgroundRecording()) {
                        hudRenderer.setStatus("◉ 监听中");
                    }
                }
            }

            if (!mode.equals(activeMode)) {
                cleanupPreviousMode();
                activeMode = mode;
            }
            switch (mode) {
                case "talk" -> handlePushToTalk();
                case "send" -> handlePushToSend();
                case "toggle" -> handleToggle();
            }
        });
    }

    private void cleanupPreviousMode() {
        // Stop any active recording from previous mode
        if (recorder.isRecording()) {
            recorder.stopRecording(); // discard
        }
        if (recorder.isBackgroundRecording()) {
            recorder.stopBackgroundRecording();
        }
        toggleActive = false;
        pttPressed = false;
        hudRenderer.setStatus(null);
        LOGGER.info("[mcAgent] 模式切换，已清理前一模式状态");
    }

    private void handlePushToTalk() {

        long handle = MinecraftClient.getInstance().getWindow().getHandle();
        boolean pressed = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_V) == GLFW.GLFW_PRESS;
        if (pressed && !pttPressed) {
            if (System.currentTimeMillis() - lastStopTime > 500) {
                pttPressStart = System.currentTimeMillis();
                startRecording();
            }
        } else if (!pressed && pttPressed) {
            long held = System.currentTimeMillis() - pttPressStart;
            if (held >= McagentConfig.get().minSpeechMs) {
                stopAndProcessPtt();
            } else {
                // Too short press, discard
                recorder.stopRecording();
                hudRenderer.setStatus(null);
            }
            lastStopTime = System.currentTimeMillis();
        }
        pttPressed = pressed;
    }

    private void handlePushToSend() {
        // Ensure background recording is running (not normal recording)
        if (!recorder.isBackgroundRecording()) {
            recorder.startBackgroundRecording();
            pushToSendInitialized = true;
            hudRenderer.setStatus("◉ 监听中");
            LOGGER.info("[mcAgent] send: 后台录音已启动");
        }

        long handle = MinecraftClient.getInstance().getWindow().getHandle();
        boolean pressed = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_V) == GLFW.GLFW_PRESS;
        if (pressed && !pttPressed) {
            // Key just pressed - extract last speech and process
            LOGGER.info("[mcAgent] send: 按键触发，提取最后一段语音");
            byte[] wav = recorder.extractLastSpeech();
            processAudio(wav);
        }
        pttPressed = pressed;
    }

    private void handleToggle() {
        long handle = MinecraftClient.getInstance().getWindow().getHandle();
        boolean pressed = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_V) == GLFW.GLFW_PRESS;

        if (pressed && !pttPressed) {
            // Key just pressed - toggle listening
            if (toggleActive) {
                // Stop listening
                toggleActive = false;
                if (recorder.isRecording()) {
                    stopAndProcess();
                }
                hudRenderer.setStatus(null);
                LOGGER.info("[mcAgent] toggle: 停止监听");
            } else {
                // Start listening
                toggleActive = true;
                startRecording();
                LOGGER.info("[mcAgent] toggle: 开始监听");
            }
        }
        pttPressed = pressed;

        // Auto-restart after silence stop (if still active)
        if (toggleActive && !recorder.isRecording()) {
            // Recording stopped (silence detection) - process and restart
            stopAndProcess();
            if (toggleActive) {
                startRecording();
            }
        }
    }

    private void startRecording() {
        LOGGER.info("[mcAgent] 开始录音");
        recorder.startRecording();
        if (recorder.isRecording()) {
            hudRenderer.setStatus("● 录音中...");
        } else {
            LOGGER.warn("[mcAgent] 录音启动失败，可能没有可用麦克风");
            sendClientMessage("§c[mcAgent] 录音启动失败，请检查麦克风");
            toggleActive = false;
        }
    }

    private void stopAndProcess() {
        LOGGER.info("[mcAgent] 停止录音，开始处理");
        byte[] wav = recorder.stopRecording();
        processAudio(wav);
    }

    private void stopAndProcessPtt() {
        LOGGER.info("[mcAgent] PTT停止录音，按键时长足够，直接发送");
        byte[] wav = recorder.stopRecordingRaw();
        processAudio(wav);
    }

    private void processAudio(byte[] wav) {
        if (wav == null) {
            LOGGER.warn("[mcAgent] 录音数据为空（可能太短或全是静音）");
            // Don't show error for toggle mode auto-restarts
            if (!toggleActive && !recorder.isBackgroundRecording()) {
                sendClientMessage("§c[mcAgent] 录音太短，请重试");
            }
            hudRenderer.setStatus(toggleActive ? "● 录音中..." : (recorder.isBackgroundRecording() ? "◉ 监听中" : null));
            return;
        }
        LOGGER.info("[mcAgent] 录音数据 {} 字节，发送处理", wav.length);
        hudRenderer.setStatus("⟳ 处理中...");

        // Capture player context on the tick thread (before entering virtual thread)
        // so it reflects the player's state at the moment of voice key action
        String playerContext = llmClient.getPlayerContext();

        Thread.startVirtualThread(() -> {
            McagentConfig cfg = McagentConfig.get();

            if (!llmClient.tryStartProcessing()) {
                if ("direct_audio".equals(cfg.voiceMode)) {
                    llmClient.queueAudio(wav, playerContext);
                    LOGGER.info("[mcAgent] AI处理中，音频已排队");
                } else {
                    try {
                        String text = SpeechToText.transcribe(wav);
                        LOGGER.info("[mcAgent] AI处理中，消息已排队: {}", text);
                        if (text != null && !text.isBlank()) {
                            llmClient.queueSttMessage(text, playerContext);
                        }
                    } catch (Exception e) {
                        LOGGER.error("[mcAgent] STT排队出错", e);
                    }
                }
                hudRenderer.setStatus(toggleActive ? "● 录音中..." : (recorder.isBackgroundRecording() ? "◉ 监听中" : null));
                return;
            }
            try {
                JsonObject response;
                if ("direct_audio".equals(cfg.voiceMode)) {
                    LOGGER.info("[mcAgent] 直接发送音频给LLM");
                    response = llmClient.sendAudio(wav, playerContext);
                } else {
                    String text = SpeechToText.transcribe(wav);
                    LOGGER.info("[mcAgent] STT结果: {}", text);
                    if (text == null || text.isBlank()) {
                        LOGGER.warn("[mcAgent] STT结果为空，跳过LLM调用");
                        return;
                    }
                    hudRenderer.setStatus("⟳ AI思考中...");
                    response = llmClient.sendTextFromStt(text, playerContext);
                }
                LOGGER.info("[mcAgent] LLM响应已收到");
                toolHandler.processResponse(response);
            } catch (Exception e) {
                LOGGER.error("[mcAgent] 处理出错", e);
                sendClientMessage("§c[mcAgent] 错误: " + e.getMessage());
            } finally {
                llmClient.setProcessing(false);
                hudRenderer.setStatus(toggleActive ? "● 录音中..." : (recorder.isBackgroundRecording() ? "◉ 监听中" : null));
            }
        });
    }

    /**
     * 判断是否为ESC游戏菜单界面，这些界面下应暂停录音（toggle/send模式）。
     */
    private boolean isGameMenuScreen(Screen screen) {
        return screen instanceof GameMenuScreen;
    }

    /**
     * 判断是否为退出类界面（主菜单、断开连接等），这些界面下应完全停止录音。
     */
    private boolean isExitScreen(Screen screen) {
        return screen instanceof TitleScreen
                || screen instanceof DisconnectedScreen
                || screen instanceof MultiplayerScreen;
    }

    /**
     * Toggle模式下在游戏内界面（物品栏、聊天等）时的自动重启逻辑。
     * 只处理静音检测后的自动重启，不检测V键。
     */
    private void handleToggleAutoRestart() {
        if (!recorder.isRecording()) {
            // Recording stopped (silence detection) - process and restart
            stopAndProcess();
            if (toggleActive) {
                startRecording();
            }
        }
    }

    private void sendClientMessage(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.execute(() -> mc.player.sendMessage(Text.literal(msg), false));
        }
    }

}
