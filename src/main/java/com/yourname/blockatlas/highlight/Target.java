package com.yourname.blockatlas.highlight;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

/**
 * One navigation target picked on the map. Shown as a tall beacon in the world (visible from
 * any distance) and as a distance + direction line on the HUD. Cleared on arrival.
 */
public final class Target {
    private static Block block;
    private static long pos;
    private static boolean set;

    private Target() {}

    public static void set(Block b, long p) {
        block = b;
        pos = p;
        set = true;
    }

    public static void clear() {
        set = false;
        block = null;
    }

    public static boolean isSet() {
        return set;
    }

    public static Block block() {
        return block;
    }

    public static long pos() {
        return pos;
    }

    public static boolean is(Block b, long p) {
        return set && block == b && pos == p;
    }

    public static void tick(Minecraft mc) {
        if (!set || mc.player == null) return;
        double dx = BlockPos.getX(pos) + 0.5 - mc.player.getX();
        double dy = BlockPos.getY(pos) + 0.5 - mc.player.getEyeY();
        double dz = BlockPos.getZ(pos) + 0.5 - mc.player.getZ();
        if (dx * dx + dy * dy + dz * dz < 9.0) {
            mc.player.sendOverlayMessage(Component.translatable("blockatlas.target.reached",
                    block.getName()).withStyle(ChatFormatting.GREEN));
            clear();
        }
    }
}
