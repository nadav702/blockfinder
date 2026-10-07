package com.yourname.blockatlas.scan;

import com.yourname.blockatlas.config.BlockAtlasConfig;
import com.yourname.blockatlas.highlight.HighlightManager;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Incremental, throttled scanner over the client's <b>loaded</b> chunks.
 *
 * <ul>
 *   <li>Runs on the client thread inside a fixed time budget per tick (default 3 ms), so it
 *       never races chunk updates and never causes a frame spike.</li>
 *   <li>Visits chunks nearest-first, so close blocks appear almost immediately.</li>
 *   <li>Skips whole 16³ sections whose block palette cannot contain any target
 *       ({@link LevelChunkSection#maybeHas}); for rare blocks such as ores this skips
 *       the vast majority of the world without reading a single block.</li>
 *   <li>A complete pass is published atomically; while a pass is running, newly selected
 *       blocks get progressive partial results.</li>
 * </ul>
 */
public final class BlockScanner {
    private static final BlockScanner INSTANCE = new BlockScanner();

    public static BlockScanner get() {
        return INSTANCE;
    }

    private enum State { IDLE, SCANNING, COOLDOWN }

    private State state = State.IDLE;
    private boolean restartRequested = true;
    private int cooldown;
    private int ticksSincePartialPublish;

    private ClientLevel level;
    private ReferenceOpenHashSet<Block> targets = new ReferenceOpenHashSet<>();
    private Predicate<BlockState> targetPredicate = s -> false;
    private final Reference2ObjectOpenHashMap<Block, LongArrayList> found = new Reference2ObjectOpenHashMap<>();
    private final ReferenceOpenHashSet<Block> capped = new ReferenceOpenHashSet<>();

    private int[] offsets = new int[0];
    private int offsetsRange = -1;
    private int offsetIndex;
    private int originCx, originCz;
    private double originX, originY, originZ;
    private int range;
    private double rangeSq;
    private int maxPerBlock;

    private BlockScanner() {}

    public void requestRescan() {
        restartRequested = true;
    }

    public void reset() {
        state = State.IDLE;
        level = null;
        restartRequested = true;
        found.clear();
    }

    public boolean isScanning() {
        return state == State.SCANNING;
    }

    /** Progress of the current pass, 0..1. */
    public float progress() {
        if (state != State.SCANNING || offsets.length == 0) return 1f;
        return Math.min(1f, offsetIndex / (offsets.length / 2f));
    }

    public void tick(Minecraft client) {
        BlockAtlasConfig cfg = BlockAtlasConfig.get();
        HighlightManager hm = HighlightManager.get();
        ClientLevel lvl = client.level;
        LocalPlayer player = client.player;
        if (lvl == null || player == null) return;

        if (!cfg.enabled || hm.activeBlocks().isEmpty()) {
            state = State.IDLE;
            restartRequested = true;
            return;
        }
        if (lvl != level) {
            hm.clearResults();
            restartRequested = true;
        }

        if (restartRequested) {
            restartRequested = false;
            begin(lvl, player, cfg, hm.activeBlocks());
        }

        switch (state) {
            case IDLE -> begin(lvl, player, cfg, hm.activeBlocks());
            case COOLDOWN -> {
                if (--cooldown <= 0) begin(lvl, player, cfg, hm.activeBlocks());
            }
            case SCANNING -> step(cfg, hm);
        }
    }

    private void begin(ClientLevel lvl, LocalPlayer player, BlockAtlasConfig cfg, Set<Block> active) {
        level = lvl;
        targets = new ReferenceOpenHashSet<>(active);
        ReferenceOpenHashSet<Block> t = targets;
        targetPredicate = s -> t.contains(s.getBlock());

        range = cfg.range;
        rangeSq = (double) range * range;
        maxPerBlock = cfg.maxResultsPerBlock;
        originX = player.getX();
        originY = player.getEyeY();
        originZ = player.getZ();
        originCx = BlockPos.containing(originX, originY, originZ).getX() >> 4;
        originCz = BlockPos.containing(originX, originY, originZ).getZ() >> 4;
        if (offsetsRange != range) {
            offsets = buildOffsets(range);
            offsetsRange = range;
        }
        offsetIndex = 0;
        found.clear();
        capped.clear();
        for (Block b : targets) found.put(b, new LongArrayList());
        ticksSincePartialPublish = 0;
        state = State.SCANNING;
    }

    /** Chunk offsets (dx, dz pairs) whose area can intersect the range, sorted nearest first. */
    private static int[] buildOffsets(int range) {
        int rc = (range >> 4) + 1;
        List<int[]> list = new ArrayList<>();
        long limit = (long) range * range;
        for (int dx = -rc; dx <= rc; dx++) {
            for (int dz = -rc; dz <= rc; dz++) {
                long nx = Math.max(0, Math.abs(dx) - 1) * 16L;
                long nz = Math.max(0, Math.abs(dz) - 1) * 16L;
                if (nx * nx + nz * nz <= limit) list.add(new int[]{dx, dz});
            }
        }
        list.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
        int[] out = new int[list.size() * 2];
        for (int i = 0; i < list.size(); i++) {
            out[i * 2] = list.get(i)[0];
            out[i * 2 + 1] = list.get(i)[1];
        }
        return out;
    }

    private void step(BlockAtlasConfig cfg, HighlightManager hm) {
        long deadline = System.nanoTime() + cfg.scanBudgetMicros * 1000L;
        int total = offsets.length / 2;

        while (offsetIndex < total && System.nanoTime() < deadline) {
            int cx = originCx + offsets[offsetIndex * 2];
            int cz = originCz + offsets[offsetIndex * 2 + 1];
            offsetIndex++;
            if (!level.hasChunk(cx, cz)) continue;
            LevelChunk chunk = level.getChunk(cx, cz);
            scanChunk(chunk, cx, cz);
        }

        if (offsetIndex >= total) {
            hm.publish(snapshot(), true, new ReferenceOpenHashSet<>(capped));
            state = State.COOLDOWN;
            cooldown = cfg.rescanIntervalTicks;
            return;
        }

        if (++ticksSincePartialPublish >= 10) {
            ticksSincePartialPublish = 0;
            hm.publish(snapshot(), false, new ReferenceOpenHashSet<>(capped));
        }
    }

    private Map<Block, long[]> snapshot() {
        Map<Block, long[]> out = new HashMap<>(found.size() * 2);
        for (var e : found.reference2ObjectEntrySet()) out.put(e.getKey(), e.getValue().toLongArray());
        return out;
    }

    private void scanChunk(LevelChunk chunk, int cx, int cz) {
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = cx << 4;
        int baseZ = cz << 4;
        double minY = originY - range;
        double maxY = originY + range;

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            if (baseY + 16 < minY || baseY > maxY) continue;
            // Palette check: skips the section without touching block data if no target can be inside.
            if (!section.maybeHas(targetPredicate)) continue;

            for (int y = 0; y < 16; y++) {
                int wy = baseY + y;
                double dy = wy + 0.5 - originY;
                double dy2 = dy * dy;
                if (dy2 > rangeSq) continue;
                for (int z = 0; z < 16; z++) {
                    int wz = baseZ + z;
                    double dz = wz + 0.5 - originZ;
                    double dyz = dy2 + dz * dz;
                    if (dyz > rangeSq) continue;
                    for (int x = 0; x < 16; x++) {
                        Block block = section.getBlockState(x, y, z).getBlock();
                        if (!targets.contains(block)) continue;
                        int wx = baseX + x;
                        double dx = wx + 0.5 - originX;
                        if (dyz + dx * dx > rangeSq) continue;
                        LongArrayList list = found.get(block);
                        if (list.size() >= maxPerBlock) {
                            capped.add(block);
                            continue;
                        }
                        list.add(BlockPos.asLong(wx, wy, wz));
                    }
                }
            }
        }
    }
}
