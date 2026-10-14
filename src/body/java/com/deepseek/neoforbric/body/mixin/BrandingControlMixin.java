package com.deepseek.neoforbric.body.mixin;

import com.deepseek.neoforbric.body.BodyLog;
import com.deepseek.neoforbric.body.HideList;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.internal.BrandingControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;


@Mixin(BrandingControl.class)
public abstract class BrandingControlMixin {

    @Redirect(method = "computeBranding", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/fml/ModList;size()I"))
    private static int neoforbric$visibleModCount(ModList modList) {
        int total = modList.size();
        int hidden = HideList.hiddenCount();
        if (hidden > 0) {
            BodyLog.info("title screen mod count: %d visible of %d (%d hidden)", total - hidden, total, hidden);
        }
        return Math.max(0, total - hidden);
    }
}
