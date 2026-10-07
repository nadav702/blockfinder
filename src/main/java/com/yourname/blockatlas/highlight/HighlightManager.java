package com.yourname.blockatlas.highlight;

import com.yourname.blockatlas.config.BlockAtlasConfig;
import com.yourname.blockatlas.scan.BlockScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the set of highlighted blocks, the latest scan results and the per-frame render list.
 * All mutation happens on the client thread; the render list is an immutable snapshot.
 */
public final class HighlightManager {
    private static final HighlightManager INSTANCE = new HighlightManager();

    public static HighlightManager get() {
        return INSTANCE;
    }

    /** Per-block statistics for the GUI and the HUD. */
    public record Stats(int count, double nearestDistance, long nearestPos, boolean capped) {}

    /**
     * Immutable data handed to the renderer: positions sorted nearest first, their colours,
     * and one "beam" (nearest instance) per highlighted block.
     */
    public record RenderList(long[] positions, int[] colors, int count, long[] beams, int[] beamColors) {
        public static final RenderList EMPTY = new RenderList(new long[0], new int[0], 0, new long[0], new int[0]);
    }

    private final LinkedHashSet<Block> active = new LinkedHashSet<>();
    private final Set<Block> activeView = Collections.unmodifiableSet(active);
    private Map<Block, long[]> results = Map.of();
    private Set<Block> capped = Set.of();
    private final Map<Block, Stats> stats = new HashMap<>();
    private volatile RenderList renderList = RenderList.EMPTY;

    private int totalFound;
    private double nearestOverall = -1;
    private int tickCounter;
    private boolean dirty = true;

    private HighlightManager() {}

    // ---- selection --------------------------------------------------------------------------

    public Set<Block> activeBlocks() {
        return activeView;
    }

    public boolean isActive(Block block) {
        return active.contains(block);
    }

    public void toggle(Block block) {
        if (active.contains(block)) remove(block);
        else add(block);
    }

    public void add(Block block) {
        if (active.add(block)) {
            saveToConfig();
            BlockScanner.get().requestRescan();
            dirty = true;
        }
    }

    public void remove(Block block) {
        if (active.remove(block)) {
            if (results.containsKey(block)) {
                Map<Block, long[]> copy = new HashMap<>(results);
                copy.remove(block);
                results = copy;
            }
            stats.remove(block);
            saveToConfig();
            BlockScanner.get().requestRescan();
            dirty = true;
        }
    }

    public void clearAll() {
        active.clear();
        saveToConfig();
        clearResults();
    }

    public void clearResults() {
        if (results.isEmpty() && renderList == RenderList.EMPTY) return;
        results = Map.of();
        capped = Set.of();
        stats.clear();
        totalFound = 0;
        nearestOverall = -1;
        renderList = RenderList.EMPTY;
    }

    public void markDirty() {
        dirty = true;
    }

    public void loadFromConfig() {
        active.clear();
        for (String s : BlockAtlasConfig.get().active) {
            Identifier id = Identifier.tryParse(s);
            if (id == null) continue;
            BuiltInRegistries.BLOCK.getOptional(id).ifPresent(active::add);
        }
    }

    private void saveToConfig() {
        List<String> ids = new ArrayList<>(active.size());
        for (Block b : active) ids.add(BuiltInRegistries.BLOCK.getKey(b).toString());
        BlockAtlasConfig cfg = BlockAtlasConfig.get();
        cfg.active = ids;
        cfg.save();
    }

    // ---- results ----------------------------------------------------------------------------

    /**
     * Receives scan output. A complete pass replaces everything; a partial pass only fills in
     * blocks that have no results yet (so freshly selected blocks show up while scanning).
     */
    public void publish(Map<Block, long[]> data, boolean complete, Set<Block> cappedBlocks) {
        Map<Block, long[]> next;
        if (complete) {
            next = new HashMap<>();
            for (Block b : active) {
                long[] arr = data.get(b);
                if (arr != null) next.put(b, arr);
            }
            capped = cappedBlocks;
        } else {
            next = new HashMap<>(results);
            for (Block b : active) {
                long[] arr = data.get(b);
                if (arr != null && !next.containsKey(b)) next.put(b, arr);
            }
        }
        results = next;
        dirty = true;
    }

