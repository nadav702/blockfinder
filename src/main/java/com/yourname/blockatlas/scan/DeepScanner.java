package com.yourname.blockatlas.scan;

import com.yourname.blockatlas.BlockAtlasClient;
import com.yourname.blockatlas.highlight.HighlightManager;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Singleplayer-only "deep scan": finds the highlighted blocks in every generated chunk of the
 * world within a large radius (up to 5000 blocks), without loading or rendering them.
 *
 * <ul>
 *   <li>Saved chunks are read straight from the region files through the integrated server's
 *       IO worker and decoded on a background thread (block palettes only, no world access).</li>
 *   <li>Optionally, chunks that were never generated are generated with the server's own
 *       world generator (to the stage where ores exist), a few at a time so the game stays
 *       smooth, and scanned when ready.</li>
 *   <li>Results go into {@link BlockMemory} and show on the map.</li>
 * </ul>
 */
public final class DeepScanner {
    private static final DeepScanner INSTANCE = new DeepScanner();
    private static final int MAX_READS_IN_FLIGHT = 48;
    private static final int MAX_GEN_IN_FLIGHT = 6;
    private static final Set<String> ORES_PLACED = Set.of(
            "minecraft:features", "minecraft:initialize_light", "minecraft:light", "minecraft:spawn", "minecraft:full");

    public static DeepScanner get() {
        return INSTANCE;
    }

    public enum State { IDLE, RUNNING, DONE, STOPPED }

    private record Found(Block block, long[] positions) {}

    private volatile State state = State.IDLE;
    private volatile int generation; // increments on every start/stop to drop stale callbacks
    private ExecutorService parser;

    private MinecraftServer server;
    private ServerLevel level;
    private Object clientLevel;
    private Map<String, Block> targetsById = new HashMap<>();
    private Set<Block> targets = new HashSet<>();
    private Predicate<BlockState> targetPredicate = s -> false;

    private int[] order = new int[0];
    private int nextIndex;
    private int originCx, originCz;
    private int radius;
    private boolean generateMissing;

    private final AtomicInteger readsInFlight = new AtomicInteger();
    private final AtomicInteger genInFlight = new AtomicInteger();
    private final ConcurrentLinkedQueue<Long> needGen = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Found> results = new ConcurrentLinkedQueue<>();

    // progress (read from the render thread)
    private final AtomicInteger scanned = new AtomicInteger();
    private final AtomicInteger missing = new AtomicInteger();
    private final AtomicInteger generated = new AtomicInteger();
    private final AtomicLong found = new AtomicLong();
    private int total;

    private DeepScanner() {}

    // ---- public API -------------------------------------------------------------------------

    public static boolean available(Minecraft mc) {
        return mc.getSingleplayerServer() != null && mc.level != null;
    }

    public State state() {
        return state;
    }

    public boolean running() {
        return state == State.RUNNING;
    }

    public int total() {
        return total;
    }

    /** Chunks finished (scanned from disk, generated, or confirmed missing). */
    public int done() {
        return scanned.get() + generated.get() + (generateMissing ? 0 : missing.get());
    }

    public int scanned() {
        return scanned.get();
    }

    public int missing() {
        return missing.get();
    }

    public int generated() {
        return generated.get();
    }

    public long found() {
        return found.get();
    }

    public int radius() {
        return radius;
    }

    public int originX() {
        return originCx * 16 + 8;
    }

    public int originZ() {
        return originCz * 16 + 8;
    }

    public boolean generatesMissing() {
        return generateMissing;
    }

