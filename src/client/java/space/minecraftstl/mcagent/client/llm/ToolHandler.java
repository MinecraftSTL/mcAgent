package space.minecraftstl.mcagent.client.llm;

import com.google.gson.*;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
            case "get_entity_info" -> {
                String uuid = args.get("uuid").getAsString();
                yield new ToolResult(id, getEntityInfo(uuid));
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

    private String getEntityInfo(String uuidStr) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null) return "无法获取: 世界未加载";

        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            return "参数错误: 无效的UUID格式";
        }

        Entity entity = null;
        for (Entity e : world.getEntities()) {
            if (e.getUuid().equals(uuid)) {
                entity = e;
                break;
            }
        }
        if (entity == null) return "未找到该UUID的实体（可能未加载或已不存在）";

        JsonObject info = new JsonObject();
        info.addProperty("uuid", entity.getUuidAsString());
        info.addProperty("type", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
        if (entity.hasCustomName()) {
            info.addProperty("custom_name", entity.getCustomName().getString());
        }
        info.addProperty("display_name", entity.getName().getString());

        // Position & motion
        JsonArray pos = new JsonArray();
        pos.add(Math.round(entity.getX() * 100.0) / 100.0);
        pos.add(Math.round(entity.getY() * 100.0) / 100.0);
        pos.add(Math.round(entity.getZ() * 100.0) / 100.0);
        info.add("position", pos);

        JsonArray velocity = new JsonArray();
        velocity.add(Math.round(entity.getVelocity().x * 1000.0) / 1000.0);
        velocity.add(Math.round(entity.getVelocity().y * 1000.0) / 1000.0);
        velocity.add(Math.round(entity.getVelocity().z * 1000.0) / 1000.0);
        info.add("velocity", velocity);

        info.addProperty("yaw", Math.round(entity.getYaw() * 10.0) / 10.0);
        info.addProperty("pitch", Math.round(entity.getPitch() * 10.0) / 10.0);

        // State flags
        info.addProperty("on_ground", entity.isOnGround());
        info.addProperty("is_sneaking", entity.isSneaking());
        info.addProperty("is_sprinting", entity.isSprinting());
        info.addProperty("is_swimming", entity.isSwimming());
        info.addProperty("is_on_fire", entity.isOnFire());
        info.addProperty("is_invisible", entity.isInvisible());
        info.addProperty("is_glowing", entity.isGlowing());
        info.addProperty("is_silent", entity.isSilent());
        info.addProperty("has_no_gravity", entity.hasNoGravity());

        // Passengers & vehicle
        if (entity.hasVehicle()) {
            Entity vehicle = entity.getVehicle();
            info.addProperty("vehicle_uuid", vehicle.getUuidAsString());
            info.addProperty("vehicle_type", Registries.ENTITY_TYPE.getId(vehicle.getType()).toString());
        }
        if (!entity.getPassengerList().isEmpty()) {
            JsonArray passengers = new JsonArray();
            for (Entity p : entity.getPassengerList()) {
                JsonObject pObj = new JsonObject();
                pObj.addProperty("uuid", p.getUuidAsString());
                pObj.addProperty("type", Registries.ENTITY_TYPE.getId(p.getType()).toString());
                passengers.add(pObj);
            }
            info.add("passengers", passengers);
        }

        // LivingEntity specific
        if (entity instanceof LivingEntity living) {
            info.addProperty("health", Math.round(living.getHealth() * 10.0) / 10.0);
            info.addProperty("max_health", Math.round(living.getMaxHealth() * 10.0) / 10.0);
            info.addProperty("absorption", Math.round(living.getAbsorptionAmount() * 10.0) / 10.0);
            info.addProperty("is_baby", living.isBaby());

            // Armor value
            info.addProperty("armor", living.getArmor());

            // Equipment
            JsonObject equipment = new JsonObject();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getEquippedStack(slot);
                if (!stack.isEmpty()) {
                    equipment.addProperty(slot.getName(), stack.getItem().toString() + (stack.getCount() > 1 ? " x" + stack.getCount() : ""));
                }
            }
            if (equipment.size() > 0) info.add("equipment", equipment);

            // Active effects
            if (!living.getActiveStatusEffects().isEmpty()) {
                JsonArray effects = new JsonArray();
                for (Map.Entry<?, StatusEffectInstance> entry : living.getActiveStatusEffects().entrySet()) {
                    StatusEffectInstance effect = entry.getValue();
                    JsonObject eff = new JsonObject();
                    eff.addProperty("id", effect.getEffectType().getIdAsString());
                    eff.addProperty("amplifier", effect.getAmplifier());
                    eff.addProperty("duration", effect.getDuration());
                    effects.add(eff);
                }
                info.add("effects", effects);
            }
        }

        // MobEntity specific
        if (entity instanceof MobEntity mob) {
            if (mob.getTarget() != null) {
                info.addProperty("target_uuid", mob.getTarget().getUuidAsString());
            }
        }

        // TameableEntity specific
        if (entity instanceof TameableEntity tameable) {
            info.addProperty("is_tamed", tameable.isTamed());
            if (tameable.getOwner() != null) {
                info.addProperty("owner_uuid", tameable.getOwner().getUuidAsString());
            }
            info.addProperty("is_sitting", tameable.isSitting());
        }

        // PlayerEntity specific
        if (entity instanceof PlayerEntity player) {
            info.addProperty("experience_level", player.experienceLevel);
            info.addProperty("total_experience", player.totalExperience);
            info.addProperty("food_level", player.getHungerManager().getFoodLevel());
            info.addProperty("saturation", player.getHungerManager().getSaturationLevel());
        }

        return info.toString();
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