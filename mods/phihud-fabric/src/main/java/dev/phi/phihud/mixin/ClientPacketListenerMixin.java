package dev.phi.phihud.mixin;

import dev.phi.phihud.Tps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
	// RETURN: the handler re-schedules itself onto the main thread by throwing, so a normal return
	// only happens once, on the render thread, after the level time was set.
	@Inject(method = "handleSetTime", at = @At("RETURN"))
	private void phihud$onSetTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
		var level = Minecraft.getInstance().level;
		if (level != null) Tps.onTimePacket(level.getGameTime());
	}
}
