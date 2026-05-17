package space.minecraftstl.mcagent.client.command;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class CommandExecutor {

    public String execute(String command) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null) return "错误: 玩家未加入世界";

        ClientPlayNetworkHandler handler = player.networkHandler;
        if (handler == null) return "错误: 网络未连接";

        try {
            CommandFeedbackCapture.startCapture();
            CompletableFuture<Void> future = new CompletableFuture<>();
            mc.execute(() -> {
                handler.sendChatCommand(command);
                future.complete(null);
            });
            future.get(5, TimeUnit.SECONDS);
            // Wait for server feedback
            Thread.sleep(500);
            List<String> feedback = CommandFeedbackCapture.stopCapture();
            if (feedback.isEmpty()) {
                return "已执行: /" + command + " (无返回值)";
            }
            return "/" + command + " -> " + String.join("\n", feedback);
        } catch (Exception e) {
            CommandFeedbackCapture.stopCapture();
            return "执行失败 /" + command + ": " + e.getMessage();
        }
    }
}