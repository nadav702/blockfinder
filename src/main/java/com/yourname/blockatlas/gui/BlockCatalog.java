package com.yourname.blockatlas.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Snapshot of every registered block (vanilla and modded) with its icon, translated name and
 * categories. Rebuilt automatically when the game language changes.
 */
public final class BlockCatalog {

    public record Entry(Block block, String id, String shortId, String name, String nameLower,
                        ItemStack icon, Set<Category> categories) {
        public boolean hasIcon() {
            return !icon.isEmpty();
        }

        public boolean matches(String[] tokens) {
            for (String t : tokens) {
                if (!nameLower.contains(t) && !id.contains(t)) return false;
            }
            return true;
        }
    }

    private static BlockCatalog instance;
    private static String builtForLanguage;

    public static BlockCatalog get() {
        String lang = Minecraft.getInstance().options.languageCode;
        if (instance == null || !Objects.equals(lang, builtForLanguage)) {
            instance = new BlockCatalog();
            builtForLanguage = lang;
        }
        return instance;
    }

    private final List<Entry> entries;
    private final Map<Block, Entry> byBlock = new IdentityHashMap<>();

    private BlockCatalog() {
        List<Entry> list = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block.defaultBlockState().isAir()) continue;
            Identifier key = BuiltInRegistries.BLOCK.getKey(block);
            String id = key.toString();
            String shortId = "minecraft".equals(key.getNamespace()) ? key.getPath() : id;
            String name = block.getName().getString();
            ItemStack icon = new ItemStack(block.asItem());
            Set<Category> cats = Collections.unmodifiableSet(classify(key.getPath(), !icon.isEmpty()));
            Entry e = new Entry(block, id, shortId, name, name.toLowerCase(Locale.ROOT), icon, cats);
            list.add(e);
            byBlock.put(block, e);
        }
        list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        entries = Collections.unmodifiableList(list);
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry entry(Block block) {
        return byBlock.get(block);
    }

    public int size() {
        return entries.size();
    }

    // ---- classification ---------------------------------------------------------------------
    // Keyword heuristics on the registry path work for vanilla and for most modded blocks,
    // which follow vanilla naming ("_ore", "_planks", "_button" ...).

    private static final String[] TECH_EXACT = {
            "barrier", "light", "structure_void", "structure_block", "jigsaw", "moving_piston", "piston_head",
            "spawner", "trial_spawner", "vault", "end_portal", "end_portal_frame", "end_gateway", "nether_portal",
            "bedrock", "reinforced_deepslate", "test_block", "test_instance_block", "bubble_column", "frosted_ice"
    };
    private static final String[] TECH_PARTS = {"command_block", "structure_", "portal", "infested", "petrified"};
    private static final String[] REDSTONE_PARTS = {
            "redstone", "repeater", "comparator", "piston", "observer", "hopper", "dropper", "dispenser", "lever",
            "button", "pressure_plate", "tripwire", "daylight_detector", "target", "sculk_sensor", "rail", "tnt",
            "note_block", "crafter", "lightning_rod", "trapped_chest", "copper_bulb"
    };
    private static final String[] FUNCTIONAL_PARTS = {
            "table", "cutter", "furnace", "smoker", "anvil", "grindstone", "loom", "barrel", "chest", "brewing",
            "cauldron", "composter", "beacon", "conduit", "lectern", "respawn_anchor", "lodestone", "_bed",
            "shulker_box", "scaffolding", "ladder", "jukebox", "campfire"
    };
    private static final String[] SHAPED_PARTS = {
            "planks", "brick", "_slab", "_stairs", "_wall", "fence", "door", "polished", "smooth", "cut_",
            "chiseled", "tiles", "pillar", "_pane", "glass", "sign", "carpet", "_bars", "chain", "potted",
            "concrete"
    };
    private static final String[] NATURAL_PARTS = {
            "stone", "dirt", "grass", "sand", "gravel", "clay", "_log", "_wood", "stem", "hyphae", "leaves", "sapling",
            "flower", "tulip", "orchid", "allium", "bluet", "daisy", "dandelion", "poppy", "cornflower", "lily",
            "rose", "peony", "lilac", "fern", "bush", "vine", "kelp", "seagrass", "coral", "mushroom", "fungus",
            "roots", "nylium", "netherrack", "basalt", "blackstone", "deepslate", "tuff", "calcite", "dripstone",
            "amethyst", "ice", "snow", "obsidian", "end_stone", "moss", "mud", "propagule", "bamboo", "cactus",
            "sugar_cane", "pumpkin", "melon", "sculk", "wheat", "carrots", "potatoes", "beetroots", "cobweb", "magma",
            "soul_s", "glowstone", "sponge", "bee_nest", "podzol", "mycelium", "water", "lava", "andesite", "diorite",
            "granite", "lichen", "spore", "dripleaf", "azalea", "berry", "cocoa", "pitcher", "torchflower", "egg",
            "frogspawn", "chorus", "wart", "shroomlight", "weeping", "twisting", "pale", "eyeblossom", "leaf_litter",
            "wildflowers", "dry_grass", "cactus_flower", "fire"
    };
    private static final String[] BUILDING_PARTS = {
            "_block", "bricks", "planks", "concrete", "terracotta", "copper", "quartz", "purpur", "prismarine", "glass"
    };
    private static final String[] DECOR_PARTS = {
            "carpet", "banner", "candle", "potted", "flower_pot", "_head", "skull", "glazed", "wool", "stained_glass",
            "_pane", "lantern", "torch", "sign", "chain", "bell", "_bed", "decorated_pot", "bookshelf", "campfire",
            "end_rod", "froglight", "sea_lantern", "shulker_box", "jukebox", "heavy_core", "shelf", "lamp"
    };

    static EnumSet<Category> classify(String p, boolean hasItem) {
        EnumSet<Category> c = EnumSet.noneOf(Category.class);
        if (isOre(p)) c.add(Category.ORES);
        if (!hasItem || equalsAny(p, TECH_EXACT) || containsAny(p, TECH_PARTS)) c.add(Category.TECHNICAL);
        if (containsAny(p, REDSTONE_PARTS)) c.add(Category.REDSTONE);

        boolean functional = containsAny(p, FUNCTIONAL_PARTS);
        boolean shaped = containsAny(p, SHAPED_PARTS);
        if (!c.contains(Category.ORES) && !c.contains(Category.REDSTONE) && !shaped && !functional
                && containsAny(p, NATURAL_PARTS)) {
            c.add(Category.NATURAL);
        }
        if (containsAny(p, DECOR_PARTS)) c.add(Category.DECORATIVE);
        if (shaped || functional || containsAny(p, BUILDING_PARTS)) c.add(Category.BUILDING);
        if (c.isEmpty()) c.add(Category.BUILDING);
        return c;
    }

    private static boolean isOre(String p) {
        return p.endsWith("_ore") || p.equals("ancient_debris") || p.equals("gilded_blackstone")
                || p.equals("budding_amethyst") || p.equals("amethyst_cluster") || p.endsWith("amethyst_bud")
                || (p.startsWith("raw_") && p.endsWith("_block"));
    }

    private static boolean containsAny(String s, String[] parts) {
        for (String part : parts) if (s.contains(part)) return true;
        return false;
    }

    private static boolean equalsAny(String s, String[] values) {
        for (String v : values) if (s.equals(v)) return true;
        return false;
    }
}
