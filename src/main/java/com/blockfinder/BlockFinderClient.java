package com.blockfinder;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class BlockFinderClient implements ClientModInitializer {
    public static KeyMapping OPEN, CLEAR;

    @Override
    public void onInitializeClient() {
        Theme.register();
        KeyMapping.Category cat = KeyMapping.Category.register(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("blockfinder", "main"));
        OPEN = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.blockfinder.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, cat));
        CLEAR = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.blockfinder.clear", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, cat));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (OPEN.consumeClick()) mc.setScreen(new BlockPickerScreen());
            while (CLEAR.consumeClick()) Scanner.INSTANCE.setTarget(null);
            Scanner.INSTANCE.tick(mc);
        });
        WorldRenderEvents.AFTER_ENTITIES.register(HighlightRenderer::render);
        HudRenderCallback.EVENT.register(Hud::render);
    }
}
