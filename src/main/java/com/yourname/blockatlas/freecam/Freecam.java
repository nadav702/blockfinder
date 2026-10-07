package com.yourname.blockatlas.freecam;

import com.yourname.blockatlas.config.BlockAtlasConfig;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side free camera.
 *
 * <p>When enabled, the view detaches into an invisible camera entity that is not added to the
 * world (the server never sees it). The real player stays exactly where it was: its movement
 * input is zeroed by {@code KeyboardInputMixin} and mouse look is redirected to the camera by
 * {@code EntityTurnMixin}. Attacking and using items/blocks is blocked while flying, so
 * nothing can be broken or placed from the camera position.</p>
 */
public final class Freecam {
    private static Marker camera;
    private static Level cameraLevel;
    private static Vec3 velocity = Vec3.ZERO;

    private Freecam() {}

    public static boolean isActive() {
        return camera != null;
    }

    public static Marker camera() {
        return camera;
    }

    public static void register() {
        AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> block(level));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> block(level));
        UseItemCallback.EVENT.register((player, level, hand) -> block(level));
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> block(level));
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> block(level));
    }

    private static InteractionResult block(Level level) {
        return isActive() && level.isClientSide() ? InteractionResult.FAIL : InteractionResult.PASS;
    }

    public static void toggle() {
        if (isActive()) disable(true);
        else enable();
    }

    private static void enable() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        Marker cam = new Marker(EntityTypes.MARKER, mc.level);
        Vec3 eye = player.getEyePosition();
        cam.setPos(eye.x, eye.y, eye.z);
        cam.xo = eye.x;
        cam.yo = eye.y;
        cam.zo = eye.z;
        cam.setYRot(player.getYRot());
        cam.setXRot(player.getXRot());
        cam.yRotO = player.getYRot();
        cam.xRotO = player.getXRot();

        camera = cam;
        cameraLevel = mc.level;
        velocity = Vec3.ZERO;
        mc.setCameraEntity(cam);
        player.sendOverlayMessage(Component.translatable("blockatlas.freecam.on").withStyle(ChatFormatting.AQUA));
    }

    public static void disable(boolean announce) {
        if (camera == null) return;
        camera = null;
        cameraLevel = null;
        velocity = Vec3.ZERO;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
            if (announce) {
                mc.player.sendOverlayMessage(Component.translatable("blockatlas.freecam.off").withStyle(ChatFormatting.GRAY));
            }
        }
    }

    /** Called every client tick. Moves the camera from the live key state. */
    public static void tick(Minecraft mc) {
        if (camera == null) return;
        if (mc.player == null || mc.level == null || mc.level != cameraLevel || !mc.player.isAlive()) {
            disable(false);
            return;
        }
        // Something else (e.g. spectating a mob) took the camera: just drop out of freecam.
        if (mc.getCameraEntity() != camera) {
            camera = null;
            cameraLevel = null;
            return;
        }

        Marker cam = camera;
        cam.xo = cam.getX();
        cam.yo = cam.getY();
        cam.zo = cam.getZ();
        cam.yRotO = cam.getYRot();
        cam.xRotO = cam.getXRot();

        Options o = mc.options;
        boolean typing = mc.gui.screen() != null;
        double forward = typing ? 0 : (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
        double strafe = typing ? 0 : (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0);
        double vertical = typing ? 0 : (o.keyJump.isDown() ? 1 : 0) - (o.keyShift.isDown() ? 1 : 0);

        double speed = BlockAtlasConfig.get().freecamSpeed * (!typing && o.keySprint.isDown() ? 3.0 : 1.0);
        double yaw = Math.toRadians(cam.getYRot());
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        Vec3 target = new Vec3(strafe * cos - forward * sin, vertical, forward * cos + strafe * sin);
        if (target.lengthSqr() > 1e-6) target = target.normalize().scale(speed);

        // Smooth acceleration / glide to a stop.
        velocity = velocity.scale(0.55).add(target.scale(0.45));
        if (velocity.lengthSqr() < 1e-6) velocity = Vec3.ZERO;
        cam.setPos(cam.getX() + velocity.x, cam.getY() + velocity.y, cam.getZ() + velocity.z);
    }
}
