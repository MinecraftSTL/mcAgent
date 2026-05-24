package space.minecraftstl.mcagent.client.llm;

import com.google.gson.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import space.minecraftstl.mcagent.client.config.McagentConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class LlmClient {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();
    private final List<JsonObject> conversationHistory = new ArrayList<>();

    private final java.util.concurrent.atomic.AtomicBoolean processing = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final ConcurrentLinkedQueue<QueuedMessage> messageQueue = new ConcurrentLinkedQueue<>();

    public record QueuedMessage(String type, String textContent, byte[] audioData) {}

    private static final String TOOL_SYSTEM_PROMPT = """
            你是一个Minecraft Java版 1.21.10游戏助手AI。玩家通过语音向你发出指令，你需要调用工具来执行操作。
            所有命令以玩家身份通过客户端发送到服务端执行（相当于玩家在聊天栏输入）；命令是否可执行按服务端命令语义判断，不以客户端是否已加载目标区块、是否可见、是否在视野内为准。

            可用工具：
            1. execute_commands - 执行Minecraft命令列表。参数: {"commands": ["command1", "command2"]}
               命令不需要带 / 前缀。命令会发送到服务端执行，不因客户端未加载目标区块或不可见而默认失败。所有命令会依次执行，结果会汇总后一起返回。
               没有输出的命令会显示"已执行(无返回值)"，执行失败会显示错误信息。
            2. get_block_info - 获取指定坐标的方块信息（方块类型、方块状态属性、客户端可见NBT）。参数: {"x": 0, "y": 64, "z": 0}
               返回该坐标的方块ID、状态属性和NBT数据（如果有）。如果坐标超出范围或区块未加载，会返回错误信息。
               这是直接从客户端世界读取，不需要执行命令。
            3. get_entities_in_range - 获取指定范围内的实体列表。
               支持两种形状：
               - 球形: {"shape": "sphere", "center_x": 0, "center_y": 64, "center_z": 0, "radius": 10}
               - 长方体: {"shape": "box", "x1": -5, "y1": 60, "z1": -5, "x2": 5, "y2": 70, "z2": 5}
               返回范围内所有实体的UUID、类型、坐标，以及自定义显示名（如果有）。从客户端已加载的实体中查询。
            4. get_entity_info - 获取指定UUID实体的全部客户端可见信息。参数: {"uuid": "实体UUID字符串"}
               返回实体的详细属性，包括类型、位置、速度、朝向、生命值、装备、状态等。

            消息格式说明：
            - 玩家状态用 <player_context> 标签包裹
            - 玩家消息用 <player_message> 标签包裹，source属性标明来源(stt/text)

            规则：
            1. 命令不带 / 前缀。
            2. 以下为风险指令，必须先在回复中向玩家说明你要执行的具体命令并请求确认，等玩家下一次语音回复确认后再执行：不限类型和范围的 kill（如 kill @e）、涉及多个玩家的 kill、大范围的 fill 或 clone、不限类型的 clear（如 clear @a）、放置命令方块或命令方块矿车（如 setblock/give command_block 等）。
            3. 你会收到命令执行的反馈结果（包括错误信息），据此判断是否需要修复、重试或继续后续操作。玩家状态信息（玩家名、位置、朝向、维度、时间、游戏模式）会自动附带在每次请求中。
            4. 你的文本回复（content）会直接显示给玩家。只能输出纯文本，禁止使用任何 Markdown 或其他格式化写法。禁止出现标题、有序列表、无序列表、代码块、加粗、斜体、引用、分隔线、表格等格式。
            5. 一次可以调用多个工具，所有工具结果会汇总后一起返回。如果工具结果和玩家新消息同时出现，说明玩家在你执行工具期间发送了新指令，你需要同时处理工具结果和新指令。
            6. 玩家消息如标注 source="stt"，表示由语音转文字转换，可能有同音字、谐音字或识别错误，你应根据上下文纠错理解。
            7. 不要使用 setblock 来探测或获取某个位置的方块信息，应优先使用 get_block_info 工具直接查询。
            8. 玩家发出要求后，到命令实际执行前，玩家可能已经移动或转向。“我脚下”“我头上”“我前面”“我旁边”“围着我”“朝我面前”这类表达，应以本次请求中的 <player_context> 为基准。需要位置或朝向基准的命令，优先根据 <player_context> 换算为绝对坐标，或固定位置、朝向后执行。多条相关命令如果应基于同一个位置或朝向，统一使用同一个 <player_context>，不要直接使用裸的 ~ 或 ^。

            命令示例：
            | 玩家要求 | 命令 |
            |---------|------|
            | 杀死附近15格的羊 | kill @e[type=sheep,distance=..15] |
            | 清掉我背包里的火把 | clear @s minecraft:torch |
            | 给我64个钻石 | give @s minecraft:diamond 64 |
            | 在我头上放个石头 | setblock <x> <y+2> <z> minecraft:stone |
            | 用玻璃围一个空心盒子 | fill <x1> <y1> <z1> <x2> <y2> <z2> minecraft:glass hollow |
            | 召唤闪电 | summon minecraft:lightning_bolt <x> <y> <z> |
            | 把我传送上去10格 | tp @s <x> <y+10> <z> |
            | 帮我找要塞 | locate structure minecraft:stronghold |
            | 在坐标100 64 200放一个苦力怕刷怪笼 | setblock 100 64 200 minecraft:spawner{SpawnData:{entity:{id:"minecraft:creeper"}}} |
            | 把我主手换成末影之眼 | item replace entity @s weapon.mainhand with minecraft:ender_eye |
            | 给我100级经验 | experience add @s 100 levels |
            | 切创造模式 | gamemode creative @s |
            | 给我速度效果 | effect give @s minecraft:speed 60 2 |
            | 查看我的坐标数据 | data get entity @s Pos |

            """;

    private static final JsonArray TOOLS;

    static {
        TOOLS = JsonParser.parseString("""
                [
                  {
                    "type": "function",
                    "function": {
                      "name": "execute_commands",
                      "description": "执行一组Minecraft命令",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "commands": {
                            "type": "array",
                            "items": {"type": "string"},
                            "description": "要执行的命令列表，不带/前缀"
                          }
                        },
                        "required": ["commands"]
                      }
                    }
                  },
                  {
                    "type": "function",
                    "function": {
                      "name": "get_block_info",
                      "description": "获取指定坐标的方块信息，包括方块ID、状态属性和客户端可见的NBT数据",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "x": {"type": "integer", "description": "方块X坐标"},
                          "y": {"type": "integer", "description": "方块Y坐标"},
                          "z": {"type": "integer", "description": "方块Z坐标"}
                        },
                        "required": ["x", "y", "z"]
                      }
                    }
                  },
                  {
                    "type": "function",
                    "function": {
                      "name": "get_entities_in_range",
                      "description": "获取指定范围内的实体列表（从客户端已加载实体中查询）。支持球形和长方体两种范围。返回每个实体的UUID、类型、坐标和自定义名称（如有）。",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "shape": {"type": "string", "enum": ["sphere", "box"], "description": "搜索形状: sphere(球形)或box(长方体)"},
                          "center_x": {"type": "number", "description": "球心X坐标(sphere时必填)"},
                          "center_y": {"type": "number", "description": "球心Y坐标(sphere时必填)"},
                          "center_z": {"type": "number", "description": "球心Z坐标(sphere时必填)"},
                          "radius": {"type": "number", "description": "搜索半径(sphere时必填)"},
                          "x1": {"type": "number", "description": "长方体对角点1的X坐标(box时必填)"},
                          "y1": {"type": "number", "description": "长方体对角点1的Y坐标(box时必填)"},
                          "z1": {"type": "number", "description": "长方体对角点1的Z坐标(box时必填)"},
                          "x2": {"type": "number", "description": "长方体对角点2的X坐标(box时必填)"},
                          "y2": {"type": "number", "description": "长方体对角点2的Y坐标(box时必填)"},
                          "z2": {"type": "number", "description": "长方体对角点2的Z坐标(box时必填)"}
                        },
                        "required": ["shape"]
                      }
                    }
                  },
                  {
                    "type": "function",
                    "function": {
                      "name": "get_entity_info",
                      "description": "获取指定UUID实体的全部客户端可见信息，包括类型、位置、速度、朝向、生命值、装备、状态标志等",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "uuid": {"type": "string", "description": "实体的UUID字符串"}
                        },
                        "required": ["uuid"]
                      }
                    }
                  }
                ]
                """).getAsJsonArray();
    }

    public boolean isProcessing() {
        return processing.get();
    }

    public void setProcessing(boolean value) {
        this.processing.set(value);
    }

    /**
     * Atomically try to set processing to true. Returns true if successful (was false before).
     */
    public boolean tryStartProcessing() {
        return processing.compareAndSet(false, true);
    }

    public void queueUserMessage(String message) {
        messageQueue.add(new QueuedMessage("text", message, null));
    }

    public void queueSttMessage(String message) {
        messageQueue.add(new QueuedMessage("stt", message, null));
    }

    public void queueAudio(byte[] wav) {
        messageQueue.add(new QueuedMessage("audio", null, wav));
    }

    public boolean hasPendingMessages() {
        return !messageQueue.isEmpty();
    }

    public String getPlayerContext() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return "";
        return String.format("<player_context>\n{\"world\": {\"dimension\": \"%s\", \"time\": %d, \"min_y\": %d, \"max_y\": %d}, \"player\": {\"name\": \"%s\", \"position\": [%.2f, %.2f, %.2f], \"block_pos\": [%d, %d, %d], \"yaw\": %.1f, \"pitch\": %.1f, \"gamemode\": \"%s\"}}\n</player_context>",
                mc.world.getRegistryKey().getValue().toString(),
                mc.world.getTimeOfDay(),
                mc.world.getBottomY(),
                mc.world.getTopYInclusive(),
                player.getName().getString(),
                player.getX(), player.getY(), player.getZ(),
                player.getBlockPos().getX(), player.getBlockPos().getY(), player.getBlockPos().getZ(),
                player.getYaw(), player.getPitch(),
                mc.interactionManager != null ? mc.interactionManager.getCurrentGameMode().getId() : "unknown");
    }

    public JsonObject sendText(String userText) {
        return sendText(userText, "text");
    }

    public JsonObject sendTextFromStt(String userText) {
        return sendText(userText, "stt");
    }

    private JsonObject sendText(String userText, String source) {
        McagentConfig cfg = McagentConfig.get();
        String content = getPlayerContext() + "\n<player_message source=\"" + source + "\">\n" + userText + "\n</player_message>";
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", content);
        conversationHistory.add(userMsg);
        trimHistory(cfg.maxContextRounds);
        return doRequest(cfg);
    }

    public JsonObject sendAudio(byte[] wavData) {
        McagentConfig cfg = McagentConfig.get();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", getPlayerContext());
        content.add(textPart);
        JsonObject audioPart = new JsonObject();
        audioPart.addProperty("type", "input_audio");
        JsonObject audioData = new JsonObject();
        audioData.addProperty("data", Base64.getEncoder().encodeToString(wavData));
        audioData.addProperty("format", "wav");
        audioPart.add("input_audio", audioData);
        content.add(audioPart);
        userMsg.add("content", content);
        conversationHistory.add(userMsg);
        trimHistory(cfg.maxContextRounds);
        return doRequest(cfg);
    }

    public JsonObject sendToolResult(String toolCallId, String result) {
        McagentConfig cfg = McagentConfig.get();
        JsonObject toolMsg = new JsonObject();
        toolMsg.addProperty("role", "tool");
        toolMsg.addProperty("tool_call_id", toolCallId);
        toolMsg.addProperty("content", result);
        conversationHistory.add(toolMsg);
        return doRequest(cfg);
    }

    public JsonObject sendToolResults(List<ToolHandler.ToolResult> results) {
        McagentConfig cfg = McagentConfig.get();
        for (var r : results) {
            JsonObject toolMsg = new JsonObject();
            toolMsg.addProperty("role", "tool");
            toolMsg.addProperty("tool_call_id", r.toolCallId());
            toolMsg.addProperty("content", r.content());
            conversationHistory.add(toolMsg);
        }
        appendQueuedMessages();
        return doRequest(cfg);
    }

    /**
     * Consume all queued messages, append them to conversationHistory, and send a new request.
     * Returns the LLM response.
     */
    public JsonObject consumeQueue() {
        McagentConfig cfg = McagentConfig.get();
        appendQueuedMessages();
        return doRequest(cfg);
    }

    private void appendQueuedMessages() {
        List<QueuedMessage> pending = new ArrayList<>();
        QueuedMessage msg;
        while ((msg = messageQueue.poll()) != null) {
            pending.add(msg);
        }
        if (pending.isEmpty()) return;

        String context = getPlayerContext();

        // Separate text/stt messages and audio messages
        List<QueuedMessage> textMessages = new ArrayList<>();
        List<QueuedMessage> audioMessages = new ArrayList<>();
        for (QueuedMessage m : pending) {
            if ("audio".equals(m.type())) {
                audioMessages.add(m);
            } else {
                textMessages.add(m);
            }
        }

        // Add text/stt messages as one user message with multiple <player_message> tags
        if (!textMessages.isEmpty()) {
            StringBuilder sb = new StringBuilder(context);
            for (QueuedMessage tm : textMessages) {
                sb.append("\n<player_message source=\"").append(tm.type()).append("\">\n")
                        .append(tm.textContent()).append("\n</player_message>");
            }
            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", sb.toString());
            conversationHistory.add(userMsg);
        }

        // Add each audio message as a separate user message with array content
        for (QueuedMessage am : audioMessages) {
            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            JsonArray content = new JsonArray();
            JsonObject textPart = new JsonObject();
            textPart.addProperty("type", "text");
            textPart.addProperty("text", textMessages.isEmpty() ? context : "");
            content.add(textPart);
            JsonObject audioPart = new JsonObject();
            audioPart.addProperty("type", "input_audio");
            JsonObject audioData = new JsonObject();
            audioData.addProperty("data", Base64.getEncoder().encodeToString(am.audioData()));
            audioData.addProperty("format", "wav");
            audioPart.add("input_audio", audioData);
            content.add(audioPart);
            userMsg.add("content", content);
            conversationHistory.add(userMsg);
        }
    }

    private JsonObject doRequest(McagentConfig cfg) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", cfg.llmModel);
            body.add("tools", TOOLS);

            JsonArray messages = new JsonArray();
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", TOOL_SYSTEM_PROMPT + cfg.systemPrompt);
            messages.add(sys);
            for (JsonObject msg : conversationHistory) {
                messages.add(msg);
            }
            body.add("messages", messages);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.llmApiUrl))
                    .header("Authorization", "Bearer " + cfg.llmApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject respJson = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (respJson.has("choices")) {
                JsonObject choice = respJson.getAsJsonArray("choices").get(0).getAsJsonObject();
                JsonObject assistantMsg = choice.getAsJsonObject("message");
                conversationHistory.add(assistantMsg);
            }
            return respJson;
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getMessage());
            return err;
        }
    }

    private void trimHistory(int maxRounds) {
        while (conversationHistory.size() > maxRounds * 3) {
            conversationHistory.removeFirst();
        }
    }

    public void clearHistory() {
        conversationHistory.clear();
    }
}
