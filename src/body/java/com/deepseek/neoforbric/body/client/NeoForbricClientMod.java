package com.deepseek.neoforbric.body.client;

import com.deepseek.neoforbric.body.BodyLog;
import com.deepseek.neoforbric.body.NeoForbricMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;


@Mod(value = NeoForbricMod.MOD_ID, dist = Dist.CLIENT)
public final class NeoForbricClientMod {
    private static final String CLOTH_CONFIG = "cloth_config";

    public NeoForbricClientMod(ModContainer container) {
        if (!ModList.get().isLoaded(CLOTH_CONFIG)) {

            BodyLog.info("cloth_config not installed; config screen disabled "
                    + "(edit config/neoforbric/config.properties by hand)");
            return;
        }

        NeoForbricClothScreen.register(container);
        BodyLog.info("cloth_config detected; config screen registered");
    }
}
