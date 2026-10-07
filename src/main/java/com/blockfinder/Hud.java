package com.blockfinder;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** Compact HUD card: tracked block, total found, and distance + arrow to the nearest one. */
public class Hud {
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    public static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        Block t = Scanner.INSTANCE.target();
        if (t == null || mc.player == null || mc.options.hideGui) return;
        var res = Scanner.INSTANCE.results();
        int x = 6, y = 6, w = 150;
        g.fill(x, y, x + w, y + 38, Theme.background);
        g.fill(x, y, x + 2, y + 38, Theme.accent);
        g.renderItem(new ItemStack(t.asItem()), x + 6, y + 4);
        g.drawString(mc.font, t.getName().getString(), x + 26, y + 5, Theme.text);
        String status = res.size() + " found" + (Scanner.INSTANCE.isScanning() ? " §7· scanning…" : "");
        g.drawString(mc.font, status, x + 26, y + 15, Theme.accent);
        if (!res.isEmpty()) {
            BlockPos n = res.get(0);
            double dx = n.getX() + 0.5 - mc.player.getX(), dz = n.getZ() + 0.5 - mc.player.getZ();
            int dist = (int) Math.sqrt(dx * dx + dz * dz + Math.pow(n.getY() - mc.player.getY(), 2));
            double ang = Math.toDegrees(Math.atan2(-dx, dz)) - mc.player.getYRot();
            int idx = (int) Math.floorMod(Math.round(((ang + 180) % 360 + 360) % 360 / 45.0), 8);
            int dy = n.getY() - mc.player.getBlockY();
            g.drawString(mc.font, "Nearest " + ARROWS[idx] + " " + dist + "m  §7Y" + (dy >= 0 ? "+" : "") + dy,
                x + 26, y + 26, Theme.text);
        }
    }
}
