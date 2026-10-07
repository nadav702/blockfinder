package com.yourname.blockatlas.gui;

import com.yourname.blockatlas.BlockAtlasClient;
import com.yourname.blockatlas.config.BlockAtlasConfig;
import com.yourname.blockatlas.gui.widget.Ui;
import com.yourname.blockatlas.highlight.HighlightManager;
import com.yourname.blockatlas.scan.BlockScanner;
import com.yourname.blockatlas.util.ColorUtil;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * Live HUD card: per highlighted block the number found, the distance to the nearest one and
 * a direction arrow relative to where the player is looking (▲/▼ when it is above/below).
 */
public final class HudOverlay {
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final int MAX_ROWS = 8;
    private static final int ROW_H = 18;
    private static final int WIDTH = 184;

    private HudOverlay() {}

    public static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, BlockAtlasClient.id("hud"), HudOverlay::extract);
    }

    private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        BlockAtlasConfig cfg = BlockAtlasConfig.get();
        HighlightManager hm = HighlightManager.get();
        LocalPlayer player = mc.player;
        if (!cfg.showHud || player == null || hm.activeBlocks().isEmpty()) return;
        if (mc.gui.screen() instanceof BlockAtlasScreen) return;

        Font font = mc.font;
        BlockCatalog catalog = BlockCatalog.get();
        List<Block> blocks = new ArrayList<>(hm.activeBlocks());
        int shown = Math.min(blocks.size(), MAX_ROWS);
        boolean more = blocks.size() > shown;
        boolean warn = cfg.range > BlockAtlasConfig.WARN_RANGE;

        int x = 6, y = 6;
        int h = 22 + shown * ROW_H + (more ? 11 : 0) + 14 + (warn ? 11 : 0);
        Ui.card(g, x, y, x + WIDTH, y + h, 6, 0xEE0E1015, 0xFF252A34);

        // header
        Ui.text(g, font, "Block", x + 8, y + 7, Ui.TEXT);
        Ui.text(g, font, "Atlas", x + 8 + font.width("Block"), y + 7, Ui.ACCENT);
        String status;
        int statusColor;
        if (!cfg.enabled) {
            status = Component.translatable("blockatlas.status.paused").getString();
            statusColor = Ui.WARN;
        } else if (BlockScanner.get().isScanning()) {
            status = Component.translatable("blockatlas.status.scanning",
                    Math.round(BlockScanner.get().progress() * 100)).getString();
            statusColor = Ui.ACCENT;
        } else {
            status = Component.translatable("blockatlas.status.live").getString();
            statusColor = Ui.OK;
        }
        Ui.textRight(g, font, status, x + WIDTH - 8, y + 7, statusColor);

        for (int i = 0; i < shown; i++) {
            Block b = blocks.get(i);
            BlockCatalog.Entry e = catalog.entry(b);
            if (e == null) continue;
            int ry = y + 20 + i * ROW_H;
            int rgb = cfg.colorFor(e.id()).rgb;
            Ui.roundRect(g, x + 6, ry + 3, x + 8, ry + 15, 1, ColorUtil.opaque(rgb));
            Ui.icon(g, font, e.icon(), e.name(), rgb, x + 12, ry + 1);

            HighlightManager.Stats s = hm.stats(b);
            String count;
            String where = "";
            if (s == null) {
                count = "…";
            } else {
                count = Ui.formatCount(s.count()) + (s.capped() ? "+" : "");
                if (s.count() > 0) where = Ui.formatDistance(s.nearestDistance()) + " " + direction(player, s.nearestPos());
            }
            int whereW = where.isEmpty() ? 0 : font.width(where) + 6;
            int countW = font.width(count);
            int right = x + WIDTH - 8;
            Ui.textRight(g, font, where, right, ry + 5, Ui.TEXT_DIM);
            Ui.textRight(g, font, count, right - whereW, ry + 5, s != null && s.count() > 0 ? Ui.TEXT : Ui.TEXT_FAINT);
            int nameMax = right - whereW - countW - 6 - (x + 32);
            Ui.text(g, font, Ui.trim(font, e.name(), nameMax), x + 32, ry + 5, Ui.TEXT_DIM);
        }

        int fy = y + 22 + shown * ROW_H;
        if (more) {
            Ui.text(g, font, Component.translatable("blockatlas.hud.more", blocks.size() - shown).getString(),
                    x + 12, fy, Ui.TEXT_FAINT);
            fy += 11;
        }
        Ui.rect(g, x + 6, fy - 2, x + WIDTH - 6, fy - 1, Ui.DIVIDER);
        String total = Component.translatable("blockatlas.hud.total", Ui.formatCount(hm.totalFound()),
                Ui.formatDistance(hm.nearestOverall())).getString();
        Ui.text(g, font, Ui.trim(font, total, WIDTH - 16), x + 8, fy + 2, Ui.TEXT_FAINT);
        if (warn) {
            Ui.text(g, font, Component.translatable("blockatlas.hud.warn", cfg.range).getString(),
                    x + 8, fy + 13, Ui.WARN);
        }
    }

    /** Arrow pointing at a block relative to the player's view yaw, plus ▲/▼ for height. */
    private static String direction(LocalPlayer player, long pos) {
        double dx = BlockPos.getX(pos) + 0.5 - player.getX();
        double dz = BlockPos.getZ(pos) + 0.5 - player.getZ();
        double dy = BlockPos.getY(pos) + 0.5 - player.getEyeY();
        String vertical = dy > 2.5 ? "▲" : dy < -2.5 ? "▼" : "";
        if (dx * dx + dz * dz < 1.0) return vertical.isEmpty() ? "•" : vertical;
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = Mth.wrapDegrees(targetYaw - player.getYRot());
        int index = Math.floorMod((int) Math.round(rel / 45.0), 8);
        return ARROWS[index] + vertical;
    }
}
