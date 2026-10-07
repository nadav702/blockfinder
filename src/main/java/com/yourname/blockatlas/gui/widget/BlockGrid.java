package com.yourname.blockatlas.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import com.yourname.blockatlas.config.BlockAtlasConfig;
import com.yourname.blockatlas.gui.BlockCatalog;
import com.yourname.blockatlas.highlight.HighlightManager;
import com.yourname.blockatlas.util.ColorUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * Virtualised, smoothly scrolling grid of block cards. Only visible rows are drawn, so
 * thousands of entries (vanilla + modded) cost the same as a single screenful.
 */
public final class BlockGrid {
    public enum Action { TOGGLE, FOCUS, FAVORITE }

    public interface Listener {
        void onCard(BlockCatalog.Entry entry, Action action);
    }

    private static final int CARD_H = 30;
    private static final int GAP = 5;
    private static final int MIN_CARD_W = 136;
    private static final int BAR_W = 4;
    private static final int ROW = CARD_H + GAP;

    private final int x, y, w, h;
    private final int cols, cardW;
    private final Listener listener;

    private List<BlockCatalog.Entry> entries = List.of();
    private BlockCatalog.Entry focused;
    private String emptyMessage = "";

    private double scroll, targetScroll;
    private long lastNanos;
    private boolean draggingBar;
    private double barGrab;

