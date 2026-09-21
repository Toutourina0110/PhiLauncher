package dev.phi.phihud.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.phi.phihud.Cps;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts attack / use presses for the CPS widget; {@code click} is the static press hook every binding goes through. */
@Mixin(KeyMapping.class)
abstract class KeyMappingMixin {
	@Inject(method = "click", at = @At("HEAD"))
	private static void phihud$click(InputConstants.Key key, CallbackInfo ci) {
		Cps.onPress(key);
	}
}
