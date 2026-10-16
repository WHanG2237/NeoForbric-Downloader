package com.deepseek.neoforbric.body;

import java.util.List;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;


@Mod(NeoForbricMod.MOD_ID)
public final class NeoForbricMod {
    public static final String MOD_ID = "neoforbric";


    private static final String DEBUG_FORCE_LOAD = "neoforbric.debug.forceLoadModListScreen";


    private static String sinceJvmStart() {
        try {
            var start = ProcessHandle.current().info().startInstant().orElse(null);
            if (start == null) {
                return "unknown";
            }
            double seconds = java.time.Duration.between(start, java.time.Instant.now()).toNanos() / 1_000_000_000.0;
            return String.format(java.util.Locale.ROOT, "%.1f s", seconds);
        } catch (Throwable t) {
            return "unknown";
        }
    }

    public NeoForbricMod(IEventBus modEventBus, ModContainer modContainer) {
        BodyLog.displayName(modContainer.getModInfo().getDisplayName());
        BodyLog.game("%s %s ready (total startup overhead %s)", BodyLog.tag(),
                modContainer.getModInfo().getVersion(), sinceJvmStart());
        BodyLog.info("body loaded: version=%s", modContainer.getModInfo().getVersion());
        BodyLog.info("jvm->modConstruct: %s", sinceJvmStart());
        List<String> hidden = List.copyOf(HideList.ids());
        BodyLog.debug("hide list: %d entries %s", hidden.size(), hidden);


        modEventBus.addListener(net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent.class, event -> {
            if (Boolean.getBoolean(DEBUG_FORCE_LOAD)) {
                DebugModListProbe.probe();
            }
        });
    }
}
