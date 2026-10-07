package com.yourname.blockatlas.gui;

import net.minecraft.network.chat.Component;

/** Browser tabs. {@link #ALL} and {@link #ACTIVE} are filters, the rest are classifications. */
public enum Category {
    ALL("all"),
    ORES("ores"),
    NATURAL("natural"),
    BUILDING("building"),
    REDSTONE("redstone"),
    DECORATIVE("decorative"),
    TECHNICAL("technical"),
    ACTIVE("active");

    private final String key;

    Category(String key) {
        this.key = key;
    }

    public String label() {
        return Component.translatable("blockatlas.category." + key).getString();
    }
}
