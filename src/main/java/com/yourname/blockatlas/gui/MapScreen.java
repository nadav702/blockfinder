package com.yourname.blockatlas.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.yourname.blockatlas.BlockAtlasClient;
import com.yourname.blockatlas.config.BlockAtlasConfig;
import com.yourname.blockatlas.gui.widget.PillButton;
import com.yourname.blockatlas.gui.widget.Slider;
import com.yourname.blockatlas.scan.DeepScanner;
import com.yourname.blockatlas.gui.widget.Ui;
import com.yourname.blockatlas.highlight.HighlightManager;
import com.yourname.blockatlas.highlight.Target;
import com.yourname.blockatlas.scan.BlockMemory;
import com.yourname.blockatlas.util.ColorUtil;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Top-down map (M) of every remembered block in this world and dimension, at any distance.
 * Drag to pan, scroll to zoom, click a dot to make it the navigation target.
 */
public class MapScreen extends ScaledScreen {
    private static final double[] GRID_STEPS = {16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192};
    private static final double MIN_ZOOM = 1 / 32.0, MAX_ZOOM = 8.0;
    private static final int SIDE_W = 168;
    private static final int ROW_H = 16;
    private static final int MAX_MARKERS = 25000;
    private static final int DEEP_H = 118;

    private static final Set<Block> hidden = new HashSet<>();
    private static double zoom = 1.0;

    private final Screen parent;
    private final BlockAtlasConfig cfg = BlockAtlasConfig.get();
    private final List<PillButton> buttons = new ArrayList<>();

    private double centerX, centerZ;
    private boolean follow = true;
    private boolean panning;
    private double panStartMX, panStartMY, panStartCX, panStartCZ;
    private boolean panMoved;
    private int listScroll;
    private long openedAt;

    // layout
    private int px, py, pw, ph, mapX, mapY, mapW, mapH, sideX, listY, listBottom, deepY;
    private Slider deepRadius;
    private boolean draggingRadius;
    private PillButton genToggle, startStop;
    private String deepError;

    // per-frame hover
    private Block hoverBlock;
    private long hoverPos;
    private boolean hovering;
    private int[] cellStamp = new int[0];
    private int frameId;

