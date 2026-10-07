package com.yourname.blockatlas.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.yourname.blockatlas.BlockAtlasClient;
import com.yourname.blockatlas.util.ColorUtil;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persistent settings, stored as config/blockatlas.json. */
public final class BlockAtlasConfig {
    public static final int MIN_RANGE = 16;
    public static final int MAX_RANGE = 300;
    public static final int DEFAULT_RANGE = 128;
    public static final int WARN_RANGE = 200;
    public static final float DEFAULT_ALPHA = 0.35f;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static BlockAtlasConfig instance = new BlockAtlasConfig();

    // ---- persisted fields -------------------------------------------------------------------
    public int range = DEFAULT_RANGE;
    public boolean enabled = true;
    public boolean seeThroughWalls = true;
    public boolean showHud = true;
    public boolean outlines = true;
    public boolean nearestBeam = true;
    // ---- appearance -------------------------------------------------------------------------
    /** Accent colour (0xRRGGBB) for buttons, sliders, highlights in the UI. */
    public int accentColor = 0x7C9CFF;
    /** Panel opacity 0.55..1 (lower = see the world through the menu). */
    public float panelOpacity = 1.0f;
    /** Scale of the BlockAtlas menu, map and settings (independent of Minecraft's GUI scale). */
    public float uiScale = 1.0f;
    /** HUD size multiplier. */
    public float hudScale = 1.0f;
    /** HUD corner: 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right. */
    public int hudCorner = 0;
    /** true = card grid, false = compact list. */
    public boolean gridLayout = true;
    /** Show block ids under names. */
    public boolean showIds = true;

    // ---- memory / map -----------------------------------------------------------------------
    /** Remember every found block per world so the map (M) can show it from any distance. */
    public boolean rememberBlocks = true;
    /** Stored positions per block type per dimension. */
    public int memoryMaxPerBlock = 1000000;
    /** Deep scan radius in blocks (singleplayer), 500..5000. */
    public int deepScanRadius = 2000;
    /** Deep scan also generates chunks that don't exist yet. */
    public boolean deepScanGenerate = false;

    /** Freecam flying speed in blocks per tick (sprint triples it). */
    public double freecamSpeed = 0.6;

    /** Main-thread time budget for scanning per client tick, in microseconds. */
    public int scanBudgetMicros = 3000;
    /** Pause between two complete scan passes, in ticks. */
    public int rescanIntervalTicks = 40;
    /** Highlights drawn per frame (nearest first). */
    public int maxRenderedBoxes = 6000;
    /** Hard cap on stored positions per block type (protects memory for e.g. "stone"). */
    public int maxResultsPerBlock = 50000;

    public List<String> favorites = new ArrayList<>();
    public List<String> active = new ArrayList<>();
    public Map<String, BlockColor> colors = new LinkedHashMap<>();

    private transient Set<String> favoriteSet = new HashSet<>();

    public static final class BlockColor {
        public int rgb;
        public float alpha;

        public BlockColor() {}

        public BlockColor(int rgb, float alpha) {
            this.rgb = rgb & 0xFFFFFF;
            this.alpha = alpha;
        }

        public int argb() {
            return ColorUtil.argb(rgb, alpha);
        }
    }

    public static BlockAtlasConfig get() {
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("blockatlas.json");
    }

    public static void load() {
        Path file = path();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                BlockAtlasConfig loaded = GSON.fromJson(reader, BlockAtlasConfig.class);
                if (loaded != null) instance = loaded;
            } catch (Exception e) {
                BlockAtlasClient.LOGGER.warn("Could not read {}, using defaults", file, e);
                instance = new BlockAtlasConfig();
            }
        }
        instance.sanitize();
        instance.save();
    }

    public void save() {
        Path file = path();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("blockatlas.json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            BlockAtlasClient.LOGGER.warn("Could not save {}", file, e);
        }
    }

    private void sanitize() {
        range = clamp(range, MIN_RANGE, MAX_RANGE);
        freecamSpeed = Math.max(0.05, Math.min(5.0, freecamSpeed));
        accentColor &= 0xFFFFFF;
        panelOpacity = Math.max(0.55f, Math.min(1f, panelOpacity));
        uiScale = Math.max(0.6f, Math.min(1.6f, uiScale));
        hudScale = Math.max(0.6f, Math.min(1.8f, hudScale));
        hudCorner = Math.floorMod(hudCorner, 4);
        if (memoryMaxPerBlock == 100000) memoryMaxPerBlock = 1000000; // old default
        memoryMaxPerBlock = clamp(memoryMaxPerBlock, 1000, 5000000);
        deepScanRadius = clamp(deepScanRadius, 500, 5000);
        scanBudgetMicros = clamp(scanBudgetMicros, 500, 20000);
        rescanIntervalTicks = clamp(rescanIntervalTicks, 5, 400);
        maxRenderedBoxes = clamp(maxRenderedBoxes, 100, 20000);
        maxResultsPerBlock = clamp(maxResultsPerBlock, 1000, 500000);
        if (favorites == null) favorites = new ArrayList<>();
        if (active == null) active = new ArrayList<>();
        if (colors == null) colors = new LinkedHashMap<>();
        colors.values().removeIf(c -> c == null);
        colors.values().forEach(c -> c.alpha = Math.max(0.05f, Math.min(1f, c.alpha)));
        favoriteSet = new HashSet<>(favorites);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ---- favourites -------------------------------------------------------------------------
    public boolean isFavorite(String id) {
        return favoriteSet.contains(id);
    }

    public void toggleFavorite(String id) {
        if (favoriteSet.remove(id)) {
            favorites.remove(id);
        } else {
            favoriteSet.add(id);
            favorites.add(id);
        }
        save();
    }

    // ---- colours ----------------------------------------------------------------------------
    public BlockColor colorFor(String id) {
        BlockColor c = colors.get(id);
        return c != null ? c : new BlockColor(ColorUtil.defaultColor(id), DEFAULT_ALPHA);
    }

    /** Stored in memory immediately; written to disk when the screen closes. */
    public void setColor(String id, int rgb, float alpha) {
        colors.put(id, new BlockColor(rgb, Math.max(0.05f, Math.min(1f, alpha))));
    }
}
