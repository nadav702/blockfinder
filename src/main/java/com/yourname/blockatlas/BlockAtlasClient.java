package com.yourname.blockatlas;

import com.mojang.blaze3d.platform.InputConstants;
import com.yourname.blockatlas.config.BlockAtlasConfig;
import com.yourname.blockatlas.freecam.Freecam;
import com.yourname.blockatlas.gui.BlockAtlasScreen;
import com.yourname.blockatlas.gui.HudOverlay;
import com.yourname.blockatlas.gui.MapScreen;
import com.yourname.blockatlas.highlight.Target;
import com.yourname.blockatlas.scan.BlockMemory;
import com.yourname.blockatlas.scan.DeepScanner;
import com.yourname.blockatlas.highlight.BoxRenderer;
import com.yourname.blockatlas.highlight.HighlightManager;
import com.yourname.blockatlas.scan.BlockScanner;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. Wires together the config, key mappings, the incremental scanner,
 * the highlight manager, the world box renderer and the HUD overlay.
 */
public final class BlockAtlasClient implements ClientModInitializer {
    public static final String MOD_ID = "blockatlas";
    public static final Logger LOGGER = LoggerFactory.getLogger("BlockAtlas");

    public static KeyMapping openKey;
    public static KeyMapping toggleKey;
    public static KeyMapping freecamKey;
    public static KeyMapping mapKey;

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitializeClient() {
        BlockAtlasConfig.load();
        HighlightManager.get().loadFromConfig();

        KeyMapping.Category category = KeyMapping.Category.register(id("main"));
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.blockatlas.open", InputConstants.KEY_B, category));
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.blockatlas.toggle", InputConstants.UNKNOWN.getValue(), category));

        freecamKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.blockatlas.freecam", InputConstants.KEY_F6, category));
        mapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.blockatlas.map", InputConstants.KEY_M, category));

        Freecam.register();
        BoxRenderer.register();
        HudOverlay.register();

        ClientTickEvents.END_CLIENT_TICK.register(BlockAtlasClient::onEndTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            BlockAtlasConfig.get().save();
            DeepScanner.get().stop();
            BlockMemory.get().unload();
            BoxRenderer.close();
        });

        LOGGER.info("BlockAtlas initialised");
    }

    private static void onEndTick(Minecraft client) {
        while (openKey.consumeClick()) {
            if (client.player != null) {
                client.gui.setScreen(new BlockAtlasScreen());
            }
        }
        while (toggleKey.consumeClick()) {
            BlockAtlasConfig cfg = BlockAtlasConfig.get();
            cfg.enabled = !cfg.enabled;
            cfg.save();
            BlockScanner.get().requestRescan();
            if (client.player != null) {
                client.player.sendSystemMessage(Component.translatable(
                        cfg.enabled ? "blockatlas.chat.enabled" : "blockatlas.chat.disabled")
                        .withStyle(cfg.enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY));
            }
        }

        while (freecamKey.consumeClick()) {
            Freecam.toggle();
        }
        while (mapKey.consumeClick()) {
            if (client.player != null) client.gui.setScreen(new MapScreen(null));
        }

        BlockMemory.get().tick(client);
        DeepScanner.get().tick(client);

        if (client.level == null || client.player == null) {
            Target.clear();
            Freecam.disable(false);
            BlockScanner.get().reset();
            HighlightManager.get().clearResults();
            return;
        }

        Freecam.tick(client);
        Target.tick(client);
        BlockScanner.get().tick(client);
        HighlightManager.get().tick(client);
    }
}
