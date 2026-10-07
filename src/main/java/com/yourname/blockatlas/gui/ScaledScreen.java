package com.yourname.blockatlas.gui;

import com.yourname.blockatlas.config.BlockAtlasConfig;
import com.yourname.blockatlas.gui.widget.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Base for BlockAtlas screens that honour the user's own UI scale (independent of
 * Minecraft's GUI scale). Subclasses lay out in a virtual {@code sw × sh} space; drawing is
 * scaled with the pose stack and mouse coordinates are divided back.
 */
public abstract class ScaledScreen extends Screen {
    protected int sw, sh;
    protected float scale = 1f;

    protected ScaledScreen(Component title) {
        super(title);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Layout using {@link #sw} / {@link #sh}. */
    protected abstract void initScaled();

    /** Draw in scaled space; mouse coordinates are already scaled. */
    protected abstract void extractScaled(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta);

    @Override
    protected final void init() {
        Ui.applyTheme();
        float wanted = BlockAtlasConfig.get().uiScale;
        float fit = Math.min(width / 380f, height / 250f); // never scale so far the panel can't fit
        scale = Math.max(0.5f, Math.min(wanted, fit));
        sw = Math.round(width / scale);
        sh = Math.round(height / scale);
        initScaled();
    }

    @Override
    public final void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Ui.applyTheme();
        Ui.rect(g, 0, 0, width, height, Ui.BACKDROP);
        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
        extractScaled(g, Math.round(mouseX / scale), Math.round(mouseY / scale), delta);
        g.pose().popMatrix();
    }

    protected MouseButtonEvent scaled(MouseButtonEvent e) {
        return new MouseButtonEvent(e.x() / scale, e.y() / scale, e.buttonInfo());
    }

    protected double sx(double rawX) {
        return rawX / scale;
    }

    protected double sy(double rawY) {
        return rawY / scale;
    }

    /** Re-run layout (e.g. after settings changed the scale). */
    protected void relayout() {
        clearWidgets();
        init();
    }
}
