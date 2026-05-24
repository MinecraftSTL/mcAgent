package space.minecraftstl.mcagent.client.llm;

import com.google.gson.*;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.border.WorldBorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.minecraftstl.mcagent.client.command.CommandExecutor;
import space.minecraftstl.mcagent.client.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

        // handle tool calls check
        boolean hasToolCalls = "tool_calls".equals(finishReason) || message.has("tool_calls");

        // handle content (text response) - only display when no tool calls (final answer)
        if (!hasToolCalls && message.has("content") && !message.get("content").isJsonNull()) {
            var contentEl = message.get("content");
            String content = contentEl.isJsonPrimitive() ? contentEl.getAsString() : contentEl.toString();
            if (!content.isBlank()) {
                sendPlayerMessage("§b[AI]§r " + content);
                TextToSpeech.speak(content);
            }
        }

        // handle tool calls - batch all results together
        if (hasToolCalls) {
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
            case "get_block_info" -> {
                int x = args.get("x").getAsInt();
                int y = args.get("y").getAsInt();
                int z = args.get("z").getAsInt();
                yield new ToolResult(id, getBlockInfo(x, y, z));
            }
            default -> new ToolResult(id, "未知工具: " + name);
        };
    }

    private String getBlockInfo(int x, int y, int z) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null) {
            return "无法获取: 世界未加载";
        }

        // Check world height bounds
        if (y < world.getBottomY() || y > world.getTopYInclusive()) {
            return String.format("无法获取: 坐标Y=%d超出世界高度范围 [%d, %d]", y, world.getBottomY(), world.getTopYInclusive());
        }

        BlockPos pos = new BlockPos(x, y, z);

        // Check world border
        WorldBorder border = world.getWorldBorder();
        if (!border.contains(pos)) {
            return "无法获取: 坐标超出世界边界";
        }

        // Check if chunk is loaded
        if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
            return "无法获取: 该位置的区块未加载（超出客户端加载范围）";
        }

        BlockState state = world.getBlockState(pos);
        StringBuilder sb = new StringBuilder();

        // Block ID
        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
        sb.append("block: ").append(blockId);

        // Block state properties
        if (!state.getEntries().isEmpty()) {
            sb.append("\nstates: {");
            boolean first = true;
            for (Map.Entry<Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
                if (!first) sb.append(", ");
                sb.append(entry.getKey().getName()).append("=").append(propertyValueToString(entry.getKey(), entry.getValue()));
                first = false;
            }
            sb.append("}");
        }

        // Client-visible NBT from block entity
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity != null) {
            NbtCompound nbt = blockEntity.toInitialChunkDataNbt(world.getRegistryManager());
            if (nbt != null && !nbt.isEmpty()) {
                sb.append("\nnbt: ").append(nbt.toString());
            }
        }

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private <T extends Comparable<T>> String propertyValueToString(Property<T> property, Comparable<?> value) {
        return property.name((T) value);
    }

    private void sendPlayerMessage(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.execute(() -> mc.player.sendMessage(Text.literal(msg), false));
        }
    }
}