    public Stats stats(Block block) {
        return stats.get(block);
    }

    public int totalFound() {
        return totalFound;
    }

    public double nearestOverall() {
        return nearestOverall;
    }

    public RenderList renderList() {
        return renderList;
    }

    public void tick(Minecraft client) {
        if (client.player == null) return;
        tickCounter++;
        // Distances change as the player walks; refresh four times a second, or right away on change.
        if (!dirty && tickCounter % 5 != 0) return;
        dirty = false;
        recompute(client.player.getEyePosition());
    }

    /**
     * Computes stats and the render list. Uses a counting sort on integer distance (O(n)),
     * so even tens of thousands of hits cost well under a millisecond.
     */
    private void recompute(Vec3 eye) {
        BlockAtlasConfig cfg = BlockAtlasConfig.get();
        Map<Block, long[]> res = results;
        stats.clear();

        int total = 0;
        for (Block b : active) {
            long[] arr = res.get(b);
            if (arr != null) total += arr.length;
        }
        totalFound = total;

        int maxBucket = BlockAtlasConfig.MAX_RANGE + 64;
        int[] bucketCounts = new int[maxBucket + 1];
        int[] bucketOf = new int[total];
        long[] allPos = new long[total];
        int[] allColor = new int[total];
        List<Long> beamList = new ArrayList<>();
        List<Integer> beamColorList = new ArrayList<>();

        double bestOverall = Double.MAX_VALUE;
        int n = 0;
        for (Block b : active) {
            long[] arr = res.get(b);
            if (arr == null) continue;
            String id = BuiltInRegistries.BLOCK.getKey(b).toString();
            int argb = cfg.colorFor(id).argb();
            double best = Double.MAX_VALUE;
            long bestPos = 0L;
            for (long p : arr) {
                double dx = BlockPos.getX(p) + 0.5 - eye.x;
                double dy = BlockPos.getY(p) + 0.5 - eye.y;
                double dz = BlockPos.getZ(p) + 0.5 - eye.z;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 < best) {
                    best = d2;
                    bestPos = p;
                }
                int bucket = (int) Math.min(maxBucket, Math.sqrt(d2));
                bucketOf[n] = bucket;
                allPos[n] = p;
                allColor[n] = argb;
                bucketCounts[bucket]++;
                n++;
            }
            double nearest = arr.length > 0 ? Math.sqrt(best) : -1;
            stats.put(b, new Stats(arr.length, nearest, bestPos, capped.contains(b)));
            if (arr.length > 0) {
                beamList.add(bestPos);
                beamColorList.add(argb);
                bestOverall = Math.min(bestOverall, nearest);
            }
        }
        nearestOverall = bestOverall == Double.MAX_VALUE ? -1 : bestOverall;

        int take = Math.min(n, cfg.maxRenderedBoxes);
        long[] outPos = new long[take];
        int[] outColor = new int[take];
        if (take > 0) {
            int[] next = new int[maxBucket + 1];
            int run = 0;
            for (int k = 0; k <= maxBucket; k++) {
                next[k] = run;
                run += bucketCounts[k];
            }
            for (int i = 0; i < n; i++) {
                int slot = next[bucketOf[i]]++;
                if (slot < take) {
                    outPos[slot] = allPos[i];
                    outColor[slot] = allColor[i];
                }
            }
        }

        long[] beams = new long[beamList.size()];
        int[] beamColors = new int[beamList.size()];
        for (int i = 0; i < beams.length; i++) {
            beams[i] = beamList.get(i);
            beamColors[i] = beamColorList.get(i);
        }
        renderList = new RenderList(outPos, outColor, take, beams, beamColors);
    }
}
