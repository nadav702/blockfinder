package com.yourname.blockatlas.gui.widget;

import com.yourname.blockatlas.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.DoubleConsumer;

/** Minimal horizontal slider (value 0..1). Optional rainbow track for hue selection. */
public final class Slider {
    private final int x, y, w;
    private final boolean hueTrack;
    private final DoubleConsumer onChange;
    private double value;
    private boolean dragging;

    public Slider(int x, int y, int w, boolean hueTrack, DoubleConsumer onChange) {
        this.x = x;
        this.y = y;
        this.w = Math.max(10, w);
        this.hueTrack = hueTrack;
        this.onChange = onChange;
    }

    public double value() {
        return value;
    }

    public void setValue(double v) {
        value = Math.max(0, Math.min(1, v));
    }

    public boolean isDragging() {
        return dragging;
    }

    public boolean hit(double mx, double my) {
        return mx >= x - 5 && mx <= x + w + 5 && my >= y - 6 && my <= y + 10;
    }

    public void render(GuiGraphicsExtractor g, int mx, int my, int accent) {
        if (hueTrack) {
            for (int i = 0; i < w; i += 2) {
                int c = ColorUtil.opaque(ColorUtil.hsv(i / (float) w, 0.78f, 1f));
                Ui.rect(g, x + i, y, Math.min(x + i + 2, x + w), y + 4, c);
            }
        } else {
            Ui.roundRect(g, x, y, x + w, y + 4, 2, Ui.TRACK);
            Ui.roundRect(g, x, y, x + (int) Math.round(w * value), y + 4, 2, accent);
        }
        int kx = x + (int) Math.round(w * value);
        boolean big = dragging || hit(mx, my);
        int s = big ? 5 : 4;
        int knobFill = hueTrack ? ColorUtil.opaque(ColorUtil.hsv((float) value, 0.78f, 1f)) : Ui.TEXT;
        Ui.card(g, kx - s, y + 2 - s, kx + s, y + 2 + s, s, knobFill, Ui.PANEL);
    }

    public boolean mouseClicked(double mx, double my) {
        if (!hit(mx, my)) return false;
        dragging = true;
        set(mx);
        return true;
    }

    public void drag(double mx) {
        if (dragging) set(mx);
    }

    public void release() {
        dragging = false;
    }

    private void set(double mx) {
        double v = Math.max(0, Math.min(1, (mx - x) / w));
        if (v != value) {
            value = v;
            onChange.accept(v);
        }
    }
}
