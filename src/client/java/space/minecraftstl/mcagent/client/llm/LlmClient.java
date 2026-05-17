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

    private volatile boolean processing = false;
    private final ConcurrentLinkedQueue<QueuedMessage> messageQueue = new ConcurrentLinkedQueue<>();

    public record QueuedMessage(String type, String textContent, byte[] audioData) {}

    private static final String TOOL_SYSTEM_PROMPT = """
            你是一个Minecraft Java版 1.21.10游戏助手AI。玩家通过语音向你发出指令，你需要调用工具来执行操作。
            所有命令以玩家身份在客户端执行（相当于玩家在聊天栏输入）。

            可用工具：
            1. execute_commands - 执行Minecraft命令列表。参数: {"commands": ["command1", "command2"]}
               命令不需要带 / 前缀。所有命令会依次执行，结果会汇总后一起返回。
               没有输出的命令会显示"已执行(无返回值)"，执行失败会显示错误信息。
            2. send_message_to_player - 向当前客户端玩家本地发送聊天消息（不会发送到服务器，其他玩家看不到）。参数: {"message": "消息内容"}

            消息格式说明：
            - 玩家状态用 <player_context> 标签包裹
            - 玩家消息用 <player_message> 标签包裹，source属性标明来源(stt/text)

            规则：
            1. 命令不带 / 前缀
            2. 以下为风险指令，必须先用 send_message_to_player 向玩家说明你要执行的具体命令并请求确认，等玩家下一次语音回复确认后再执行：
               - 不限类型和范围的kill（如 kill @e）、涉及多个玩家的kill
               - 大范围的fill或clone
               - 不限类型的clear（如 clear @a）
               - 放置命令方块或命令方块矿车（setblock/give command_block等）
            3. 你会收到命令执行的反馈结果（包括错误信息），据此判断是否需要修复或后续操作
            4. 玩家状态信息（玩家名、位置、朝向、维度、时间、游戏模式）会自动附带在每次请求中
            5. 如需向玩家说话/提问，使用 send_message_to_player 工具（消息仅本地显示）
            6. 一次可以调用多个工具，所有工具结果会汇总后一起返回
            7. 玩家消息如标注source="stt"，表示由语音转文字转换，可能有同音字/谐音错误，请根据上下文纠错理解
            8. 如果工具结果和玩家新消息同时出现，说明玩家在你执行工具期间发送了新指令，请同时处理工具结果和新指令
            9. 不要使用setblock来探测/获取某个位置的方块信息，应使用data get block命令

            命令示例：
            | 玩家要求 | 命令 |
            |---------|------|
            | 杀死附近15格的羊 | kill @e[type=sheep,distance=..15] |
            | 清掉我背包里的火把 | clear @s minecraft:torch |
            | 给我64个钻石 | give @s minecraft:diamond 64 |
            | 在我头上放个石头 | setblock ~ ~2 ~ minecraft:stone |
            | 用玻璃围一个空心盒子 | fill ~-5 ~ ~-5 ~5 ~3 ~5 minecraft:glass hollow |
            | 召唤闪电 | summon minecraft:lightning_bolt ~ ~ ~ |
            | 把我传送上去10格 | tp @s ~ ~10 ~ |
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
                      "name": "send_message_to_player",
                      "description": "向玩家发送聊天消息",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "message": {
                            "type": "string",
                            "description": "要发送的消息"
                          }
                        },
                        "required": ["message"]
                      }
                    }
                  }
                ]
                """).getAsJsonArray();
    }

    public boolean isProcessing() {
        return processing;
    }

    public void setProcessing(boolean processing) {
        this.processing = processing;
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