package com.blockfinder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import java.io.Reader;

/** Colours loaded from assets/blockfinder/theme.json, overridable by any resource pack. */
public final class Theme {
    public static int accent = 0xFF4FC3F7, background = 0xE0101418, text = 0xFFFFFFFF, subtext = 0xFF8A96A3;
    public static int outlineNear = 0xFF4FE0FF, outlineFar = 0xFFA060FF;
    public static float outlineWidth = 2f;
    public static boolean pulse = true;

    public static final ResourceLocation SLOT = id("textures/gui/slot.png");
    public static final ResourceLocation SLOT_HOVER = id("textures/gui/slot_hover.png");
    public static final ResourceLocation SLOT_SELECTED = id("textures/gui/slot_selected.png");
    public static final ResourceLocation PANEL = id("textures/gui/panel.png");
    public static final ResourceLocation ICON = id("textures/gui/icon.png");
    private static final ResourceLocation FILE = id("theme.json");

    static ResourceLocation id(String p) { return ResourceLocation.fromNamespaceAndPath("blockfinder", p); }

    public static void register() {
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloader(id("theme"),
            (ResourceManagerReloadListener) Theme::load);
    }

    private static void load(ResourceManager rm) {
        rm.getResource(FILE).ifPresent(res -> {
            try (Reader r = res.openAsReader()) {
                JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                accent = col(o, "accent", accent); background = col(o, "background", background);
                text = col(o, "text", text); subtext = col(o, "subtext", subtext);
                outlineNear = col(o, "outline_near", outlineNear); outlineFar = col(o, "outline_far", outlineFar);
                if (o.has("outline_width")) outlineWidth = o.get("outline_width").getAsFloat();
                if (o.has("pulse")) pulse = o.get("pulse").getAsBoolean();
            } catch (Exception e) {
                System.err.println("[BlockFinder] Bad theme.json: " + e.getMessage());
            }
        });
    }

    private static int col(JsonObject o, String k, int def) {
        return o.has(k) ? (int) Long.parseLong(o.get(k).getAsString(), 16) : def;
    }

    /** Linear blend between two ARGB colours. */
    public static int lerp(int a, int b, float t) {
        int r = 0;
        for (int s = 0; s < 32; s += 8) {
            int ca = (a >>> s) & 0xFF, cb = (b >>> s) & 0xFF;
            r |= ((int) (ca + (cb - ca) * t) & 0xFF) << s;
        }
        return r;
    }
}
