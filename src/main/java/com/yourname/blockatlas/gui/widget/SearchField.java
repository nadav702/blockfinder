package com.yourname.blockatlas.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.function.Consumer;

/** Lightweight single-line search input with live change callback. */
public final class SearchField {
    private static final int MAX_LENGTH = 64;

    private final int x, y, w, h;
    private final Consumer<String> onChange;
    private String text = "";
    private int cursor;
    private boolean focused;
    private boolean allSelected;
    private String placeholder = "";
    private String suffix = "";

    public SearchField(int x, int y, int w, int h, Consumer<String> onChange) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.onChange = onChange;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
    }

    public void setSuffix(String suffix) {
        this.suffix = suffix;
    }

    public String text() {
        return text;
    }

    public void setText(String value) {
        text = value == null ? "" : value;
        cursor = text.length();
        allSelected = false;
    }

    public boolean isFocused() {
        return focused;
    }

    public void setFocused(boolean f) {
        focused = f;
        if (!f) allSelected = false;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private boolean overClear(double mx, double my) {
        return !text.isEmpty() && mx >= x + w - 18 && mx < x + w && my >= y && my < y + h;
    }

    public void render(GuiGraphicsExtractor g, Font font, int mx, int my) {
        boolean hover = contains(mx, my);
        int border = focused ? Ui.ACCENT : hover ? Ui.BORDER_HOVER : Ui.SURFACE_BORDER;
        Ui.card(g, x, y, x + w, y + h, 6, Ui.INPUT, border);

        // magnifier glyph
        int gx = x + 8, gy = y + (h - 9) / 2;
        int glyph = focused ? Ui.ACCENT : Ui.TEXT_FAINT;
        Ui.card(g, gx, gy, gx + 7, gy + 7, 3, Ui.INPUT, glyph);
        Ui.rect(g, gx + 6, gy + 6, gx + 8, gy + 8, glyph);
        Ui.rect(g, gx + 7, gy + 7, gx + 9, gy + 9, glyph);

        int tx = x + 22;
        int ty = y + (h - 8) / 2;
        int suffixW = suffix.isEmpty() ? 0 : font.width(suffix) + 8;
        int maxW = w - 22 - 20 - suffixW;

        if (text.isEmpty()) {
            Ui.text(g, font, Ui.trim(font, placeholder, maxW), tx, ty, Ui.TEXT_FAINT);
        }

        int start = 0;
        while (start < cursor && font.width(text.substring(start, cursor)) > maxW) start++;
        String visible = font.plainSubstrByWidth(text.substring(start), maxW);
        if (allSelected && !visible.isEmpty()) {
            Ui.rect(g, tx - 1, ty - 1, tx + font.width(visible) + 1, ty + 9, 0x667C9CFF);
        }
        Ui.text(g, font, visible, tx, ty, Ui.TEXT);

        if (focused && (System.currentTimeMillis() / 530) % 2 == 0) {
            int cx = tx + font.width(text.substring(start, Math.min(cursor, start + visible.length())));
            Ui.rect(g, cx, ty - 1, cx + 1, ty + 9, Ui.ACCENT);
        }

        if (!suffix.isEmpty()) {
            Ui.textRight(g, font, suffix, x + w - 22, ty, Ui.TEXT_FAINT);
        }
        if (!text.isEmpty()) {
            Ui.textCenter(g, font, "✕", x + w - 10, ty, overClear(mx, my) ? Ui.TEXT : Ui.TEXT_FAINT);
        }
    }

    public boolean mouseClicked(double mx, double my) {
        if (!contains(mx, my)) {
            setFocused(false);
            return false;
        }
        if (overClear(mx, my)) {
            setText("");
            onChange.accept(text);
        }
        focused = true;
        allSelected = false;
        return true;
    }

    public boolean charTyped(int codepoint) {
        if (!focused || codepoint < 32 || codepoint == 127 || !Character.isValidCodePoint(codepoint)) return false;
        if (allSelected) {
            text = "";
            cursor = 0;
            allSelected = false;
        }
        String add = Character.toString(codepoint);
        if (text.length() + add.length() > MAX_LENGTH) return true;
        text = text.substring(0, cursor) + add + text.substring(cursor);
        cursor += add.length();
        onChange.accept(text);
        return true;
    }

    public boolean keyPressed(int key, int modifiers) {
        if (!focused) return false;
        boolean ctrl = (modifiers & (InputConstants.MOD_CONTROL | InputConstants.MOD_SUPER)) != 0;
        String before = text;

        switch (key) {
            case InputConstants.KEY_BACKSPACE -> {
                if (allSelected) {
                    text = "";
                    cursor = 0;
                } else if (cursor > 0) {
                    int from = ctrl ? previousWord(cursor) : text.offsetByCodePoints(cursor, -1);
                    text = text.substring(0, from) + text.substring(cursor);
                    cursor = from;
                }
            }
            case InputConstants.KEY_DELETE -> {
                if (allSelected) {
                    text = "";
                    cursor = 0;
                } else if (cursor < text.length()) {
                    int to = text.offsetByCodePoints(cursor, 1);
                    text = text.substring(0, cursor) + text.substring(to);
                }
            }
            case InputConstants.KEY_LEFT -> cursor = cursor > 0 ? (ctrl ? previousWord(cursor) : text.offsetByCodePoints(cursor, -1)) : 0;
            case InputConstants.KEY_RIGHT -> cursor = cursor < text.length() ? text.offsetByCodePoints(cursor, 1) : text.length();
            case InputConstants.KEY_HOME -> cursor = 0;
            case InputConstants.KEY_END -> cursor = text.length();
            case InputConstants.KEY_A -> {
                if (!ctrl) return false;
                allSelected = !text.isEmpty();
                return true;
            }
            case InputConstants.KEY_C -> {
                if (!ctrl) return false;
                Minecraft.getInstance().keyboardHandler.setClipboard(text);
                return true;
            }
            case InputConstants.KEY_V -> {
                if (!ctrl) return false;
                String clip = Minecraft.getInstance().keyboardHandler.getClipboard()
                        .replaceAll("[\\r\\n\\t]", " ");
                if (allSelected) {
                    text = "";
                    cursor = 0;
                }
                String merged = text.substring(0, cursor) + clip + text.substring(cursor);
                if (merged.length() > MAX_LENGTH) merged = merged.substring(0, MAX_LENGTH);
                cursor = Math.min(merged.length(), cursor + clip.length());
                text = merged;
            }
            default -> {
                return false;
            }
        }
        allSelected = false;
        if (!text.equals(before)) onChange.accept(text);
        return true;
    }

    private int previousWord(int from) {
        int i = from;
        while (i > 0 && text.charAt(i - 1) == ' ') i--;
        while (i > 0 && text.charAt(i - 1) != ' ') i--;
        return i;
    }
}
