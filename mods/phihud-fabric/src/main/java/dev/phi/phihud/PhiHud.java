package dev.phi.phihud;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PhiHud implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("phihud");

	@Override
	public void onInitializeClient() {
		Compat.registerHud(HudRenderer::render);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Tps.reset());
		HudConfig.get();
		LOGGER.info("[phihud] Phi HUD loaded");
	}
}