    /** @return null when started, otherwise a translation key explaining why not */
    public String start(Minecraft mc, int radiusBlocks, boolean generate) {
        MinecraftServer srv = mc.getSingleplayerServer();
        if (srv == null || mc.level == null || mc.player == null) return "blockatlas.deep.sp_only";
        Set<Block> active = HighlightManager.get().activeBlocks();
        if (active.isEmpty()) return "blockatlas.deep.no_targets";
        ServerLevel sl = srv.getLevel(mc.level.dimension());
        if (sl == null) return "blockatlas.deep.sp_only";

        stop();
        int gen = ++generation;
        server = srv;
        level = sl;
        clientLevel = mc.level;
        targets = new HashSet<>(active);
        targetsById = new HashMap<>();
        for (Block b : targets) targetsById.put(BuiltInRegistries.BLOCK.getKey(b).toString(), b);
        Set<Block> t = targets;
        targetPredicate = s -> t.contains(s.getBlock());

        radius = Math.max(64, Math.min(5000, radiusBlocks));
        generateMissing = generate;
        originCx = mc.player.getBlockX() >> 4;
        originCz = mc.player.getBlockZ() >> 4;
        order = spiral(radius);
        total = order.length / 2;
        nextIndex = 0;
        readsInFlight.set(0);
        genInFlight.set(0);
        needGen.clear();
        results.clear();
        scanned.set(0);
        missing.set(0);
        generated.set(0);
        found.set(0);
        if (parser == null) {
            parser = Executors.newSingleThreadExecutor(r -> {
                Thread th = new Thread(r, "BlockAtlas deep scan");
                th.setDaemon(true);
                th.setPriority(Thread.MIN_PRIORITY);
                return th;
            });
        }
        state = State.RUNNING;
        BlockAtlasClient.LOGGER.info("Deep scan #{} started: radius {} ({} chunks), generate={}", gen, radius, total, generate);
        return null;
    }

    public void stop() {
        generation++;
        if (state == State.RUNNING) state = State.STOPPED;
        needGen.clear();
        readsInFlight.set(0);
        genInFlight.set(0);
        drainResults();
    }

    /** Client tick: feeds work to the IO worker / server and moves results into memory. */
    public void tick(Minecraft mc) {
        if (state != State.RUNNING) return;
        if (mc.level == null || mc.level != clientLevel || mc.getSingleplayerServer() != server) {
            stop();
            return;
        }
        drainResults();
        int gen = generation;

        // disk reads, nearest first
        while (readsInFlight.get() < MAX_READS_IN_FLIGHT && nextIndex < total) {
            int cx = originCx + order[nextIndex * 2];
            int cz = originCz + order[nextIndex * 2 + 1];
            nextIndex++;
            readsInFlight.incrementAndGet();
            requestRead(cx, cz, gen);
        }

        // generation of missing chunks, a few at a time
        if (generateMissing) {
            while (genInFlight.get() < MAX_GEN_IN_FLIGHT) {
                Long key = needGen.poll();
                if (key == null) break;
                genInFlight.incrementAndGet();
                int cx = (int) (long) key, cz = (int) (key >> 32);
                server.execute(() -> requestGenerate(cx, cz, gen));
            }
        }

        boolean allIssued = nextIndex >= total;
        if (allIssued && readsInFlight.get() == 0 && genInFlight.get() == 0 && needGen.isEmpty()) {
            drainResults();
            state = State.DONE;
            BlockMemory.get().save();
            BlockAtlasClient.LOGGER.info("Deep scan done: {} scanned, {} generated, {} missing, {} blocks found",
                    scanned.get(), generated.get(), missing.get(), found.get());
        }
    }

    private void drainResults() {
        Found f;
        while ((f = results.poll()) != null) {
            BlockMemory.get().addAll(f.block(), f.positions());
        }
    }

    // ---- disk -------------------------------------------------------------------------------

    private void requestRead(int cx, int cz, int gen) {
        ServerLevel lvl = level;
        try {
            lvl.getChunkSource().chunkMap.read(new ChunkPos(cx, cz)).whenCompleteAsync((opt, err) -> {
                if (gen != generation) return;
                try {
                    boolean ok = err == null && opt.isPresent() && parse(opt.get(), cx, cz);
                    if (ok) {
                        scanned.incrementAndGet();
                    } else {
                        missing.incrementAndGet();
                        if (generateMissing) needGen.add(BlockMemory.chunkKey(cx, cz));
                    }
                } catch (Throwable t) {
                    missing.incrementAndGet();
                } finally {
                    readsInFlight.decrementAndGet();
                }
            }, parser);
        } catch (Throwable t) {
            readsInFlight.decrementAndGet();
            missing.incrementAndGet();
        }
    }

