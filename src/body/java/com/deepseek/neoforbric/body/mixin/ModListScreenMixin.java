package com.deepseek.neoforbric.body.mixin;

import com.deepseek.neoforbric.body.BodyLog;
import com.deepseek.neoforbric.body.HideList;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ModListScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;


@Mixin(ModListScreen.class)
public abstract class ModListScreenMixin {

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/fml/ModList;getSortedMods()Ljava/util/List;"))
    private List<ModContainer> neoforbric$hideTranspiledDependencies(ModList modList) {
        List<ModContainer> all = modList.getSortedMods();
        List<ModContainer> visible = new ArrayList<>(all.size());
        List<String> hidden = new ArrayList<>();
        for (ModContainer container : all) {
            if (HideList.isHidden(container)) {
                hidden.add(container.getModInfo().getDisplayName() + "(" + container.getModId() + ")");
            } else {
                visible.add(container);
            }
        }
        if (!hidden.isEmpty()) {
            BodyLog.info("hid %d mod(s) in mod list: %s", hidden.size(), hidden);
        }
        return visible;
    }
}
