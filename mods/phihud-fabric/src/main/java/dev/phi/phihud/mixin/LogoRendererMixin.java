package dev.phi.phihud.mixin;

import dev.phi.phihud.PhiHud;
import net.minecraft.client.gui.components.LogoRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces the vanilla logo (+ edition banner) with the Phi logo on the title screen. */
@Mixin(LogoRenderer.class)
abstract class LogoRendererMixin {
	// The graphics parameter type differs per version (GuiGraphics vs GuiGraphicsExtractor): coerced to Object, cast in Compat.
	@Inject(method = PhiHud.LOGO_METHOD, at = @At("HEAD"), cancellable = true)
	private void phihud$logo(@Coerce Object g, int width, float alpha, int heightOffset, CallbackInfo ci) {
		if (PhiHud.drawLogo(g, width, alpha, heightOffset)) ci.cancel();
	}
}
