package com.deepseek.neoforbric.body;

import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ModListScreen;


final class DebugModListProbe {
    private DebugModListProbe() {}

    static void probe() {
        try {
            ModListScreen screen = new ModListScreen((Screen) null);
            BodyLog.info("[debug] constructed ModListScreen (%s), hide filter ran once",
                    screen.getClass().getName());
            dump();
        } catch (Throwable t) {
            BodyLog.error("[debug] ModListScreen construct failed: %s", t.toString());
        }
    }


    private static void dump() {
        List<ModContainer> all = ModList.get().getSortedMods();
        BodyLog.info("[debug] mods total=%d hide rules ids=%s prefixes=%s files=%s names=%s",
                all.size(), HideList.ids(), HideList.prefixes(), HideList.filePrefixes(), HideList.names());
        int hidden = 0;
        for (ModContainer container : all) {
            String fileName = owningFileName(container);
            boolean isHidden = HideList.matches(container.getModId(), container.getModInfo().getDisplayName(), fileName)
                    || chainMatches(container);
            if (isHidden) {
                hidden++;
            }
            BodyLog.info("[debug]   %-8s id=%-38s file=%s", isHidden ? "HIDDEN" : "visible",
                    container.getModId(), fileName);
        }
        BodyLog.info("[debug] hidden %d of %d", hidden, all.size());
    }


    private static boolean chainMatches(ModContainer container) {
        try {
            var owningFile = container.getModInfo().getOwningFile();
            if (owningFile == null || owningFile.getFile() == null) {
                return false;
            }
            var file = owningFile.getFile();
            int guard = 0;
            while (file != null && guard++ < 16) {
                if (HideList.matchesFile(file.getFileName())) {
                    return true;
                }
                var attributes = file.getDiscoveryAttributes();
                file = attributes == null ? null : attributes.parent();
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    private static String owningFileName(ModContainer container) {
        try {
            var owningFile = container.getModInfo().getOwningFile();
            return owningFile == null || owningFile.getFile() == null ? "(null)" : owningFile.getFile().getFileName();
        } catch (Throwable t) {
            return "(err:" + t.getClass().getSimpleName() + ")";
        }
    }
}
