package dev.phi.phihud;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class PhiHud implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("phihud");
	/** Mixin target of the vanilla logo draw; differs per Minecraft version (see Compat). */
	public static final String LOGO_METHOD = Compat.LOGO_METHOD;

	@Override
	public void onInitializeClient() {
		Compat.registerHud(HudRenderer::render);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Tps.reset());
		HudConfig.get();

		KeyMapping key = Compat.registerKey(new KeyMapping("key.phihud.editor", InputConstants.KEY_H,
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("phihud", "main"))));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (key.consumeClick()) Compat.setScreen(client, Compat.newEditor(Compat.screen(client)));
		});

		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof TitleScreen) {
				addPhiButton(screen, false);
				Compat.afterRender(screen, g -> g.text(client.font, "Phi Launcher", 2, g.height() - 20, 0xFFB388FF, true));
			} else if (screen instanceof PauseScreen) {
				addPhiButton(screen, true);
			}
		});
		LOGGER.info("[phihud] Phi HUD loaded");
	}

	/** A full-row "Phi HUD" button under the vanilla "Options" row; {@code shiftBelow} pushes lower rows down (pause menu). */
	private static void addPhiButton(Screen screen, boolean shiftBelow) {
		List<AbstractWidget> widgets = Compat.widgets(screen);
		AbstractWidget options = widgets.stream()
			.filter(w -> w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("menu.options"))
			.findFirst().orElse(null);
		if (options == null) return;
		int right = widgets.stream().filter(w -> w.getY() == options.getY()).mapToInt(w -> w.getX() + w.getWidth()).max().orElse(options.getX() + options.getWidth());
		int y = options.getBottom() + 4;
		if (shiftBelow) widgets.stream().filter(w -> w.getY() >= y).forEach(w -> w.setY(w.getY() + 24));
		widgets.add(Button.builder(Component.literal("Phi HUD"), b -> Compat.setScreen(Compat.mc(), Compat.newEditor(screen)))
			.bounds(options.getX(), y, right - options.getX(), 20).build());
	}

	/** Called by LogoRendererMixin; true when the Phi logo replaced the vanilla one. */
	public static boolean drawLogo(Object g, int width, float alpha, int heightOffset) {
		if (!(Compat.screen(Compat.mc()) instanceof TitleScreen)) return false;
		Compat.drawLogo(g, width, alpha, heightOffset);
		return true;
	}
}
