package com.yourname.blockatlas.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Invisible widget that sits over the search bar and becomes the screen's focused element.
 *
 * <p>Since 26.3 (SDL input) Minecraft only enables OS text input, and therefore only
 * delivers {@code charTyped} events, while the focused element reports
 * {@link #capturesInput()}. Our search bar is custom-drawn, so this proxy carries that
 * flag for it. Actual key handling stays in the screen.</p>
 */
public final class TextInputFocus extends AbstractWidget {
    private final SearchField field;

    public TextInputFocus(int x, int y, int w, int h, SearchField field) {
        super(x, y, w, h, Component.empty());
        this.field = field;
    }

    /** Like vanilla EditBox: tell Minecraft so it starts/stops SDL text input. */
    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        Minecraft.getInstance().onTextInputFocusChange(this, focused);
    }

    @Override
    public boolean capturesInput() {
        return field.isFocused();
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // drawn by SearchField
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}
