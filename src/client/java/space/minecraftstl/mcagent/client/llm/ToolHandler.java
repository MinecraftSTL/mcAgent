package space.minecraftstl.mcagent.client.llm;

import com.google.gson.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.minecraftstl.mcagent.client.command.CommandExecutor;
import space.minecraftstl.mcagent.client.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.List;

public class ToolHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcagent");
    private final LlmClient llmClient;
    private final CommandExecutor commandExecutor = new CommandExecutor();

    public record PendingToolCall(String id, String name, JsonObject arguments) {}

    public ToolHandler(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public void processResponse(JsonObject response) {
        processResponseInternal(response);
    }

    private void processResponseInternal(JsonObject response) {
        if (response.has("error")) {
            var err = response.get("error");
            String errMsg = err.isJsonPrimitive() ? err.getAsString() : err.toString();
            sendPlayerMessage("§c[AI] 错误: " + errMsg);
            return;
        }
        if (!response.has("choices")) return;

        JsonObject choice = response.getAsJsonArray("choices").get(0).getAsJsonObject();
        JsonObject message = choice.getAsJsonObject("message");
        String finishReason = "";
        if (choice.has("finish_reason") && choice.get("finish_reason").isJsonPrimitive()) {
            finishReason = choice.get("finish_reason").getAsString();
        }

        // handle content (text response)
        if (message.has("content") && !message.get("content").isJsonNull()) {
            var contentEl = message.get("content");
            String content = contentEl.isJsonPrimitive() ? contentEl.getAsString() : contentEl.toString();
            if (!content.isBlank()) {
                sendPlayerMessage("§b[AI]§r " + content);
                TextToSpeech.speak(content);
            }
        }

        // handle tool calls - batch all results together
        if ("tool_calls".equals(finishReason) || message.has("tool_calls")) {
            JsonArray toolCalls = message.getAsJsonArray("tool_calls");
            if (toolCalls != null) {
                List<ToolResult> results = new ArrayList<>();
                for (JsonElement tc : toolCalls) {
                    JsonObject call = tc.getAsJsonObject();
                    String id = call.get("id").getAsString();
                    JsonObject func = call.getAsJsonObject("function");
                    String name = func.get("name").getAsString();
                    JsonObject args = JsonParser.parseString(func.get("arguments").getAsString()).getAsJsonObject();
                    results.add(processToolCall(id, name, args));
                }
                // Send all tool results (+ any pending user message) in one request
                JsonObject resp = llmClient.sendToolResults(results);
                processResponseInternal(resp);
            }
        } else if (llmClient.hasPendingMessages()) {
            // Pure text response but there are queued messages - consume them
            JsonObject resp = llmClient.consumeQueue();
            processResponseInternal(resp);
        }
    }

    public record ToolResult(String toolCallId, String content) {}

    private ToolResult processToolCall(String id, String name, JsonObject args) {
        return switch (name) {
            case "execute_commands" -> {
                JsonArray cmds = args.getAsJsonArray("commands");
                StringBuilder result = new StringBuilder();
                for (JsonElement cmd : cmds) {
                    LOGGER.info("[mcAgent] LLM执行指令: /{}", cmd.getAsString());
                    String feedback = commandExecutor.execute(cmd.getAsString());
                    result.append(feedback).append("\n");
                }
                yield new ToolResult(id, result.toString().trim());
            }
            case "send_message_to_player" -> {
                String msg = args.get("message").getAsString();
                sendPlayerMessage("§b[AI]§r " + msg);
                TextToSpeech.speak(msg);
                yield new ToolResult(id, "消息已发送给玩家");
            }
            default -> new ToolResult(id, "未知工具: " + name);
        };
    }

    private void sendPlayerMessage(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.execute(() -> mc.player.sendMessage(Text.literal(msg), false));
        }
    }
}