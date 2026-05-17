package space.minecraftstl.mcagent.client.ui;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

public class HudRenderer implements HudRenderCallback {
    private volatile String status = null; // null = hidden

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        String s = this.status;
        if (s == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        int width = mc.getWindow().getScaledWidth();
        int textWidth = mc.textRenderer.getWidth(s);
        int x = (width - textWidth) / 2;
        int y = 4;
        // background
        context.fill(x - 4, y - 2, x + textWidth + 4, y + 12, 0xAA000000);
        // text
        int color = s.contains("录音") ? 0xFFFF5555 : (s.contains("处理") ? 0xFFFFAA00 : 0xFF55FF55);
        context.drawText(mc.textRenderer, s, x, y, color, true);
    }
}