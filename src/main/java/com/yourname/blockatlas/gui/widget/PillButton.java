package com.yourname.blockatlas.gui.widget;

import com.yourname.blockatlas.util.ColorUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Rounded pill used for toggles, tabs and actions. */
public final class PillButton {
    private final int x, y, w, h;
    private final Supplier<String> label;
    private final BooleanSupplier active;
    private final int accent;
    private final Runnable action;

    public PillButton(int x, int y, int w, int h, Supplier<String> label, BooleanSupplier active, int accent, Runnable action) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.label = label;
        this.active = active;
        this.accent = accent;
        this.action = action;
    }

    public int x() {
        return x;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public void render(GuiGraphicsExtractor g, Font font, int mx, int my) {
        boolean hover = contains(mx, my);
        boolean on = active.getAsBoolean();
        int bg = on ? ColorUtil.mix(Ui.SURFACE, accent, 0.20f) : hover ? Ui.SURFACE_HOVER : Ui.SURFACE;
        int border = on ? ColorUtil.mix(Ui.SURFACE_BORDER, accent, 0.65f) : hover ? Ui.BORDER_HOVER : Ui.SURFACE_BORDER;
        int fg = on ? ColorUtil.mix(accent, 0xFFFFFFFF, 0.30f) : hover ? ColorUtil.mix(Ui.TEXT, accent, 0.25f) : Ui.TEXT_DIM;
        Ui.card(g, x, y, x + w, y + h, h / 2, bg, border);
        Ui.textCenter(g, font, Ui.trim(font, label.get(), w - 8), x + w / 2, y + (h - 8) / 2, fg);
    }

    public boolean mouseClicked(double mx, double my) {
        if (!contains(mx, my)) return false;
        action.run();
        return true;
    }
}
