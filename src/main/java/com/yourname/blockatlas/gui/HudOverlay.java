package com.yourname.blockatlas.gui;

import com.yourname.blockatlas.BlockAtlasClient;
import com.yourname.blockatlas.config.BlockAtlasConfig;
import com.yourname.blockatlas.freecam.Freecam;
import com.yourname.blockatlas.gui.widget.Ui;
import com.yourname.blockatlas.highlight.HighlightManager;
import com.yourname.blockatlas.highlight.Target;
import com.yourname.blockatlas.scan.BlockScanner;
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
 * a direction arrow; plus the map target. Corner and size come from the settings.
 */
public final class HudOverlay {
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final int MAX_ROWS = 8;
    private static final int ROW_H = 18;
    private static final int WIDTH = 184;
    private static final int MARGIN = 6;

    private HudOverlay() {}

    public static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, BlockAtlasClient.id("hud"), HudOverlay::extract);
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        BlockAtlasConfig cfg = BlockAtlasConfig.get();
        HighlightManager hm = HighlightManager.get();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (mc.gui.screen() instanceof ScaledScreen) return;
        Ui.applyTheme();
        Font font = mc.font;

        if (Freecam.isActive()) {
            String label = tr("blockatlas.hud.freecam");
            int w = font.width(label) + 16;
            int cx = g.guiWidth() / 2;
            Ui.card(g, cx - w / 2, 4, cx + w / 2, 18, 7, 0xEE0E1015, Ui.ACCENT);
            Ui.textCenter(g, font, label, cx, 7, Ui.ACCENT);
        }

        boolean hasBlocks = !hm.activeBlocks().isEmpty();
        boolean hasTarget = Target.isSet();
        if (!cfg.showHud || (!hasBlocks && !hasTarget)) return;

        List<Block> blocks = new ArrayList<>(hm.activeBlocks());
        int shown = Math.min(blocks.size(), MAX_ROWS);
        boolean more = blocks.size() > shown;
        boolean warn = cfg.range > BlockAtlasConfig.WARN_RANGE;

        int h = 20 + (hasTarget ? 22 : 0) + shown * ROW_H + (more ? 11 : 0) + (hasBlocks ? 14 : 0) + (warn && hasBlocks ? 11 : 0);
        float s = cfg.hudScale;
        int sw = Math.round(WIDTH * s), shgt = Math.round(h * s);
        int ox = (cfg.hudCorner & 1) == 0 ? MARGIN : g.guiWidth() - MARGIN - sw;
        int oy = cfg.hudCorner < 2 ? MARGIN : g.guiHeight() - MARGIN - shgt - (cfg.hudCorner >= 2 ? 40 : 0);
        if (Freecam.isActive() && cfg.hudCorner < 2) oy = Math.max(oy, 22);

        g.pose().pushMatrix();
        g.pose().translate(ox, oy);
        g.pose().scale(s, s);
        drawCard(g, font, mc, player, cfg, hm, blocks, shown, more, warn, h);
        g.pose().popMatrix();
    }

    private static void drawCard(GuiGraphicsExtractor g, Font font, Minecraft mc, LocalPlayer player, BlockAtlasConfig cfg,
                                 HighlightManager hm, List<Block> blocks, int shown, boolean more, boolean warn, int h) {
        BlockCatalog catalog = BlockCatalog.get();
        int x = 0, y = 0;
        Ui.card(g, x, y, x + WIDTH, y + h, 6, (Ui.PANEL & 0x00FFFFFF) | 0xEE000000, 0xFF252A34);

        Ui.text(g, font, "Block", x + 8, y + 7, Ui.TEXT);
        Ui.text(g, font, "Atlas", x + 8 + font.width("Block"), y + 7, Ui.ACCENT);
        String status;
        int statusColor;
        if (!cfg.enabled) {
            status = tr("blockatlas.status.paused");
            statusColor = Ui.WARN;
        } else if (BlockScanner.get().isScanning()) {
            status = tr("blockatlas.status.scanning", Math.round(BlockScanner.get().progress() * 100));
            statusColor = Ui.ACCENT;
        } else {
            status = tr("blockatlas.status.live");
            statusColor = Ui.OK;
        }
        if (!blocks.isEmpty()) Ui.textRight(g, font, status, x + WIDTH - 8, y + 7, statusColor);

        int ry0 = y + 20;
        if (Target.isSet()) {
            long p = Target.pos();
            BlockCatalog.Entry te = catalog.entry(Target.block());
            double dist = Math.sqrt(dist2(player, p));
            Ui.roundRect(g, x + 4, ry0, x + WIDTH - 4, ry0 + 19, 4, 0x33FFC94A);
            if (te != null) Ui.icon(g, font, te.icon(), te.name(), 0xFFC94A, x + 8, ry0 + 1);
            String right = Ui.formatDistance(dist) + " " + direction(player, p);
            Ui.textRight(g, font, right, x + WIDTH - 9, ry0 + 6, Ui.STAR);
            String name = tr("blockatlas.hud.target", te != null ? te.name() : "?");
            Ui.text(g, font, Ui.trim(font, name, WIDTH - 40 - font.width(right) - 6), x + 28, ry0 + 6, Ui.TEXT);
            ry0 += 22;
        }

        for (int i = 0; i < shown; i++) {
            Block b = blocks.get(i);
            BlockCatalog.Entry e = catalog.entry(b);
            if (e == null) continue;
            int ry = ry0 + i * ROW_H;
            int rgb = cfg.colorFor(e.id()).rgb;
            Ui.roundRect(g, x + 6, ry + 3, x + 8, ry + 15, 1, 0xFF000000 | rgb);
            Ui.icon(g, font, e.icon(), e.name(), rgb, x + 12, ry + 1);

            HighlightManager.Stats st = hm.stats(b);
            String count;
            String where = "";
            if (st == null) {
                count = "…";
            } else {
                count = Ui.formatCount(st.count()) + (st.capped() ? "+" : "");
                if (st.count() > 0) where = Ui.formatDistance(st.nearestDistance()) + " " + direction(player, st.nearestPos());
            }
            int whereW = where.isEmpty() ? 0 : font.width(where) + 6;
            int countW = font.width(count);
            int right = x + WIDTH - 8;
            Ui.textRight(g, font, where, right, ry + 5, Ui.TEXT_DIM);
            Ui.textRight(g, font, count, right - whereW, ry + 5, st != null && st.count() > 0 ? Ui.TEXT : Ui.TEXT_FAINT);
            int nameMax = right - whereW - countW - 6 - (x + 32);
            Ui.text(g, font, Ui.trim(font, e.name(), nameMax), x + 32, ry + 5, Ui.TEXT_DIM);
        }

        int fy = ry0 + 2 + shown * ROW_H;
        if (more) {
            Ui.text(g, font, tr("blockatlas.hud.more", blocks.size() - shown), x + 12, fy, Ui.TEXT_FAINT);
            fy += 11;
        }
        if (!blocks.isEmpty()) {
            Ui.rect(g, x + 6, fy - 2, x + WIDTH - 6, fy - 1, Ui.DIVIDER);
            String total = tr("blockatlas.hud.total", Ui.formatCount(hm.totalFound()), Ui.formatDistance(hm.nearestOverall()));
            Ui.text(g, font, Ui.trim(font, total, WIDTH - 16), x + 8, fy + 2, Ui.TEXT_FAINT);
            if (warn) Ui.text(g, font, tr("blockatlas.hud.warn", cfg.range), x + 8, fy + 13, Ui.WARN);
        }
    }

    private static double dist2(LocalPlayer player, long pos) {
        double dx = BlockPos.getX(pos) + 0.5 - player.getX();
        double dy = BlockPos.getY(pos) + 0.5 - player.getEyeY();
        double dz = BlockPos.getZ(pos) + 0.5 - player.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Arrow pointing at a block relative to the player's view yaw, plus ▲/▼ for height. */
    static String direction(LocalPlayer player, long pos) {
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
