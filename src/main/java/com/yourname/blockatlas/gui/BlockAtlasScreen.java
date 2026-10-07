package com.yourname.blockatlas.gui;

import com.yourname.blockatlas.BlockAtlasClient;
import com.yourname.blockatlas.config.BlockAtlasConfig;
import com.yourname.blockatlas.gui.widget.BlockGrid;
import com.yourname.blockatlas.gui.widget.PillButton;
import com.yourname.blockatlas.gui.widget.SearchField;
import com.yourname.blockatlas.gui.widget.Slider;
import com.yourname.blockatlas.gui.widget.Ui;
import com.yourname.blockatlas.highlight.HighlightManager;
import com.yourname.blockatlas.scan.BlockScanner;
import com.yourname.blockatlas.util.ColorUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The BlockAtlas browser. Everything is drawn by hand (no vanilla widgets) for a consistent,
 * dark, card-based look:
 *
 * <pre>
 * ┌ BlockAtlas  1,104 blocks · 3 highlighted        [Enabled][Through walls][HUD][Clear all] ┐
 * │ [ search ............................................ ] │ ┌ SCAN RANGE ──────────────┐ │
 * │ (All)(Ores)(Natural)(Building)(Redstone)(Decor)(Tech)  │ │ ───────●──────  128 m    │ │
 * │ ┌card┐ ┌card┐ ┌card┐ ┌card┐                           │ ├ COLOUR ──────────────────┤ │
 * │ ┌card┐ ┌card┐ ┌card┐ ┌card┐               smooth ↕    │ │ hue / opacity / swatches │ │
 * │ ...                                                    │ ├ ACTIVE · 3 ──────────────┤ │
 * │ hints                                                  │ │ ● Diamond Ore   12 · 9 m │ │
 * └──────────────────────────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class BlockAtlasScreen extends Screen {
    private static final int[] PRESETS = {
            0xFF5555, 0xFFA040, 0xFFE14D, 0x55FF77, 0x4DE8FF, 0x5C7CFF, 0xC77DFF, 0xFFFFFF
    };
    private static final int ROW_H = 16;

    // remembered between openings
    private static String lastQuery = "";
    private static Category lastCategory = Category.ALL;
    private static double lastScroll;

    private final BlockAtlasConfig cfg = BlockAtlasConfig.get();
    private final HighlightManager hm = HighlightManager.get();

    private BlockCatalog catalog;
    private SearchField search;
    private BlockGrid grid;
    private Slider rangeSlider, hueSlider, alphaSlider;
    private final List<PillButton> headerButtons = new ArrayList<>();
    private final List<PillButton> tabButtons = new ArrayList<>();
    private Category category = lastCategory;
    private BlockCatalog.Entry focused;
    private Slider dragging;
    private int activeScroll;
    private int resultCount;

    // layout
    private int px, py, pw, ph;
    private int listX, listY, listW, listH;
    private int sideX, sideW;
    private int rangeCardY, colorCardY, activeCardY, activeCardBottom, swatchY, footerY;
    private int headerLeftLimit;

    public BlockAtlasScreen() {
        super(Component.translatable("blockatlas.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    // ---- layout -----------------------------------------------------------------------------

    @Override
    protected void init() {
        catalog = BlockCatalog.get();
        headerButtons.clear();
        tabButtons.clear();

        pw = Math.min(width - 16, 780);
        ph = Math.min(height - 16, 460);
        px = (width - pw) / 2;
        py = (height - ph) / 2;

        sideW = pw >= 620 ? 172 : 150;
        sideX = px + pw - 12 - sideW;
        listX = px + 12;
        listW = sideX - 10 - listX;

        // header buttons, right-aligned
        int bx = px + pw - 12;
        bx = addHeaderButton(bx, () -> tr("blockatlas.button.clear"), tr("blockatlas.button.clear"),
                () -> false, Ui.DANGER, this::clearAll);
        bx = addHeaderButton(bx, () -> tr("blockatlas.button.hud"), tr("blockatlas.button.hud"),
                () -> cfg.showHud, Ui.ACCENT, () -> { cfg.showHud = !cfg.showHud; cfg.save(); });
        bx = addHeaderButton(bx, () -> tr("blockatlas.button.xray"), tr("blockatlas.button.xray"),
                () -> cfg.seeThroughWalls, Ui.ACCENT, () -> { cfg.seeThroughWalls = !cfg.seeThroughWalls; cfg.save(); });
        bx = addHeaderButton(bx,
                () -> cfg.enabled ? "● " + tr("blockatlas.button.on") : "○ " + tr("blockatlas.button.off"),
                "○ " + longer(tr("blockatlas.button.on"), tr("blockatlas.button.off")),
                () -> cfg.enabled, Ui.OK, () -> {
                    cfg.enabled = !cfg.enabled;
                    cfg.save();
                    BlockScanner.get().requestRescan();
                });
        headerLeftLimit = bx - 8;

        int searchY = py + 36;
        search = new SearchField(listX, searchY, listW, 20, this::onQueryChanged);
        search.setPlaceholder(tr("blockatlas.search.placeholder"));
        search.setText(lastQuery);
        search.setFocused(true);

        // category tabs (wrap to a second row on narrow screens)
        int tx = listX, ty = searchY + 26;
        for (Category c : Category.values()) {
            String widest = c == Category.ACTIVE ? c.label() + " 99" : c.label();
            int w = font.width(widest) + 16;
            if (tx + w > listX + listW && tx > listX) {
                tx = listX;
                ty += 20;
            }
            final Category cc = c;
            tabButtons.add(new PillButton(tx, ty, w, 16, () -> tabLabel(cc), () -> category == cc, Ui.ACCENT,
                    () -> setCategory(cc)));
            tx += w + 4;
        }

        listY = ty + 24;
        footerY = py + ph - 16;
        listH = Math.max(40, footerY - 6 - listY);
        grid = new BlockGrid(listX, listY, listW, listH, this::onCard);
        grid.setEmptyMessage(tr("blockatlas.empty"));

        // sidebar
        rangeCardY = searchY;
        rangeSlider = new Slider(sideX + 12, rangeCardY + 26, sideW - 24, false, this::onRangeDrag);
        rangeSlider.setValue(rangeToSlider(cfg.range));

        colorCardY = rangeCardY + 72;
        hueSlider = new Slider(sideX + 12, colorCardY + 44, sideW - 24, true, this::onHue);
        alphaSlider = new Slider(sideX + 12, colorCardY + 68, sideW - 24, false, this::onAlpha);
        swatchY = colorCardY + 82;

        activeCardY = colorCardY + 112;
        activeCardBottom = py + ph - 12;

        refilter(false);
        grid.setScrollPosition(lastScroll);
        if (focused != null) setFocused(focused);
    }

    private int addHeaderButton(int right, java.util.function.Supplier<String> label, String widestLabel,
                                java.util.function.BooleanSupplier active, int accent, Runnable action) {
        int w = font.width(widestLabel) + 18;
        int x = right - w;
        headerButtons.add(new PillButton(x, py + 9, w, 16, label, active, accent, action));
        return x - 5;
    }

    private static String longer(String a, String b) {
        return a.length() >= b.length() ? a : b;
    }

    private String tabLabel(Category c) {
        return c == Category.ACTIVE ? c.label() + " " + hm.activeBlocks().size() : c.label();
    }

    // ---- filtering --------------------------------------------------------------------------

    private void onQueryChanged(String q) {
        lastQuery = q;
        refilter(true);
    }

    private void setCategory(Category c) {
        category = c;
        lastCategory = c;
        refilter(true);
    }

    private void refilter(boolean resetScroll) {
        String q = search.text().trim().toLowerCase(Locale.ROOT);
        String[] tokens = q.isEmpty() ? new String[0] : q.split("\\s+");
        List<BlockCatalog.Entry> favs = new ArrayList<>();
        List<BlockCatalog.Entry> rest = new ArrayList<>();
        for (BlockCatalog.Entry e : catalog.entries()) {
            if (category == Category.ACTIVE) {
                if (!hm.isActive(e.block())) continue;
            } else if (category != Category.ALL && !e.categories().contains(category)) {
                continue;
            }
            if (!e.matches(tokens)) continue;
            (cfg.isFavorite(e.id()) ? favs : rest).add(e);
        }
        favs.addAll(rest); // favourites first, both groups already alphabetical
        resultCount = favs.size();
        search.setSuffix(tokens.length == 0 && category == Category.ALL ? ""
                : tr("blockatlas.search.results", Ui.formatCount(resultCount)));
        grid.setEntries(favs, resetScroll);
    }

    // ---- actions ----------------------------------------------------------------------------

    private void onCard(BlockCatalog.Entry e, BlockGrid.Action action) {
        switch (action) {
            case TOGGLE -> {
                hm.toggle(e.block());
                setFocused(e);
                if (category == Category.ACTIVE) refilter(false);
            }
            case FOCUS -> setFocused(e);
            case FAVORITE -> {
                cfg.toggleFavorite(e.id());
                refilter(false);
            }
        }
    }

    private void setFocused(BlockCatalog.Entry e) {
        focused = e;
        grid.setFocused(e);
        if (e != null) {
            BlockAtlasConfig.BlockColor c = cfg.colorFor(e.id());
            hueSlider.setValue(ColorUtil.hue(c.rgb));
            alphaSlider.setValue((c.alpha - 0.05) / 0.95);
        }
    }

    private void clearAll() {
        hm.clearAll();
        if (category == Category.ACTIVE) refilter(true);
    }

    private static double rangeToSlider(int range) {
        return (range - BlockAtlasConfig.MIN_RANGE) / (double) (BlockAtlasConfig.MAX_RANGE - BlockAtlasConfig.MIN_RANGE);
    }

    private void onRangeDrag(double v) {
        int raw = BlockAtlasConfig.MIN_RANGE
                + (int) Math.round(v * (BlockAtlasConfig.MAX_RANGE - BlockAtlasConfig.MIN_RANGE) / 4.0) * 4;
        cfg.range = Math.max(BlockAtlasConfig.MIN_RANGE, Math.min(BlockAtlasConfig.MAX_RANGE, raw));
    }

    /** Called when the range slider is released: persist, rescan, and warn about expensive ranges. */
    private void commitRange() {
        cfg.save();
        BlockScanner.get().requestRescan();
        if (cfg.range > BlockAtlasConfig.WARN_RANGE && minecraft != null && minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.translatable("blockatlas.chat.range_warning", cfg.range)
                    .withStyle(ChatFormatting.GOLD));
        }
    }

    private void onHue(double v) {
        if (focused == null) return;
        BlockAtlasConfig.BlockColor c = cfg.colorFor(focused.id());
        cfg.setColor(focused.id(), ColorUtil.hsv((float) v, 0.78f, 1f), c.alpha);
        hm.markDirty();
    }

    private void onAlpha(double v) {
        if (focused == null) return;
        BlockAtlasConfig.BlockColor c = cfg.colorFor(focused.id());
        cfg.setColor(focused.id(), c.rgb, (float) (0.05 + v * 0.95));
        hm.markDirty();
    }

    private int loadedRadius() {
        return minecraft == null ? 0 : minecraft.options.getEffectiveRenderDistance() * 16;
    }

    // ---- rendering --------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // Fully custom drawing; no vanilla background blur or widgets.
        Ui.rect(g, 0, 0, width, height, Ui.BACKDROP);
        Ui.card(g, px, py, px + pw, py + ph, 8, Ui.PANEL, Ui.PANEL_BORDER);

        // header
        int tx = px + 14, ty = py + 13;
        Ui.text(g, font, "Block", tx, ty, Ui.TEXT);
        int w1 = font.width("Block");
        Ui.text(g, font, "Atlas", tx + w1, ty, Ui.ACCENT);
        int subX = tx + w1 + font.width("Atlas") + 10;
        String sub = tr("blockatlas.subtitle", Ui.formatCount(catalog.size()), hm.activeBlocks().size());
        Ui.text(g, font, Ui.trim(font, sub, headerLeftLimit - subX), subX, ty, Ui.TEXT_FAINT);
        for (PillButton b : headerButtons) b.render(g, font, mouseX, mouseY);
        Ui.rect(g, px + 1, py + 30, px + pw - 1, py + 31, Ui.DIVIDER);

        // browser
        search.render(g, font, mouseX, mouseY);
        for (PillButton b : tabButtons) b.render(g, font, mouseX, mouseY);
        grid.render(g, font, mouseX, mouseY);
        Ui.text(g, font, Ui.trim(font, tr("blockatlas.footer"), listW), listX, footerY, Ui.TEXT_FAINT);

        // sidebar
        renderRangeCard(g, mouseX, mouseY);
        renderColorCard(g, mouseX, mouseY);
        renderActiveCard(g, mouseX, mouseY);
    }

    private void renderRangeCard(GuiGraphicsExtractor g, int mx, int my) {
        int x = sideX, y = rangeCardY, x2 = sideX + sideW;
        Ui.card(g, x, y, x2, y + 64, 6, Ui.SURFACE, Ui.SURFACE_BORDER);
        boolean high = cfg.range > BlockAtlasConfig.WARN_RANGE;
        Ui.text(g, font, tr("blockatlas.range"), x + 12, y + 9, Ui.TEXT_FAINT);
        Ui.textRight(g, font, cfg.range + " m", x2 - 12, y + 9, high ? Ui.WARN : Ui.TEXT);
        rangeSlider.render(g, mx, my, high ? Ui.WARN : Ui.ACCENT);

        int lineW = sideW - 24;
        if (high) {
            Ui.text(g, font, Ui.trim(font, "⚠ " + tr("blockatlas.range.warn"), lineW), x + 12, y + 38, Ui.WARN);
        } else {
            Ui.text(g, font, Ui.trim(font, tr("blockatlas.range.ok"), lineW), x + 12, y + 38, Ui.TEXT_FAINT);
        }
        int loaded = loadedRadius();
        boolean limited = cfg.range > loaded;
        String info = limited ? tr("blockatlas.range.loaded_limited", loaded) : tr("blockatlas.range.loaded", loaded);
        Ui.text(g, font, Ui.trim(font, info, lineW), x + 12, y + 50, limited ? Ui.TEXT_DIM : Ui.TEXT_FAINT);
    }

    private void renderColorCard(GuiGraphicsExtractor g, int mx, int my) {
        int x = sideX, y = colorCardY, x2 = sideX + sideW;
        Ui.card(g, x, y, x2, y + 104, 6, Ui.SURFACE, Ui.SURFACE_BORDER);

        if (focused == null) {
            Ui.text(g, font, tr("blockatlas.color"), x + 12, y + 9, Ui.TEXT_FAINT);
            Ui.textCenter(g, font, tr("blockatlas.color.none"), x + sideW / 2, y + 44, Ui.TEXT_DIM);
            Ui.textCenter(g, font, tr("blockatlas.color.none2"), x + sideW / 2, y + 56, Ui.TEXT_DIM);
            return;
        }

        BlockAtlasConfig.BlockColor c = cfg.colorFor(focused.id());
        int opaque = ColorUtil.opaque(c.rgb);
        Ui.icon(g, font, focused.icon(), focused.name(), c.rgb, x + 10, y + 7);
        Ui.text(g, font, Ui.trim(font, focused.name(), sideW - 30 - 26), x + 30, y + 7, Ui.TEXT);
        Ui.text(g, font, Ui.trim(font, focused.shortId(), sideW - 30 - 26), x + 30, y + 17, Ui.TEXT_FAINT);
        Ui.card(g, x2 - 24, y + 9, x2 - 10, y + 23, 4, opaque, 0xFF000000 | ColorUtil.mix(opaque, 0xFFFFFFFF, 0.4f));

        Ui.text(g, font, tr("blockatlas.color.hue"), x + 12, y + 32, Ui.TEXT_FAINT);
        hueSlider.render(g, mx, my, Ui.ACCENT);
        Ui.text(g, font, tr("blockatlas.color.opacity"), x + 12, y + 56, Ui.TEXT_FAINT);
        Ui.textRight(g, font, Math.round(c.alpha * 100) + "%", x2 - 12, y + 56, Ui.TEXT_DIM);
        alphaSlider.render(g, mx, my, opaque);

        int size = swatchSize();
        for (int i = 0; i < PRESETS.length; i++) {
            int sx = swatchX(i, size);
            boolean selected = (c.rgb & 0xFFFFFF) == PRESETS[i];
            boolean hover = mx >= sx && mx < sx + size && my >= swatchY && my < swatchY + size;
            int border = selected ? Ui.TEXT : hover ? Ui.TEXT_DIM : Ui.PANEL;
            Ui.card(g, sx, swatchY, sx + size, swatchY + size, 3, ColorUtil.opaque(PRESETS[i]), border);
        }
    }

    private int swatchSize() {
        return Math.max(8, Math.min(14, (sideW - 24 - 7 * 4) / PRESETS.length));
    }

    private int swatchX(int i, int size) {
        int total = PRESETS.length * size + (PRESETS.length - 1) * 4;
        int start = sideX + (sideW - total) / 2;
        return start + i * (size + 4);
    }

    private int visibleActiveRows() {
        return Math.max(0, (activeCardBottom - 6 - (activeCardY + 24)) / ROW_H);
    }

    private void renderActiveCard(GuiGraphicsExtractor g, int mx, int my) {
        int x = sideX, y = activeCardY, x2 = sideX + sideW, bottom = activeCardBottom;
        if (bottom - y < 44) return;
        Ui.card(g, x, y, x2, bottom, 6, Ui.SURFACE, Ui.SURFACE_BORDER);

        List<Block> blocks = new ArrayList<>(hm.activeBlocks());
        Ui.text(g, font, tr("blockatlas.active") + " · " + blocks.size(), x + 12, y + 9, Ui.TEXT_FAINT);
        String status;
        int statusColor;
        if (!cfg.enabled) {
            status = tr("blockatlas.status.paused");
            statusColor = Ui.WARN;
        } else if (BlockScanner.get().isScanning()) {
            status = tr("blockatlas.status.scanning", Math.round(BlockScanner.get().progress() * 100));
            statusColor = Ui.ACCENT;
        } else {
            status = blocks.isEmpty() ? "" : tr("blockatlas.status.live");
            statusColor = Ui.OK;
        }
        Ui.textRight(g, font, status, x2 - 12, y + 9, statusColor);

        if (blocks.isEmpty()) {
            Ui.textCenter(g, font, tr("blockatlas.active.empty"), x + sideW / 2, y + 34, Ui.TEXT_DIM);
            return;
        }

        int rows = visibleActiveRows();
        activeScroll = Math.max(0, Math.min(activeScroll, Math.max(0, blocks.size() - rows)));
        for (int i = 0; i < rows && i + activeScroll < blocks.size(); i++) {
            Block b = blocks.get(i + activeScroll);
            BlockCatalog.Entry e = catalog.entry(b);
            if (e == null) continue;
            int ry = y + 24 + i * ROW_H;
            boolean hover = mx >= x + 4 && mx < x2 - 4 && my >= ry && my < ry + ROW_H;
            if (hover || e == focused) Ui.roundRect(g, x + 4, ry, x2 - 4, ry + ROW_H, 4, Ui.SURFACE_HOVER);

            int color = ColorUtil.opaque(cfg.colorFor(e.id()).rgb);
            Ui.roundRect(g, x + 10, ry + 5, x + 16, ry + 11, 2, color);

            HighlightManager.Stats s = hm.stats(b);
            String right = s == null ? "…" : Ui.formatCount(s.count()) + (s.capped() ? "+" : "")
                    + (s.count() > 0 ? " · " + Ui.formatDistance(s.nearestDistance()) : "");
            int rightEdge = x2 - (hover ? 22 : 10);
            int rw = font.width(right);
            Ui.text(g, font, Ui.trim(font, e.name(), rightEdge - rw - 6 - (x + 22)), x + 22, ry + 4, Ui.TEXT);
            Ui.textRight(g, font, right, rightEdge, ry + 4, Ui.TEXT_DIM);
            if (hover) {
                boolean overX = mx >= x2 - 20;
                Ui.textCenter(g, font, "✕", x2 - 13, ry + 4, overX ? Ui.DANGER : Ui.TEXT_FAINT);
            }
        }
        if (blocks.size() > rows && rows > 0) {
            int trackTop = y + 24, trackH = rows * ROW_H;
            int thumbH = Math.max(8, trackH * rows / blocks.size());
            int thumbY = trackTop + (trackH - thumbH) * activeScroll / Math.max(1, blocks.size() - rows);
            Ui.roundRect(g, x2 - 4, thumbY, x2 - 2, thumbY + thumbH, 1, 0xFF333946);
        }
    }

    /** Returns true if the click hit the active list. */
    private boolean clickActiveList(double mx, double my, int button) {
        int x = sideX, y = activeCardY, x2 = sideX + sideW;
        if (activeCardBottom - y < 44 || mx < x || mx >= x2 || my < y + 24 || my >= activeCardBottom) return false;
        int index = (int) ((my - (y + 24)) / ROW_H);
        if (index >= visibleActiveRows()) return false;
        List<Block> blocks = new ArrayList<>(hm.activeBlocks());
        int i = index + activeScroll;
        if (i < 0 || i >= blocks.size()) return true;
        Block b = blocks.get(i);
        if (button == 0 && mx >= x2 - 20) {
            hm.remove(b);
            if (category == Category.ACTIVE) refilter(false);
        } else {
            setFocused(catalog.entry(b));
        }
        return true;
    }

    // ---- input ------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = event.button();

        if (button == 0) {
            if (search.mouseClicked(mx, my)) return true;
            for (PillButton b : headerButtons) if (b.mouseClicked(mx, my)) return true;
            for (PillButton b : tabButtons) if (b.mouseClicked(mx, my)) return true;
            if (rangeSlider.mouseClicked(mx, my)) {
                dragging = rangeSlider;
                return true;
            }
            if (focused != null) {
                if (hueSlider.mouseClicked(mx, my)) {
                    dragging = hueSlider;
                    return true;
                }
                if (alphaSlider.mouseClicked(mx, my)) {
                    dragging = alphaSlider;
                    return true;
                }
                int size = swatchSize();
                for (int i = 0; i < PRESETS.length; i++) {
                    int sx = swatchX(i, size);
                    if (mx >= sx && mx < sx + size && my >= swatchY && my < swatchY + size) {
                        BlockAtlasConfig.BlockColor c = cfg.colorFor(focused.id());
                        cfg.setColor(focused.id(), PRESETS[i], c.alpha);
                        hueSlider.setValue(ColorUtil.hue(PRESETS[i]));
                        hm.markDirty();
                        return true;
                    }
                }
            }
        }
        if (clickActiveList(mx, my, button)) return true;
        if (grid.mouseClicked(mx, my, button)) return true;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging != null) {
            dragging.drag(event.x());
            return true;
        }
        if (grid.mouseDragged(event.y())) return true;
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            Slider released = dragging;
            dragging = null;
            released.release();
            if (released == rangeSlider) commitRange();
            return true;
        }
        grid.mouseReleased();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (grid.contains(mx, my)) {
            grid.scroll(scrollY);
            return true;
        }
        if (mx >= sideX && mx < sideX + sideW && my >= activeCardY && my < activeCardBottom) {
            activeScroll -= (int) Math.signum(scrollY);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        int mods = event.modifiers();
        boolean ctrl = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;

        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (search.isFocused() && !search.text().isEmpty()) {
                search.setText("");
                onQueryChanged("");
                return true;
            }
            onClose();
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_F) {
            search.setFocused(true);
            return true;
        }
        if (search.keyPressed(key, mods)) return true;
        if (!search.isFocused() && BlockAtlasClient.openKey.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        int cp = event.codepoint();
        if (!search.isFocused()) {
            // "type to search": any printable key focuses the search bar
            if (cp < 32) return false;
            search.setFocused(true);
        }
        return search.charTyped(cp) || super.charTyped(event);
    }

    @Override
    public void onClose() {
        lastScroll = grid == null ? 0 : grid.scrollPosition();
        cfg.save();
        super.onClose();
    }
}
