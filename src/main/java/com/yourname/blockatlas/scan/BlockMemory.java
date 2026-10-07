package com.yourname.blockatlas.scan;

import com.yourname.blockatlas.BlockAtlasClient;
import com.yourname.blockatlas.config.BlockAtlasConfig;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Remembers every highlighted block the scanner has ever found, per world (or server) and per
 * dimension, so the map can show blocks far outside the loaded area. Positions are pruned
 * when their chunk is scanned again and the block is gone (mined, exploded…).
 *
 * <p>Stored in {@code config/blockatlas/memory/<world>/<dimension>.bin.gz}.</p>
 */
public final class BlockMemory {
    private static final BlockMemory INSTANCE = new BlockMemory();
    private static final int FILE_VERSION = 1;

    public static BlockMemory get() {
        return INSTANCE;
    }

    private final Map<Block, LongOpenHashSet> data = new LinkedHashMap<>();
    private final Map<Block, LongOpenHashSet> view = Collections.unmodifiableMap(data);
    private String worldKey;
    private String dimensionKey;
    private boolean dirty;
    private long lastSave;
    private int version;
    private Object lastLevel;

    private BlockMemory() {}

    /** Read-only view for the map. Iterate on the client thread only. */
    public Map<Block, LongOpenHashSet> all() {
        return view;
    }

    public int count(Block block) {
        LongOpenHashSet s = data.get(block);
        return s == null ? 0 : s.size();
    }

    /** Increments on every change, so views can cache. */
    public int version() {
        return version;
    }

    public String worldName() {
        return worldKey == null ? "" : worldKey.substring(worldKey.indexOf('_') + 1);
    }

    // ---- lifecycle --------------------------------------------------------------------------

    public void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            unload();
            return;
        }
        if (mc.level == lastLevel && worldKey != null) {
            if (dirty && System.currentTimeMillis() - lastSave > 60_000) save();
            return;
        }
        lastLevel = mc.level;
        String world = worldKey(mc);
        String dim = mc.level.dimension().identifier().toString();
        if (!world.equals(worldKey) || !dim.equals(dimensionKey)) {
            unload();
            worldKey = world;
            dimensionKey = dim;
            load();
        }
        if (dirty && System.currentTimeMillis() - lastSave > 60_000) save();
    }

    public void unload() {
        if (worldKey != null && dirty) save();
        data.clear();
        worldKey = null;
        dimensionKey = null;
        lastLevel = null;
        dirty = false;
        version++;
    }

    /** Forgets everything remembered for the current world (all dimensions). */
    public void forgetWorld() {
        if (worldKey == null) return;
        data.clear();
        dirty = false;
        version++;
        try {
            Path dir = root().resolve(sanitize(worldKey));
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    for (Path f : (Iterable<Path>) files::iterator) Files.deleteIfExists(f);
                }
            }
        } catch (IOException e) {
            BlockAtlasClient.LOGGER.warn("Could not delete block memory", e);
        }
    }

    private static String worldKey(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            Path p = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path name = p.getFileName();
            return "sp_" + (name != null ? name.toString() : server.getWorldData().getLevelName());
        }
        ServerData sd = mc.getCurrentServer();
        if (sd != null) return "mp_" + sd.ip;
        return "other_unknown";
    }

    // ---- updates from the scanner ----------------------------------------------------------

    /**
     * Merges one complete scan pass.
     *
     * @param found         every position found per target block (also empty arrays)
     * @param scannedChunks packed chunk coordinates that were loaded and scanned
     * @param ox            scan origin x / y / z
     * @param radius        scan radius; only remembered positions inside it are re-verified
     */
    public void merge(Map<Block, long[]> found, LongSet scannedChunks,
                      double ox, double oy, double oz, double radius) {
        if (worldKey == null || !BlockAtlasConfig.get().rememberBlocks) return;
        int cap = BlockAtlasConfig.get().memoryMaxPerBlock;
        double r2 = radius * radius;
        boolean changed = false;

        for (Map.Entry<Block, long[]> e : found.entrySet()) {
            long[] now = e.getValue();
            LongOpenHashSet set = data.get(e.getKey());
            if (set == null) {
                if (now.length == 0) continue;
                set = new LongOpenHashSet(Math.max(16, now.length));
                data.put(e.getKey(), set);
            }
            LongOpenHashSet nowSet = new LongOpenHashSet(now);

            // prune positions that were re-scanned and are gone
            for (LongIterator it = set.iterator(); it.hasNext(); ) {
                long p = it.nextLong();
                if (nowSet.contains(p)) continue;
                int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
                if (!scannedChunks.contains(chunkKey(x >> 4, z >> 4))) continue;
                double dx = x + 0.5 - ox, dy = y + 0.5 - oy, dz = z + 0.5 - oz;
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                it.remove();
                changed = true;
            }
            for (long p : now) {
                if (set.size() >= cap) break;
                if (set.add(p)) changed = true;
            }
            if (set.isEmpty()) data.remove(e.getKey());
        }
        if (changed) {
            dirty = true;
            version++;
        }
    }

    /** Adds positions without pruning (used by the deep scan of far, unloaded chunks). */
    public void addAll(Block block, long[] positions) {
        if (worldKey == null || positions.length == 0) return;
        int cap = BlockAtlasConfig.get().memoryMaxPerBlock;
        LongOpenHashSet set = data.computeIfAbsent(block, k -> new LongOpenHashSet(Math.max(16, positions.length)));
        boolean changed = false;
        for (long p : positions) {
            if (set.size() >= cap) break;
            if (set.add(p)) changed = true;
        }
        if (changed) {
            dirty = true;
            version++;
        }
    }

    public static long chunkKey(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) | ((long) cz << 32);
    }

    // ---- persistence -----------------------------------------------------------------------

    private static Path root() {
        return FabricLoader.getInstance().getConfigDir().resolve("blockatlas").resolve("memory");
    }

    private Path file() {
        return root().resolve(sanitize(worldKey)).resolve(sanitize(dimensionKey) + ".bin.gz");
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void load() {
        data.clear();
        version++;
        Path f = file();
        if (!Files.exists(f)) return;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(f))))) {
            if (in.readInt() != FILE_VERSION) return;
            int blocks = in.readInt();
            for (int b = 0; b < blocks; b++) {
                String id = in.readUTF();
                int n = in.readInt();
                LongOpenHashSet set = new LongOpenHashSet(Math.max(16, n));
                for (int i = 0; i < n; i++) set.add(in.readLong());
                Identifier key = Identifier.tryParse(id);
                if (key == null) continue;
                BuiltInRegistries.BLOCK.getOptional(key).ifPresent(block -> data.put(block, set));
            }
        } catch (IOException e) {
            BlockAtlasClient.LOGGER.warn("Could not read block memory {}", f, e);
        }
        lastSave = System.currentTimeMillis();
    }

    public void save() {
        if (worldKey == null) return;
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp))))) {
                out.writeInt(FILE_VERSION);
                out.writeInt(data.size());
                for (Map.Entry<Block, LongOpenHashSet> e : data.entrySet()) {
                    out.writeUTF(BuiltInRegistries.BLOCK.getKey(e.getKey()).toString());
                    out.writeInt(e.getValue().size());
                    for (LongIterator it = e.getValue().iterator(); it.hasNext(); ) out.writeLong(it.nextLong());
                }
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
            lastSave = System.currentTimeMillis();
        } catch (IOException e) {
            BlockAtlasClient.LOGGER.warn("Could not save block memory {}", f, e);
        }
    }
}
