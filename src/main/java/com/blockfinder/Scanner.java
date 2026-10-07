package com.blockfinder;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.*;

/** Scans loaded chunks within RADIUS a few chunks per tick so the game never stutters. */
public class Scanner {
    public static final Scanner INSTANCE = new Scanner();
    public static final int RADIUS = 300;
    public static final int MAX_RESULTS = 5000;
    private static final int CHUNKS_PER_TICK = 24;

    private Block target;
    private final List<BlockPos> found = Collections.synchronizedList(new ArrayList<>());
    private final Map<Long, List<BlockPos>> byChunk = new HashMap<>();
    private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
    private int rescanTimer;
    private boolean scanning;

    public Block target() { return target; }
    public List<BlockPos> results() { return found; }
    public boolean isScanning() { return scanning; }

    public void setTarget(Block b) {
        target = b; byChunk.clear(); found.clear(); queue.clear(); rescanTimer = 0;
    }

    public void tick(Minecraft mc) {
        if (target == null || mc.level == null || mc.player == null) return;
        if (queue.isEmpty()) {
            scanning = false;
            if (--rescanTimer > 0) return;
            rescanTimer = 100; // full refresh every 5 s
            ChunkPos c = mc.player.chunkPosition();
            int r = RADIUS >> 4;
            List<ChunkPos> all = new ArrayList<>();
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++)
                if (x * x + z * z <= r * r) all.add(new ChunkPos(c.x + x, c.z + z));
            all.sort(Comparator.comparingInt(p -> (p.x - c.x) * (p.x - c.x) + (p.z - c.z) * (p.z - c.z)));
            queue.addAll(all); // nearest chunks first
            scanning = true;
        }
        for (int i = 0; i < CHUNKS_PER_TICK && !queue.isEmpty(); i++) scanChunk(mc, queue.poll());
        rebuild(mc);
    }

    private void scanChunk(Minecraft mc, ChunkPos cp) {
        LevelChunk chunk = mc.level.getChunkSource().getChunkNow(cp.x, cp.z);
        if (chunk == null) { byChunk.remove(cp.toLong()); return; }
        List<BlockPos> hits = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        int minY = mc.level.getMinY();
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection sec = sections[s];
            if (sec == null || sec.hasOnlyAir()) continue;
            if (!sec.maybeHas(st -> st.is(target))) continue; // fast palette check
            int baseY = minY + (s << 4);
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                if (sec.getBlockState(x, y, z).is(target))
                    hits.add(new BlockPos(cp.getMinBlockX() + x, baseY + y, cp.getMinBlockZ() + z));
        }
        if (hits.isEmpty()) byChunk.remove(cp.toLong()); else byChunk.put(cp.toLong(), hits);
    }

    private void rebuild(Minecraft mc) {
        BlockPos p = mc.player.blockPosition();
        long r2 = (long) RADIUS * RADIUS;
        List<BlockPos> all = new ArrayList<>();
        for (List<BlockPos> l : byChunk.values()) for (BlockPos b : l) if (b.distSqr(p) <= r2) all.add(b);
        all.sort(Comparator.comparingDouble(b -> b.distSqr(p)));
        synchronized (found) { found.clear(); found.addAll(all.subList(0, Math.min(all.size(), MAX_RESULTS))); }
    }
}