    /** Decodes one saved chunk. Returns false if the chunk isn't generated far enough to have ores. */
    private boolean parse(CompoundTag chunk, int cx, int cz) {
        String status = chunk.getStringOr("Status", "");
        if (!status.isEmpty() && !ORES_PLACED.contains(status)) return false;
        ListTag sections = chunk.getListOrEmpty("sections");
        if (sections.size() == 0) return status.isEmpty() ? false : true;

        Map<Block, LongArrayList> hits = null;
        for (int s = 0; s < sections.size(); s++) {
            CompoundTag sec = sections.getCompoundOrEmpty(s);
            CompoundTag states = sec.getCompoundOrEmpty("block_states");
            ListTag palette = states.getListOrEmpty("palette");
            int n = palette.size();
            if (n == 0) continue;
            Block[] match = null;
            for (int i = 0; i < n; i++) {
                // 26.3 saves block states as {id, properties}; older saves used {Name, Properties}
                CompoundTag entry = palette.getCompoundOrEmpty(i);
                String name = entry.getStringOr("id", "");
                if (name.isEmpty()) name = entry.getStringOr("Name", "");
                Block b = targetsById.get(name);
                if (b != null) {
                    if (match == null) match = new Block[n];
                    match[i] = b;
                }
            }
            if (match == null) continue;

            int sy = sec.getByte("Y").map(Byte::intValue).orElse(0);
            int baseX = cx << 4, baseY = sy << 4, baseZ = cz << 4;
            if (hits == null) hits = new HashMap<>();
            if (n == 1) {
                // whole section is the target block (rare, e.g. a solid block of it)
                LongArrayList list = hits.computeIfAbsent(match[0], k -> new LongArrayList());
                for (int i = 0; i < 4096; i++) {
                    list.add(BlockPos.asLong(baseX + (i & 15), baseY + (i >> 8), baseZ + ((i >> 4) & 15)));
                }
                continue;
            }
            long[] data = states.getLongArray("data").orElse(null);
            if (data == null || data.length == 0) continue;
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(n - 1));
            int perLong = 64 / bits;
            long mask = (1L << bits) - 1;
            for (int i = 0; i < 4096; i++) {
                int li = i / perLong;
                if (li >= data.length) break;
                int idx = (int) ((data[li] >>> ((i % perLong) * bits)) & mask);
                if (idx < n && match[idx] != null) {
                    hits.computeIfAbsent(match[idx], k -> new LongArrayList())
                            .add(BlockPos.asLong(baseX + (i & 15), baseY + (i >> 8), baseZ + ((i >> 4) & 15)));
                }
            }
        }
        if (hits != null) publish(hits);
        return true;
    }

    // ---- generation (server thread) -------------------------------------------------------

    private void requestGenerate(int cx, int cz, int gen) {
        if (gen != generation) {
            genInFlight.decrementAndGet();
            return;
        }
        try {
            level.getChunkSource().getChunkFuture(cx, cz, ChunkStatus.FEATURES, true).whenComplete((res, err) -> {
                if (gen != generation) return; // stopped meanwhile; counters were reset
                try {
                    ChunkAccess chunk = err == null && res != null ? res.orElse(null) : null;
                    if (chunk != null) {
                        scanChunk(chunk, cx, cz);
                        generated.incrementAndGet();
                    }
                } catch (Throwable t) {
                    // ignore one bad chunk
                } finally {
                    genInFlight.decrementAndGet();
                }
            });
        } catch (Throwable t) {
            genInFlight.decrementAndGet();
        }
    }

    private void scanChunk(ChunkAccess chunk, int cx, int cz) {
        LevelChunkSection[] sections = chunk.getSections();
        Map<Block, LongArrayList> hits = null;
        int baseX = cx << 4, baseZ = cz << 4;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(targetPredicate)) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Block b = section.getBlockState(x, y, z).getBlock();
                        if (!targets.contains(b)) continue;
                        if (hits == null) hits = new HashMap<>();
                        hits.computeIfAbsent(b, k -> new LongArrayList()).add(BlockPos.asLong(baseX + x, baseY + y, baseZ + z));
                    }
                }
            }
        }
        if (hits != null) publish(hits);
    }

    private void publish(Map<Block, LongArrayList> hits) {
        for (Map.Entry<Block, LongArrayList> e : hits.entrySet()) {
            long[] arr = e.getValue().toLongArray();
            found.addAndGet(arr.length);
            results.add(new Found(e.getKey(), arr));
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Chunk offsets within a circle of the given block radius, nearest first. */
    private static int[] spiral(int radiusBlocks) {
        int rc = radiusBlocks >> 4;
        List<int[]> list = new ArrayList<>();
        long lim = (long) rc * rc;
        for (int dx = -rc; dx <= rc; dx++) {
            for (int dz = -rc; dz <= rc; dz++) {
                if ((long) dx * dx + (long) dz * dz <= lim) list.add(new int[]{dx, dz});
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
}
