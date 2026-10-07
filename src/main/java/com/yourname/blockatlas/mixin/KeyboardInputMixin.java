package com.yourname.blockatlas.mixin;

import com.yourname.blockatlas.freecam.Freecam;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While freecam is on, the real player receives no movement input (it stands still). */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void blockatlas$freezePlayer(CallbackInfo ci) {
        if (Freecam.isActive()) {
            this.keyPresses = Input.EMPTY;
            this.moveVector = Vec2.ZERO;
        }
    }
}