    public MapScreen(Screen parent) {
        super(Component.translatable("blockatlas.map.title"));
        this.parent = parent;
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    @Override
    protected int minWidth() {
        return 540;
    }

    @Override
    protected int minHeight() {
        return 340; // sidebar list + deep-scan card both need room
    }

    @Override
    protected void initScaled() {
        openedAt = System.currentTimeMillis();
        buttons.clear();
        pw = sw - 16;
        ph = sh - 16;
        px = 8;
        py = 8;
        sideX = px + 10;
        mapX = sideX + SIDE_W + 10;
        mapY = py + 34;
        mapW = px + pw - 10 - mapX;
        mapH = py + ph - 10 - mapY - 14;

        if (minecraft.player != null && follow) {
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
        }

        // header buttons (right)
        int bx = px + pw - 10;
        bx = addButton(bx, "⚙", () -> false, () -> minecraft.gui.setScreen(new SettingsScreen(this)));
        bx = addButton(bx, tr("blockatlas.map.clear_target"), Target::isSet, Target::clear);
        bx = addButton(bx, tr("blockatlas.map.center"), () -> follow, () -> follow = true);
        bx = addButton(bx, "+", () -> false, () -> zoomAt(mapX + mapW / 2.0, mapY + mapH / 2.0, 2.0));
        addButton(bx, "−", () -> false, () -> zoomAt(mapX + mapW / 2.0, mapY + mapH / 2.0, 0.5));

        listY = mapY + 30;
        deepY = py + ph - 10 - DEEP_H;
        listBottom = deepY - 6;

        deepRadius = new Slider(sideX + 10, deepY + 34, SIDE_W - 20, false, v -> {
            cfg.deepScanRadius = 500 + (int) Math.round(v * 4500 / 250.0) * 250;
        });
        deepRadius.setValue((cfg.deepScanRadius - 500) / 4500.0);
        genToggle = new PillButton(sideX + 10, deepY + 46, SIDE_W - 20, 14,
                () -> tr(cfg.deepScanGenerate ? "blockatlas.deep.gen_on" : "blockatlas.deep.gen_off"),
                () -> cfg.deepScanGenerate, Ui.WARN, () -> {
                    cfg.deepScanGenerate = !cfg.deepScanGenerate;
                    cfg.save();
                });
        startStop = new PillButton(sideX + 10, deepY + 64, SIDE_W - 20, 16,
                () -> tr(DeepScanner.get().running() ? "blockatlas.deep.stop" : "blockatlas.deep.start"),
                () -> DeepScanner.get().running(), Ui.ACCENT, () -> {
                    DeepScanner ds = DeepScanner.get();
                    if (ds.running()) {
                        ds.stop();
                    } else {
                        cfg.save();
                        deepError = ds.start(minecraft, cfg.deepScanRadius, cfg.deepScanGenerate);
                    }
                });
    }

    private int addButton(int right, String label, java.util.function.BooleanSupplier active, Runnable action) {
        int w = font.width(label) + 16;
        buttons.add(new PillButton(right - w, py + 9, w, 16, () -> label, active, Ui.ACCENT, action));
        return right - w - 5;
    }

    // ---- data -------------------------------------------------------------------------------

    /** Every block type that has something to show: remembered ones first, then active ones. */
    private List<Block> types() {
        LinkedHashSet<Block> set = new LinkedHashSet<>(BlockMemory.get().all().keySet());
        set.addAll(HighlightManager.get().activeBlocks());
        List<Block> list = new ArrayList<>(set);
        BlockCatalog catalog = BlockCatalog.get();
        list.sort((a, b) -> {
            BlockCatalog.Entry ea = catalog.entry(a), eb = catalog.entry(b);
            return (ea == null ? "" : ea.name()).compareToIgnoreCase(eb == null ? "" : eb.name());
        });
        return list;
    }

    private int countOf(Block b) {
        int remembered = BlockMemory.get().count(b);
        return remembered > 0 ? remembered : HighlightManager.get().results(b).length;
    }

    // ---- coordinates ------------------------------------------------------------------------

    private double screenX(double wx) {
        return mapX + mapW / 2.0 + (wx - centerX) * zoom;
    }

    private double screenY(double wz) {
        return mapY + mapH / 2.0 + (wz - centerZ) * zoom;
    }

    private double worldX(double sx) {
        return centerX + (sx - (mapX + mapW / 2.0)) / zoom;
    }

    private double worldZ(double sy) {
        return centerZ + (sy - (mapY + mapH / 2.0)) / zoom;
    }

    private void zoomAt(double sx, double sy, double factor) {
        double wx = worldX(sx), wz = worldZ(sy);
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * factor));
        if (!follow || !(sx == mapX + mapW / 2.0 && sy == mapY + mapH / 2.0)) {
            centerX = wx - (sx - (mapX + mapW / 2.0)) / zoom;
            centerZ = wz - (sy - (mapY + mapH / 2.0)) / zoom;
            if (sx != mapX + mapW / 2.0 || sy != mapY + mapH / 2.0) follow = false;
        }
    }

    private boolean inMap(double x, double y) {
        return x >= mapX && x < mapX + mapW && y >= mapY && y < mapY + mapH;
    }

    // ---- rendering --------------------------------------------------------------------------

    @Override
    protected void extractScaled(GuiGraphicsExtractor g, int mx, int my, float delta) {
        LocalPlayer player = minecraft.player;
        if (player != null && follow) {
            centerX = player.getX();
            centerZ = player.getZ();
        }

        Ui.card(g, px, py, px + pw, py + ph, 8, Ui.PANEL, Ui.PANEL_BORDER);
        Ui.text(g, font, "Block", px + 14, py + 13, Ui.TEXT);
        Ui.text(g, font, "Atlas", px + 14 + font.width("Block"), py + 13, Ui.ACCENT);
        Ui.text(g, font, tr("blockatlas.map.title"), px + 14 + font.width("BlockAtlas") + 8, py + 13, Ui.TEXT_DIM);
        for (PillButton b : buttons) b.render(g, font, mx, my);
        Ui.rect(g, px + 1, py + 30, px + pw - 1, py + 31, Ui.DIVIDER);

        renderSidebar(g, mx, my);
        renderDeepCard(g, mx, my);
        renderMap(g, mx, my, player);
    }

    private void renderSidebar(GuiGraphicsExtractor g, int mx, int my) {
        int x = sideX, x2 = sideX + SIDE_W;
        Ui.card(g, x, mapY, x2, listBottom, 6, Ui.SURFACE, Ui.SURFACE_BORDER);
        Ui.text(g, font, tr("blockatlas.map.remembered"), x + 10, mapY + 9, Ui.TEXT_FAINT);
        String world = BlockMemory.get().worldName();
        Ui.text(g, font, Ui.trim(font, world, SIDE_W - 20), x + 10, mapY + 19, Ui.TEXT_FAINT);

        List<Block> types = types();
        int rows = Math.max(0, (listBottom - 8 - listY) / ROW_H);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, types.size() - rows)));
        if (types.isEmpty()) {
            int cy = listY + 10;
            for (String line : wrap(tr("blockatlas.map.empty"), SIDE_W - 20)) {
                Ui.text(g, font, line, x + 10, cy, Ui.TEXT_DIM);
                cy += 11;
            }
            return;
        }
        BlockCatalog catalog = BlockCatalog.get();
        for (int i = 0; i < rows && i + listScroll < types.size(); i++) {
            Block b = types.get(i + listScroll);
            BlockCatalog.Entry e = catalog.entry(b);
            if (e == null) continue;
            int ry = listY + i * ROW_H;
            boolean visible = !hidden.contains(b);
            boolean hover = mx >= x + 4 && mx < x2 - 4 && my >= ry && my < ry + ROW_H;
            if (hover) Ui.roundRect(g, x + 4, ry, x2 - 4, ry + ROW_H, 4, Ui.SURFACE_HOVER);
            int color = ColorUtil.opaque(cfg.colorFor(e.id()).rgb);
            Ui.roundRect(g, x + 10, ry + 4, x + 18, ry + 12, 2, visible ? color : ColorUtil.mix(Ui.SURFACE, color, 0.25f));
            String cnt = Ui.formatCount(countOf(b));
            int cw = font.width(cnt);
            Ui.text(g, font, Ui.trim(font, e.name(), SIDE_W - 34 - cw - 6), x + 24, ry + 4, visible ? Ui.TEXT : Ui.TEXT_FAINT);
            Ui.textRight(g, font, cnt, x2 - 10, ry + 4, visible ? Ui.TEXT_DIM : Ui.TEXT_FAINT);
        }
    }

    private void renderDeepCard(GuiGraphicsExtractor g, int mx, int my) {
        int x = sideX, x2 = sideX + SIDE_W, y = deepY;
        Ui.card(g, x, y, x2, y + DEEP_H, 6, Ui.SURFACE, Ui.SURFACE_BORDER);
        Ui.text(g, font, tr("blockatlas.deep.title"), x + 10, y + 8, Ui.TEXT_FAINT);
        if (!DeepScanner.available(minecraft)) {
            int cy = y + 26;
            for (String line : wrap(tr("blockatlas.deep.sp_only"), SIDE_W - 20)) {
                Ui.text(g, font, line, x + 10, cy, Ui.TEXT_DIM);
                cy += 11;
            }
            return;
        }
        DeepScanner ds = DeepScanner.get();
        Ui.text(g, font, tr("blockatlas.deep.radius"), x + 10, y + 21, Ui.TEXT_DIM);
        Ui.textRight(g, font, cfg.deepScanRadius + " m", x2 - 10, y + 21, Ui.TEXT);
        deepRadius.render(g, mx, my, Ui.ACCENT);
        genToggle.render(g, font, mx, my);
        startStop.render(g, font, mx, my);

        int ly = y + 84;
        String l1, l2 = "";
        int c1 = Ui.TEXT_DIM;
        if (deepError != null && !ds.running()) {
            l1 = tr(deepError);
            c1 = Ui.WARN;
        } else if (ds.state() == DeepScanner.State.IDLE) {
            l1 = tr("blockatlas.deep.idle");
        } else {
            int total = Math.max(1, ds.total());
            int done = Math.min(total, ds.done());
            float frac = done / (float) total;
            Ui.roundRect(g, x + 10, ly, x2 - 10, ly + 3, 1, Ui.TRACK);
            Ui.roundRect(g, x + 10, ly, x + 10 + Math.round((SIDE_W - 20) * frac), ly + 3, 1,
                    ds.state() == DeepScanner.State.DONE ? Ui.OK : Ui.ACCENT);
            ly += 6;
            l1 = switch (ds.state()) {
                case RUNNING -> tr("blockatlas.deep.progress", Ui.formatCount(done), Ui.formatCount(total), Math.round(frac * 100));
                case DONE -> tr("blockatlas.deep.done");
                default -> tr("blockatlas.deep.stopped");
            };
            l2 = tr("blockatlas.deep.stats", Ui.formatCount((int) Math.min(Integer.MAX_VALUE, ds.found())),
                    Ui.formatCount(ds.generated()), Ui.formatCount(ds.generatesMissing() ? 0 : ds.missing()));
        }
        Ui.text(g, font, Ui.trim(font, l1, SIDE_W - 20), x + 10, ly, c1);
        if (!l2.isEmpty()) Ui.text(g, font, Ui.trim(font, l2, SIDE_W - 20), x + 10, ly + 11, Ui.TEXT_FAINT);
    }

    private List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String trial = line.isEmpty() ? word : line + " " + word;
            if (font.width(trial) > width && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(trial);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    private void renderMap(GuiGraphicsExtractor g, int mx, int my, LocalPlayer player) {
        int x1 = mapX, y1 = mapY, x2 = mapX + mapW, y2 = mapY + mapH;
        Ui.card(g, x1, y1, x2, y2, 6, 0xFF0A0C10, Ui.SURFACE_BORDER);
        g.enableScissor(x1 + 1, y1 + 1, x2 - 1, y2 - 1);

        // grid
        double step = GRID_STEPS[GRID_STEPS.length - 1];
        for (double s : GRID_STEPS) {
            if (s * zoom >= 56) {
                step = s;
                break;
            }
        }
        double wx0 = worldX(x1), wx1 = worldX(x2), wz0 = worldZ(y1), wz1 = worldZ(y2);
        for (double gx = Math.floor(wx0 / step) * step; gx <= wx1; gx += step) {
            int sx = (int) Math.round(screenX(gx));
            Ui.rect(g, sx, y1, sx + 1, y2, gx == 0 ? 0x553A4256 : 0x2A2A3140);
            Ui.text(g, font, String.valueOf((long) gx), sx + 3, y1 + 4, 0xFF4A5263);
        }
        for (double gz = Math.floor(wz0 / step) * step; gz <= wz1; gz += step) {
            int sy = (int) Math.round(screenY(gz));
            Ui.rect(g, x1, sy, x2, sy + 1, gz == 0 ? 0x553A4256 : 0x2A2A3140);
            Ui.text(g, font, String.valueOf((long) gz), x1 + 4, sy + 3, 0xFF4A5263);
        }

        // loaded area (what the client can currently scan)
        if (player != null) {
            int r = minecraft.options.getEffectiveRenderDistance() * 16;
            int lx1 = (int) screenX(player.getX() - r), lx2 = (int) screenX(player.getX() + r);
            int ly1 = (int) screenY(player.getZ() - r), ly2 = (int) screenY(player.getZ() + r);
            Ui.rect(g, lx1, ly1, lx2, ly2, 0x0E7C9CFF);
            g.outline(lx1, ly1, lx2 - lx1, ly2 - ly1, 0x337C9CFF);
        }

        // deep scan area
        DeepScanner ds = DeepScanner.get();
        if (ds.state() != DeepScanner.State.IDLE) {
            double r = ds.radius();
            int segs = 180;
            int col = ds.running() ? 0x887C9CFF : 0x555BD69A;
            for (int i = 0; i < segs; i++) {
                double a = i * Math.PI * 2 / segs;
                int sx = (int) screenX(ds.originX() + Math.cos(a) * r);
                int sy = (int) screenY(ds.originZ() + Math.sin(a) * r);
                Ui.rect(g, sx, sy, sx + 2, sy + 2, col);
            }
        }

        // markers
        hovering = false;
        double bestD2 = 7 * 7;
        int cellsW = mapW / 2 + 2, cellsH = mapH / 2 + 2;
        if (cellStamp.length < cellsW * cellsH) cellStamp = new int[cellsW * cellsH];
        frameId++;
        int drawn = 0;
        int size = zoom >= 2 ? 5 : zoom >= 0.5 ? 4 : 3;
        boolean mouseInMap = inMap(mx, my);

        for (Block b : types()) {
            if (hidden.contains(b)) continue;
            BlockCatalog.Entry e = BlockCatalog.get().entry(b);
            if (e == null) continue;
            int color = ColorUtil.opaque(cfg.colorFor(e.id()).rgb);
            LongOpenHashSet remembered = BlockMemory.get().all().get(b);
            long[] live = HighlightManager.get().results(b);
            LongIterator it = remembered != null ? remembered.iterator() : null;
            int liveIdx = 0;
            while (drawn < MAX_MARKERS) {
                long p;
                if (it != null && it.hasNext()) p = it.nextLong();
                else if (liveIdx < live.length) p = live[liveIdx++];
                else break;
                double sx = screenX(BlockPos.getX(p) + 0.5), sy = screenY(BlockPos.getZ(p) + 0.5);
                if (sx < x1 || sx >= x2 || sy < y1 || sy >= y2) continue;
                if (mouseInMap) {
                    double ddx = sx - mx, ddy = sy - my, d2 = ddx * ddx + ddy * ddy;
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        hovering = true;
                        hoverBlock = b;
                        hoverPos = p;
                    }
                }
                int cell = ((int) (sy - y1) >> 1) * cellsW + ((int) (sx - x1) >> 1);
                if (cell < 0 || cell >= cellStamp.length || cellStamp[cell] == frameId) continue;
                cellStamp[cell] = frameId;
                int ix = (int) sx, iy = (int) sy, h0 = size / 2;
                Ui.rect(g, ix - h0 - 1, iy - h0 - 1, ix - h0 + size + 1, iy - h0 + size + 1, 0xCC000000);
                Ui.rect(g, ix - h0, iy - h0, ix - h0 + size, iy - h0 + size, color);
                drawn++;
            }
        }

        // target pin
        if (Target.isSet()) {
            long t = Target.pos();
            int tx = (int) screenX(BlockPos.getX(t) + 0.5), ty = (int) screenY(BlockPos.getZ(t) + 0.5);
            int pulse = 6 + (int) ((System.currentTimeMillis() / 120) % 4);
            g.outline(tx - pulse, ty - pulse, pulse * 2, pulse * 2, Ui.STAR);
            Ui.rect(g, tx - 2, ty - 2, tx + 3, ty + 3, Ui.STAR);
        }

        // player arrow
        if (player != null) {
            int sx = (int) Math.round(screenX(player.getX())), sy = (int) Math.round(screenY(player.getZ()));
            g.pose().pushMatrix();
            g.pose().translate(sx, sy);
            g.pose().rotate((float) Math.toRadians(player.getYRot() + 180));
            for (int k = 0; k <= 7; k++) {
                int hw = k / 2 + (k > 0 ? 1 : 0);
                Ui.rect(g, -hw - 1, -7 + k, hw + 1, -6 + k, 0xFF000000);
            }
            for (int k = 0; k <= 6; k++) {
                int hw = k / 2;
                Ui.rect(g, -hw, -6 + k, hw + 1, -5 + k, 0xFFFFFFFF);
            }
            Ui.rect(g, -1, 1, 2, 3, 0xFFFFFFFF);
            g.pose().popMatrix();
        }

        g.disableScissor();

        // bottom info line
        int iy = y2 + 4;
        if (mouseInMap) {
            String cur = "X " + (long) Math.floor(worldX(mx)) + "   Z " + (long) Math.floor(worldZ(my));
            Ui.text(g, font, cur, x1 + 2, iy, Ui.TEXT_DIM);
        }
        String hint = tr("blockatlas.map.hint");
        Ui.textRight(g, font, Ui.trim(font, hint, mapW - 120), x2 - 2, iy, Ui.TEXT_FAINT);

        if (hovering) drawTooltip(g, mx, my, player);
    }

    private void drawTooltip(GuiGraphicsExtractor g, int mx, int my, LocalPlayer player) {
        BlockCatalog.Entry e = BlockCatalog.get().entry(hoverBlock);
        if (e == null) return;
        int x = BlockPos.getX(hoverPos), y = BlockPos.getY(hoverPos), z = BlockPos.getZ(hoverPos);
        String l1 = e.name();
        String l2 = "X " + x + "  Y " + y + "  Z " + z;
        String l3 = player == null ? "" : Ui.formatDistance(Math.sqrt(player.distanceToSqr(x + 0.5, y + 0.5, z + 0.5)));
        String l4 = Target.is(hoverBlock, hoverPos) ? tr("blockatlas.map.is_target") : tr("blockatlas.map.click_target");
        int w = Math.max(Math.max(font.width(l1) + 24, font.width(l2)), Math.max(font.width(l3), font.width(l4))) + 16;
        int h = 52;
        int tx = Math.min(mx + 12, mapX + mapW - w - 4), ty = Math.min(my + 12, mapY + mapH - h - 4);
        Ui.card(g, tx, ty, tx + w, ty + h, 5, 0xF0101318, Ui.ACCENT);
        Ui.icon(g, font, e.icon(), e.name(), cfg.colorFor(e.id()).rgb, tx + 6, ty + 4);
        Ui.text(g, font, l1, tx + 26, ty + 8, Ui.TEXT);
        Ui.text(g, font, l2, tx + 8, ty + 22, Ui.TEXT_DIM);
        Ui.text(g, font, l3, tx + 8, ty + 32, Ui.TEXT_DIM);
        Ui.text(g, font, l4, tx + 8, ty + 42, Target.is(hoverBlock, hoverPos) ? Ui.STAR : Ui.ACCENT);
    }

    // ---- input ------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent raw, boolean doubleClick) {
        MouseButtonEvent ev = scaled(raw);
        double mx = ev.x(), my = ev.y();
        int button = ev.button();
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            for (PillButton b : buttons) if (b.mouseClicked(mx, my)) return true;
            if (DeepScanner.available(minecraft)) {
                if (deepRadius.mouseClicked(mx, my)) {
                    draggingRadius = true;
                    return true;
                }
                if (genToggle.mouseClicked(mx, my) || startStop.mouseClicked(mx, my)) return true;
            }
        }
        // sidebar: toggle visibility
        if (mx >= sideX && mx < sideX + SIDE_W && my >= listY && my < listBottom) {
            List<Block> types = types();
            int i = (int) ((my - listY) / ROW_H) + listScroll;
            if (i >= 0 && i < types.size()) {
                Block b = types.get(i);
                if (!hidden.remove(b)) hidden.add(b);
            }
            return true;
        }
        if (inMap(mx, my)) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                panning = true;
                panMoved = false;
                panStartMX = mx;
                panStartMY = my;
                panStartCX = centerX;
                panStartCZ = centerZ;
                return true;
            }
            if (button == InputConstants.MOUSE_BUTTON_RIGHT && hovering && Target.is(hoverBlock, hoverPos)) {
                Target.clear();
                return true;
            }
        }
        return super.mouseClicked(raw, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent raw, double dragX, double dragY) {
        if (draggingRadius) {
            deepRadius.drag(scaled(raw).x());
            return true;
        }
        if (panning) {
            MouseButtonEvent ev = scaled(raw);
            double dx = ev.x() - panStartMX, dy = ev.y() - panStartMY;
            if (Math.abs(dx) + Math.abs(dy) > 2) panMoved = true;
            if (panMoved) {
                follow = false;
                centerX = panStartCX - dx / zoom;
                centerZ = panStartCZ - dy / zoom;
            }
            return true;
        }
        return super.mouseDragged(raw, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent raw) {
        if (draggingRadius) {
            draggingRadius = false;
            deepRadius.release();
            cfg.save();
            return true;
        }
        if (panning) {
            panning = false;
            if (!panMoved && hovering) {
                // a click (not a drag) on a dot: make it the target
                if (Target.is(hoverBlock, hoverPos)) Target.clear();
                else Target.set(hoverBlock, hoverPos);
            }
            return true;
        }
        return super.mouseReleased(raw);
    }

    @Override
    public boolean mouseScrolled(double rawX, double rawY, double scrollX, double scrollY) {
        double mx = sx(rawX), my = sy(rawY);
        if (inMap(mx, my)) {
            zoomAt(mx, my, scrollY > 0 ? 1.25 : 0.8);
            return true;
        }
        if (mx >= sideX && mx < sideX + SIDE_W) {
            listScroll -= (int) Math.signum(scrollY) * 2;
            return true;
        }
        return super.mouseScrolled(rawX, rawY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (System.currentTimeMillis() - openedAt > 150 && BlockAtlasClient.mapKey.matches(event)) {
            onClose();
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (event.key() == InputConstants.KEY_C) {
            follow = true;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    /** For other screens: the map's hidden set is session-wide. */
    public static Set<Block> hiddenTypes() {
        return Collections.unmodifiableSet(hidden);
    }
}