    public BlockGrid(int x, int y, int w, int h, Listener listener) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.listener = listener;
        int inner = w - BAR_W - 6;
        this.cols = Math.max(1, (inner + GAP) / (MIN_CARD_W + GAP));
        this.cardW = (inner - (cols - 1) * GAP) / cols;
    }

    public void setEntries(List<BlockCatalog.Entry> list, boolean resetScroll) {
        entries = list;
        if (resetScroll) {
            scroll = targetScroll = 0;
        } else {
            targetScroll = clamp(targetScroll);
            scroll = clamp(scroll);
        }
    }

    public void setFocused(BlockCatalog.Entry entry) {
        focused = entry;
    }

    public void setEmptyMessage(String message) {
        emptyMessage = message;
    }

    public double scrollPosition() {
        return targetScroll;
    }

    public void setScrollPosition(double s) {
        targetScroll = scroll = clamp(s);
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int rows() {
        return (entries.size() + cols - 1) / cols;
    }

    private double maxScroll() {
        return Math.max(0, rows() * ROW - GAP - h);
    }

    private double clamp(double s) {
        return Math.max(0, Math.min(maxScroll(), s));
    }

    public void scroll(double notches) {
        targetScroll = clamp(targetScroll - notches * ROW * 1.25);
    }

    // ---- rendering --------------------------------------------------------------------------

    public void render(GuiGraphicsExtractor g, Font font, int mx, int my) {
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0.016 : Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        targetScroll = clamp(targetScroll);
        scroll += (targetScroll - scroll) * Math.min(1.0, dt * 14.0);
        if (Math.abs(targetScroll - scroll) < 0.3) scroll = targetScroll;

        if (entries.isEmpty()) {
            Ui.textCenter(g, font, emptyMessage, x + w / 2, y + h / 2 - 4, Ui.TEXT_FAINT);
            return;
        }

        HighlightManager hm = HighlightManager.get();
        BlockAtlasConfig cfg = BlockAtlasConfig.get();
        boolean mouseIn = contains(mx, my);

        g.enableScissor(x, y, x + w, y + h);
        int firstRow = (int) (scroll / ROW);
        int lastRow = Math.min(rows() - 1, (int) ((scroll + h) / ROW));
        for (int row = firstRow; row <= lastRow; row++) {
            int cy = y + row * ROW - (int) Math.round(scroll);
            for (int col = 0; col < cols; col++) {
                int index = row * cols + col;
                if (index >= entries.size()) break;
                int cx = x + col * (cardW + GAP);
                drawCard(g, font, entries.get(index), cx, cy, mouseIn, mx, my, hm, cfg);
            }
        }
        g.disableScissor();

        // soft fades at the edges hint at more content
        if (scroll > 1) Ui.gradientV(g, x, y, x + w - BAR_W - 2, y + 8, Ui.PANEL, 0x000E1015);
        if (scroll < maxScroll() - 1) Ui.gradientV(g, x, y + h - 8, x + w - BAR_W - 2, y + h, 0x000E1015, Ui.PANEL);

        // scrollbar
        double max = maxScroll();
        if (max > 0) {
            int trackX = x + w - BAR_W;
            Ui.roundRect(g, trackX, y, trackX + BAR_W, y + h, 2, 0xFF15181E);
            int thumbH = Math.max(20, (int) (h * (h / (double) (h + max))));
            int thumbY = y + (int) ((h - thumbH) * (scroll / max));
            boolean hot = draggingBar || (mx >= trackX - 2 && mx <= x + w && my >= y && my < y + h);
            Ui.roundRect(g, trackX, thumbY, trackX + BAR_W, thumbY + thumbH, 2, hot ? 0xFF4A5263 : 0xFF333946);
        }
    }

    private void drawCard(GuiGraphicsExtractor g, Font font, BlockCatalog.Entry e, int cx, int cy,
                          boolean mouseIn, int mx, int my, HighlightManager hm, BlockAtlasConfig cfg) {
        boolean active = hm.isActive(e.block());
        boolean hover = mouseIn && mx >= cx && mx < cx + cardW && my >= cy && my < cy + CARD_H;
        boolean fav = cfg.isFavorite(e.id());
        int color = ColorUtil.opaque(cfg.colorFor(e.id()).rgb);

        int bg = active ? ColorUtil.mix(hover ? Ui.SURFACE_HOVER : Ui.SURFACE, color, 0.10f)
                : hover ? Ui.SURFACE_HOVER : Ui.SURFACE;
        int border = e == focused ? Ui.ACCENT
                : active ? ColorUtil.mix(Ui.SURFACE_BORDER, color, 0.65f)
                : hover ? Ui.BORDER_HOVER : Ui.SURFACE_BORDER;
        Ui.card(g, cx, cy, cx + cardW, cy + CARD_H, 5, bg, border);
        if (active) Ui.roundRect(g, cx + 2, cy + 7, cx + 4, cy + CARD_H - 7, 1, color);

        Ui.icon(g, font, e.icon(), e.name(), color, cx + 8, cy + 7);

        int textX = cx + 30;
        int textW = cardW - 30 - 18;
        Ui.text(g, font, Ui.trim(font, e.name(), textW), textX, cy + 6, Ui.TEXT);

        String countText = "";
        if (active) {
            HighlightManager.Stats s = hm.stats(e.block());
            countText = s == null ? "…" : Ui.formatCount(s.count()) + (s.capped() ? "+" : "");
        }
        int countW = countText.isEmpty() ? 0 : font.width(countText) + 6;
        Ui.text(g, font, Ui.trim(font, e.shortId(), cardW - 30 - 8 - countW), textX, cy + 17, Ui.TEXT_FAINT);
        if (!countText.isEmpty()) Ui.textRight(g, font, countText, cx + cardW - 8, cy + 17, color);

        if (fav || hover) {
            boolean starHover = hover && overStar(cx, cy, mx, my);
            int starColor = fav ? Ui.STAR : starHover ? Ui.TEXT : Ui.TEXT_FAINT;
            Ui.text(g, font, fav ? "★" : "☆", cx + cardW - 14, cy + 5, starColor);
        }
    }

    private boolean overStar(int cx, int cy, double mx, double my) {
        return mx >= cx + cardW - 17 && mx < cx + cardW - 2 && my >= cy + 2 && my < cy + 15;
    }

    // ---- input ------------------------------------------------------------------------------

    public boolean mouseClicked(double mx, double my, int button) {
        if (!contains(mx, my)) return false;

        double max = maxScroll();
        if (button == InputConstants.MOUSE_BUTTON_LEFT && max > 0 && mx >= x + w - BAR_W - 2) {
            int thumbH = Math.max(20, (int) (h * (h / (double) (h + max))));
            int thumbY = y + (int) ((h - thumbH) * (scroll / max));
            draggingBar = true;
            barGrab = (my >= thumbY && my < thumbY + thumbH) ? my - thumbY : thumbH / 2.0;
            dragBar(my);
            return true;
        }

        int localY = (int) (my - y + scroll);
        int row = localY / ROW;
        int col = (int) ((mx - x) / (cardW + GAP));
        if (col < 0 || col >= cols || localY % ROW >= CARD_H || (mx - x) % (cardW + GAP) >= cardW) return true;
        int index = row * cols + col;
        if (index < 0 || index >= entries.size()) return true;

        BlockCatalog.Entry e = entries.get(index);
        int cx = x + col * (cardW + GAP);
        int cy = y + row * ROW - (int) Math.round(scroll);
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            listener.onCard(e, overStar(cx, cy, mx, my) ? Action.FAVORITE : Action.TOGGLE);
        } else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
            listener.onCard(e, Action.FOCUS);
        }
        return true;
    }

    public boolean mouseDragged(double my) {
        if (!draggingBar) return false;
        dragBar(my);
        return true;
    }

    public void mouseReleased() {
        draggingBar = false;
    }

    private void dragBar(double my) {
        double max = maxScroll();
        int thumbH = Math.max(20, (int) (h * (h / (double) (h + max))));
        double t = (my - barGrab - y) / Math.max(1, h - thumbH);
        targetScroll = scroll = clamp(t * max);
    }
}
