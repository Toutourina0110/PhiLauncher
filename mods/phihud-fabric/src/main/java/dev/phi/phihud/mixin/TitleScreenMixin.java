package dev.phi.phihud.mixin;

import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces the random splash with the Phi one. */
@Mixin(TitleScreen.class)
abstract class TitleScreenMixin {
	@Shadow private SplashRenderer splash;

	@Inject(method = "init()V", at = @At("TAIL"))
	private void phihud$splash(CallbackInfo ci) {
		splash = new SplashRenderer(Component.literal("Powered by Phi Launcher"));
	}
}
