package com.blockfinder;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.*;

/** Clean, searchable grid of every registered block with its icon and name. */
public class BlockPickerScreen extends Screen {
    private static final int CELL = 22, COLS = 12;

    private final List<Block> all = new ArrayList<>();
    private List<Block> shown = new ArrayList<>();
    private EditBox search;
    private int scroll, left, top, rows;
    private Block hovered;

    public BlockPickerScreen() {
        super(Component.literal("Block Finder"));
        for (Block b : BuiltInRegistries.BLOCK) if (b != Blocks.AIR && b != Blocks.CAVE_AIR && b != Blocks.VOID_AIR) all.add(b);
        all.sort(Comparator.comparing(b -> b.getName().getString()));
        shown = all;
    }

    @Override
    protected void init() {
        int w = COLS * CELL + 16;
        left = (width - w) / 2 + 8; top = 52;
        rows = Math.max(1, (height - top - 40) / CELL);
        search = new EditBox(font, left, 26, COLS * CELL, 18, Component.literal("Search"));
        search.setHint(Component.literal("Search blocks…  (e.g. diamond, ore, log)"));
        search.setResponder(this::filter);
        addRenderableWidget(search);
        setInitialFocus(search);
    }

    private void filter(String q) {
        String s = q.toLowerCase(Locale.ROOT).trim();
        shown = s.isEmpty() ? all : all.stream().filter(b ->
            b.getName().getString().toLowerCase(Locale.ROOT).contains(s) ||
            BuiltInRegistries.BLOCK.getKey(b).toString().contains(s)).toList();
        scroll = 0;
    }

    private static ItemStack icon(Block b) {
        ItemStack st = new ItemStack(b.asItem());
        return st.isEmpty() ? new ItemStack(Items.BARRIER) : st; // fluids/technical blocks
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, Theme.background);
        int w = COLS * CELL;
        nineSlice(g, Theme.PANEL, left - 8, 8, w + 16, top + rows * CELL + 22);
        g.blit(RenderPipelines.GUI_TEXTURED, Theme.ICON, left, 12, 0, 0, 16, 16, 16, 16);
        g.drawString(font, "§lBLOCK FINDER", left + 20, 16, Theme.text);
        String count = shown.size() + " blocks";
        g.drawString(font, count, left + w - font.width(count), 16, Theme.subtext);

        hovered = null;
        Block sel = Scanner.INSTANCE.target();
        for (int i = 0; i < rows * COLS; i++) {
            int idx = scroll * COLS + i;
            if (idx >= shown.size()) break;
            Block b = shown.get(idx);
            int x = left + (i % COLS) * CELL, y = top + (i / COLS) * CELL;
            boolean hov = mx >= x && mx < x + CELL && my >= y && my < y + CELL;
            ResourceLocation tex = b == sel ? Theme.SLOT_SELECTED : hov ? Theme.SLOT_HOVER : Theme.SLOT;
            g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0, 0, 20, 20, 20, 20);
            g.renderItem(icon(b), x + 2, y + 2);
            if (hov) hovered = b;
        }
        int maxScroll = Math.max(1, (shown.size() + COLS - 1) / COLS - rows);
        int barH = rows * CELL, thumb = Math.max(12, barH * rows / Math.max(rows, maxScroll + rows));
        int ty = top + (barH - thumb) * scroll / maxScroll;
        g.fill(left + w + 2, top, left + w + 4, top + barH, 0x30FFFFFF);
        g.fill(left + w + 2, ty, left + w + 4, ty + thumb, Theme.accent);

        int fy = top + rows * CELL + 8;
        String info = hovered != null ? hovered.getName().getString() + "  §7" + BuiltInRegistries.BLOCK.getKey(hovered)
                : sel != null ? "§bTracking: §f" + sel.getName().getString() + "  §7(N to clear)" : "§7Click a block to highlight it within 300 blocks";
        g.drawString(font, info, left, fy, Theme.text);
        super.render(g, mx, my, pt);
        if (hovered != null) g.setTooltipForNextFrame(font, hovered.getName(), mx, my);
    }

    /** Draws a 32x32 texture with 8px corners stretched to any size, so packs can restyle the panel. */
    private static void nineSlice(GuiGraphics g, ResourceLocation t, int x, int y, int w, int h) {
        int c = 8, m = 16;
        int[][] cols = {{0, 0, c}, {c, c, w - 2 * c}, {w - c, 24, c}};
        int[][] rws = {{0, 0, c}, {c, c, h - 2 * c}, {h - c, 24, c}};
        for (int[] cx : cols) for (int[] ry : rws) {
            int sw = cx[1] == c ? m : c, sh = ry[1] == c ? m : c;
            g.blit(RenderPipelines.GUI_TEXTURED, t, x + cx[0], y + ry[0], cx[1], ry[1], cx[2], ry[2], sw, sh, 32, 32);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean dbl) {
        if (hovered != null && e.button() == 0) {
            Scanner.INSTANCE.setTarget(hovered);
            onClose();
            return true;
        }
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        int maxScroll = Math.max(0, (shown.size() + COLS - 1) / COLS - rows);
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(v)));
        return true;
    }

    @Override public boolean isPauseScreen() { return false; }
}
