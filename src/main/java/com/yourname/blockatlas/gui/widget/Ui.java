package com.yourname.blockatlas.gui.widget;

import com.yourname.blockatlas.util.ColorUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/**
 * Design tokens and drawing primitives shared by every BlockAtlas widget.
 * Every call into {@link GuiGraphicsExtractor} goes through this class, so if a future
 * Minecraft version renames a GUI method only this file needs to change.
 */
public final class Ui {
    private Ui() {}

    // ---- palette ----------------------------------------------------------------------------
    public static int BACKDROP = 0xB0050608;
    public static int PANEL = 0xFF0E1015;
    public static final int PANEL_BORDER = 0xFF262B35;
    public static final int DIVIDER = 0xFF1A1E26;
    public static final int SURFACE = 0xFF161920;
    public static final int SURFACE_HOVER = 0xFF1E222B;
    public static final int SURFACE_BORDER = 0xFF262B35;
    public static final int BORDER_HOVER = 0xFF363C49;
    public static final int INPUT = 0xFF111318;
    public static final int TRACK = 0xFF262B35;
    public static final int TEXT = 0xFFE9ECF1;
    public static final int TEXT_DIM = 0xFF9098A6;
    public static final int TEXT_FAINT = 0xFF5D6472;
    public static int ACCENT = 0xFF7C9CFF;
    public static final int WARN = 0xFFFFB454;
    public static final int DANGER = 0xFFFF6B6B;
    public static final int OK = 0xFF5BD69A;
    public static final int STAR = 0xFFFFC94A;

    /** Re-reads accent colour and panel opacity from the config. Cheap; called every frame. */
    public static void applyTheme() {
        com.yourname.blockatlas.config.BlockAtlasConfig cfg = com.yourname.blockatlas.config.BlockAtlasConfig.get();
        ACCENT = 0xFF000000 | cfg.accentColor;
        PANEL = ColorUtil.argb(0x0E1015, cfg.panelOpacity);
        BACKDROP = ColorUtil.argb(0x050608, 0.69f * cfg.panelOpacity);
    }

    // ---- shapes -----------------------------------------------------------------------------

    public static void rect(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        if (x2 > x1 && y2 > y1) g.fill(x1, y1, x2, y2, color);
    }

    /** Filled rectangle with pixel-rounded corners. */
    public static void roundRect(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int radius, int color) {
        int r = Math.min(radius, Math.min((x2 - x1) / 2, (y2 - y1) / 2));
        if (r <= 0) {
            rect(g, x1, y1, x2, y2, color);
            return;
        }
        rect(g, x1, y1 + r, x2, y2 - r, color);
        for (int i = 0; i < r; i++) {
            int inset = r - (int) Math.round(Math.sqrt(r * r - (double) (r - i) * (r - i)));
            rect(g, x1 + inset, y1 + i, x2 - inset, y1 + i + 1, color);
            rect(g, x1 + inset, y2 - i - 1, x2 - inset, y2 - i, color);
        }
    }

    /** Rounded card with a 1px border. Fill should be opaque for a crisp border. */
    public static void card(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int radius, int fill, int border) {
        if ((fill >>> 24) == 0xFF) {
            roundRect(g, x1, y1, x2, y2, radius, border);
            roundRect(g, x1 + 1, y1 + 1, x2 - 1, y2 - 1, Math.max(0, radius - 1), fill);
            return;
        }
        // translucent fill: draw the fill, then a thin outline so the border doesn't show through
        roundRect(g, x1, y1, x2, y2, radius, fill);
        int r = Math.min(radius, Math.min((x2 - x1) / 2, (y2 - y1) / 2));
        rect(g, x1 + r, y1, x2 - r, y1 + 1, border);
        rect(g, x1 + r, y2 - 1, x2 - r, y2, border);
        rect(g, x1, y1 + r, x1 + 1, y2 - r, border);
        rect(g, x2 - 1, y1 + r, x2, y2 - r, border);
    }

    public static void gradientV(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int top, int bottom) {
        if (x2 > x1 && y2 > y1) g.fillGradient(x1, y1, x2, y2, top, bottom);
    }

    // ---- text -------------------------------------------------------------------------------

    public static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color) {
        g.text(font, s, x, y, color, false);
    }

    public static void textRight(GuiGraphicsExtractor g, Font font, String s, int right, int y, int color) {
        g.text(font, s, right - font.width(s), y, color, false);
    }

    public static void textCenter(GuiGraphicsExtractor g, Font font, String s, int cx, int y, int color) {
        g.text(font, s, cx - font.width(s) / 2, y, color, false);
    }

    /** Trims with an ellipsis so the string fits in maxWidth pixels. */
    public static String trim(Font font, String s, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font.width(s) <= maxWidth) return s;
        int ell = font.width("…");
        return font.plainSubstrByWidth(s, Math.max(0, maxWidth - ell)) + "…";
    }

    // ---- items ------------------------------------------------------------------------------

    /** 16×16 item icon. */
    public static void item(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        g.item(stack, x, y);
    }

    /** Icon, or a lettered colour chip for blocks without an item form (water, portals …). */
    public static void icon(GuiGraphicsExtractor g, Font font, ItemStack stack, String name, int rgb, int x, int y) {
        if (!stack.isEmpty()) {
            item(g, stack, x, y);
            return;
        }
        roundRect(g, x, y, x + 16, y + 16, 3, ColorUtil.mix(SURFACE, ColorUtil.opaque(rgb), 0.45f));
        String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        textCenter(g, font, letter, x + 8, y + 4, TEXT);
    }

    public static String formatCount(int n) {
        return String.format("%,d", n);
    }

    public static String formatDistance(double d) {
        if (d < 0) return "—";
        return d < 10 ? String.format("%.1f m", d) : Math.round(d) + " m";
    }
}
