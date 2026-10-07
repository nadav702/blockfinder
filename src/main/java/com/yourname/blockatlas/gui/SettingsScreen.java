package com.yourname.blockatlas.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.yourname.blockatlas.config.BlockAtlasConfig;
import com.yourname.blockatlas.gui.widget.PillButton;
import com.yourname.blockatlas.gui.widget.Slider;
import com.yourname.blockatlas.gui.widget.Ui;
import com.yourname.blockatlas.scan.BlockMemory;
import com.yourname.blockatlas.scan.BlockScanner;
import com.yourname.blockatlas.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Appearance, size, HUD, layout and map/memory settings. Changes apply live. */
public class SettingsScreen extends ScaledScreen {
    private static final int[] ACCENTS = {
            0x7C9CFF, 0x4DE8FF, 0x5BD69A, 0xFFC94A, 0xFF8A4D, 0xFF6B9E, 0xC77DFF, 0xE9ECF1
    };

    private final Screen parent;
    private final BlockAtlasConfig cfg = BlockAtlasConfig.get();
    private final List<PillButton> buttons = new ArrayList<>();
    private Slider opacity, uiScale, hudScale, dragging;
    private float pendingUiScale;
    private boolean confirmForget;

    private int px, py, pw, ph, colL, colR, colW, rowY0;
    private int swatchY;

