package space.minecraftstl.mcagent.client.llm;

import com.google.gson.*;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.border.WorldBorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.minecraftstl.mcagent.client.command.CommandExecutor;
import space.minecraftstl.mcagent.client.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
            case "get_entities_in_range" -> {
                yield new ToolResult(id, getEntitiesInRange(args));
            }
            case "scan_blocks_in_area" -> {
                yield new ToolResult(id, scanBlocksInArea(args));
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

        return sb.toString();
    }

    private String getEntitiesInRange(JsonObject args) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null) return "无法获取: 世界未加载";

        String shape = args.get("shape").getAsString();
        List<Entity> entities;

        if ("sphere".equals(shape)) {
            if (!args.has("center_x") || !args.has("center_y") || !args.has("center_z") || !args.has("radius")) {
                return "参数错误: sphere形状需要center_x, center_y, center_z, radius";
            }
            double cx = args.get("center_x").getAsDouble();
            double cy = args.get("center_y").getAsDouble();
            double cz = args.get("center_z").getAsDouble();
            double radius = args.get("radius").getAsDouble();
            Box searchBox = new Box(cx - radius, cy - radius, cz - radius, cx + radius, cy + radius, cz + radius);
            entities = world.getEntitiesByClass(Entity.class, searchBox, e -> {
                double dx = e.getX() - cx;
                double dy = e.getY() - cy;
                double dz = e.getZ() - cz;
                return dx * dx + dy * dy + dz * dz <= radius * radius;
            });
        } else if ("box".equals(shape)) {
            if (!args.has("x1") || !args.has("y1") || !args.has("z1") || !args.has("x2") || !args.has("y2") || !args.has("z2")) {
                return "参数错误: box形状需要x1, y1, z1, x2, y2, z2";
            }
            double x1 = args.get("x1").getAsDouble();
            double y1 = args.get("y1").getAsDouble();
            double z1 = args.get("z1").getAsDouble();
            double x2 = args.get("x2").getAsDouble();
            double y2 = args.get("y2").getAsDouble();
            double z2 = args.get("z2").getAsDouble();
            Box searchBox = new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
            entities = world.getEntitiesByClass(Entity.class, searchBox, e -> true);
        } else {
            return "参数错误: shape必须是sphere或box";
        }

        JsonArray result = new JsonArray();
        for (Entity entity : entities) {
            JsonObject obj = new JsonObject();
            obj.addProperty("uuid", entity.getUuidAsString());
            obj.addProperty("type", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
            JsonArray pos = new JsonArray();
            pos.add(Math.round(entity.getX() * 100.0) / 100.0);
            pos.add(Math.round(entity.getY() * 100.0) / 100.0);
            pos.add(Math.round(entity.getZ() * 100.0) / 100.0);
            obj.add("position", pos);
            if (entity.hasCustomName()) {
                obj.addProperty("custom_name", entity.getCustomName().getString());
            }
            result.add(obj);
        }
        return result.isEmpty() ? "范围内没有找到实体" : result.toString();
    }

    private String scanBlocksInArea(JsonObject args) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null) return "无法获取: 世界未加载";

        int x1 = args.get("x1").getAsInt();
        int y1 = args.get("y1").getAsInt();
        int z1 = args.get("z1").getAsInt();
        int x2 = args.get("x2").getAsInt();
        int y2 = args.get("y2").getAsInt();
        int z2 = args.get("z2").getAsInt();

        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        long volume = (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > 32768) {
            return "范围过大: 体积为" + volume + "个方块，最大允许32768(32×32×32)";
        }

        // Clamp Y to world bounds
        minY = Math.max(minY, world.getBottomY());
        maxY = Math.min(maxY, world.getTopYInclusive());

        HashMap<String, Integer> counts = new HashMap<>();
        int skippedChunks = 0;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
                    skippedChunks++;
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    BlockState state = world.getBlockState(new BlockPos(x, y, z));
                    String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
                    counts.merge(blockId, 1, Integer::sum);
                }
            }
        }

        // Sort by count descending
        JsonObject result = new JsonObject();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> result.addProperty(e.getKey(), e.getValue()));

        if (skippedChunks > 0) {
            result.addProperty("_skipped_unloaded_columns", skippedChunks);
        }
        return result.toString();
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