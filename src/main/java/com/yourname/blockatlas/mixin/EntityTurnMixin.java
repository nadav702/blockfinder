package com.yourname.blockatlas.mixin;

import com.yourname.blockatlas.freecam.Freecam;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While freecam is on, mouse look rotates the camera instead of the player. */
@Mixin(Entity.class)
public abstract class EntityTurnMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void blockatlas$redirectTurn(double yaw, double pitch, CallbackInfo ci) {
        if (Freecam.isActive() && (Object) this instanceof LocalPlayer) {
            Freecam.camera().turn(yaw, pitch);
            ci.cancel();
        }
    }
}