    public SettingsScreen(Screen parent) {
        super(Component.translatable("blockatlas.settings.title"));
        this.parent = parent;
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    @Override
    protected void initScaled() {
        buttons.clear();
        pw = Math.min(sw - 16, 480);
        ph = Math.min(sh - 16, 262);
        px = (sw - pw) / 2;
        py = (sh - ph) / 2;
        colW = (pw - 36) / 2;
        colL = px + 12;
        colR = colL + colW + 12;
        rowY0 = py + 40;
        pendingUiScale = cfg.uiScale;

        // ---- left column: appearance + size
        swatchY = rowY0 + 14;
        opacity = new Slider(colL + 10, rowY0 + 50, colW - 20, false, v -> {
            cfg.panelOpacity = (float) (0.55 + v * 0.45);
        });
        opacity.setValue((cfg.panelOpacity - 0.55) / 0.45);

        int sizeY = rowY0 + 82;
        uiScale = new Slider(colL + 10, sizeY + 26, colW - 20, false, v -> pendingUiScale = (float) (0.6 + v * 1.0));
        uiScale.setValue((cfg.uiScale - 0.6) / 1.0);
        hudScale = new Slider(colL + 10, sizeY + 56, colW - 20, false, v -> cfg.hudScale = (float) (0.6 + v * 1.2));
        hudScale.setValue((cfg.hudScale - 0.6) / 1.2);

        // ---- right column: HUD, layout, map
        int y = rowY0 + 14;
        int half = (colW - 24) / 2;
        String[] corners = {tr("blockatlas.settings.tl"), tr("blockatlas.settings.tr"), tr("blockatlas.settings.bl"), tr("blockatlas.settings.br")};
        for (int i = 0; i < 4; i++) {
            final int c = i;
            int bx = colR + 10 + (i % 2) * (half + 4);
            int by = y + (i / 2) * 20;
            buttons.add(new PillButton(bx, by, half, 16, () -> corners[c], () -> cfg.hudCorner == c, Ui.ACCENT, () -> cfg.hudCorner = c));
        }
        y += 42;
        buttons.add(new PillButton(colR + 10, y, colW - 20, 16,
                () -> tr(cfg.showHud ? "blockatlas.settings.hud_on" : "blockatlas.settings.hud_off"),
                () -> cfg.showHud, Ui.OK, () -> cfg.showHud = !cfg.showHud));

        y += 36;
        buttons.add(new PillButton(colR + 10, y, half, 16, () -> tr("blockatlas.settings.grid"), () -> cfg.gridLayout, Ui.ACCENT, () -> cfg.gridLayout = true));
        buttons.add(new PillButton(colR + 14 + half, y, half, 16, () -> tr("blockatlas.settings.list"), () -> !cfg.gridLayout, Ui.ACCENT, () -> cfg.gridLayout = false));
        y += 20;
        buttons.add(new PillButton(colR + 10, y, colW - 20, 16,
                () -> tr(cfg.showIds ? "blockatlas.settings.ids_on" : "blockatlas.settings.ids_off"),
                () -> cfg.showIds, Ui.ACCENT, () -> cfg.showIds = !cfg.showIds));

        y += 36;
        buttons.add(new PillButton(colR + 10, y, colW - 20, 16,
                () -> tr(cfg.rememberBlocks ? "blockatlas.settings.remember_on" : "blockatlas.settings.remember_off"),
                () -> cfg.rememberBlocks, Ui.OK, () -> {
                    cfg.rememberBlocks = !cfg.rememberBlocks;
                    BlockScanner.get().requestRescan();
                }));
        y += 20;
        buttons.add(new PillButton(colR + 10, y, colW - 20, 16,
                () -> tr(confirmForget ? "blockatlas.settings.forget_confirm" : "blockatlas.settings.forget"),
                () -> confirmForget, Ui.DANGER, () -> {
                    if (confirmForget) {
                        BlockMemory.get().forgetWorld();
                        confirmForget = false;
                    } else {
                        confirmForget = true;
                    }
                }));

        // done
        int dw = 90;
        buttons.add(new PillButton(px + pw - 12 - dw, py + 9, dw, 16, () -> tr("blockatlas.settings.done"), () -> true, Ui.ACCENT, this::onClose));
    }

    @Override
    protected void extractScaled(GuiGraphicsExtractor g, int mx, int my, float delta) {
        Ui.card(g, px, py, px + pw, py + ph, 8, Ui.PANEL, Ui.PANEL_BORDER);
        Ui.text(g, font, "Block", px + 14, py + 13, Ui.TEXT);
        Ui.text(g, font, "Atlas", px + 14 + font.width("Block"), py + 13, Ui.ACCENT);
        Ui.text(g, font, tr("blockatlas.settings.title"), px + 14 + font.width("BlockAtlas") + 8, py + 13, Ui.TEXT_DIM);
        Ui.rect(g, px + 1, py + 30, px + pw - 1, py + 31, Ui.DIVIDER);

        int colBottom = py + ph - 12;
        Ui.card(g, colL, rowY0 - 4, colL + colW, colBottom, 6, Ui.SURFACE, Ui.SURFACE_BORDER);
        Ui.card(g, colR, rowY0 - 4, colR + colW, colBottom, 6, Ui.SURFACE, Ui.SURFACE_BORDER);

        // appearance
        Ui.text(g, font, tr("blockatlas.settings.appearance"), colL + 10, rowY0 + 2, Ui.TEXT_FAINT);
        int size = Math.min(14, (colW - 20 - 7 * 4) / ACCENTS.length);
        for (int i = 0; i < ACCENTS.length; i++) {
            int sx = colL + 10 + i * (size + 4);
            boolean sel = cfg.accentColor == ACCENTS[i];
            boolean hover = mx >= sx && mx < sx + size && my >= swatchY && my < swatchY + size;
            Ui.card(g, sx, swatchY, sx + size, swatchY + size, 3, ColorUtil.opaque(ACCENTS[i]), sel ? Ui.TEXT : hover ? Ui.TEXT_DIM : Ui.PANEL_BORDER);
        }
        Ui.text(g, font, tr("blockatlas.settings.opacity"), colL + 10, rowY0 + 38, Ui.TEXT_DIM);
        Ui.textRight(g, font, Math.round(cfg.panelOpacity * 100) + "%", colL + colW - 10, rowY0 + 38, Ui.TEXT_DIM);
        opacity.render(g, mx, my, Ui.ACCENT);

        // size
        int sizeY = rowY0 + 82;
        Ui.text(g, font, tr("blockatlas.settings.size"), colL + 10, sizeY, Ui.TEXT_FAINT);
        Ui.text(g, font, tr("blockatlas.settings.menu_scale"), colL + 10, sizeY + 14, Ui.TEXT_DIM);
        Ui.textRight(g, font, Math.round(pendingUiScale * 100) + "%", colL + colW - 10, sizeY + 14, Ui.TEXT_DIM);
        uiScale.render(g, mx, my, Ui.ACCENT);
        Ui.text(g, font, tr("blockatlas.settings.hud_scale"), colL + 10, sizeY + 44, Ui.TEXT_DIM);
        Ui.textRight(g, font, Math.round(cfg.hudScale * 100) + "%", colL + colW - 10, sizeY + 44, Ui.TEXT_DIM);
        hudScale.render(g, mx, my, Ui.ACCENT);
        Ui.text(g, font, Ui.trim(font, tr("blockatlas.settings.scale_hint"), colW - 20), colL + 10, sizeY + 72, Ui.TEXT_FAINT);

        // right column headers
        Ui.text(g, font, tr("blockatlas.settings.hud"), colR + 10, rowY0 + 2, Ui.TEXT_FAINT);
        Ui.text(g, font, tr("blockatlas.settings.layout"), colR + 10, rowY0 + 80, Ui.TEXT_FAINT);
        Ui.text(g, font, tr("blockatlas.settings.map"), colR + 10, rowY0 + 136, Ui.TEXT_FAINT);

        for (PillButton b : buttons) b.render(g, font, mx, my);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent raw, boolean doubleClick) {
        MouseButtonEvent ev = scaled(raw);
        double mx = ev.x(), my = ev.y();
        if (ev.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(raw, doubleClick);
        for (PillButton b : buttons) {
            if (b.mouseClicked(mx, my)) {
                cfg.save();
                return true;
            }
        }
        for (Slider s : new Slider[]{opacity, uiScale, hudScale}) {
            if (s.mouseClicked(mx, my)) {
                dragging = s;
                return true;
            }
        }
        int size = Math.min(14, (colW - 20 - 7 * 4) / ACCENTS.length);
        for (int i = 0; i < ACCENTS.length; i++) {
            int sx = colL + 10 + i * (size + 4);
            if (mx >= sx && mx < sx + size && my >= swatchY && my < swatchY + size) {
                cfg.accentColor = ACCENTS[i];
                cfg.save();
                relayout(); // buttons capture the accent colour
                return true;
            }
        }
        confirmForget = false;
        return super.mouseClicked(raw, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent raw, double dragX, double dragY) {
        if (dragging != null) {
            dragging.drag(scaled(raw).x());
            return true;
        }
        return super.mouseDragged(raw, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent raw) {
        if (dragging != null) {
            Slider s = dragging;
            dragging = null;
            s.release();
            if (s == uiScale && Math.abs(pendingUiScale - cfg.uiScale) > 0.001f) {
                cfg.uiScale = pendingUiScale;
                cfg.save();
                relayout();
            } else {
                cfg.save();
            }
            return true;
        }
        return super.mouseReleased(raw);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        cfg.save();
        minecraft.gui.setScreen(parent);
    }
}
