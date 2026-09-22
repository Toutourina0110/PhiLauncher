package dev.phi.phihud;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
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
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> HudRenderer.onJoin());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Tps.reset());
		HudConfig.get();

		KeyMapping key = Compat.registerKey(new KeyMapping("key.phihud.editor", InputConstants.KEY_H,
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("phihud", "main"))));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (key.consumeClick()) Compat.setScreen(client, Compat.newEditor(Compat.screen(client)));
			HudRenderer.tick(client);
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

	/** A half-row "Phi HUD" button centred under the vanilla "Options" row; {@code shiftBelow} pushes lower rows down (pause menu). */
	private static void addPhiButton(Screen screen, boolean shiftBelow) {
		List<AbstractWidget> widgets = Compat.widgets(screen);
		AbstractWidget options = widgets.stream()
			.filter(w -> w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("menu.options"))
			.findFirst().orElse(null);
		if (options == null) return;
		int right = widgets.stream().filter(w -> w.getY() == options.getY()).mapToInt(w -> w.getX() + w.getWidth()).max().orElse(options.getX() + options.getWidth());
		int y = options.getBottom() + 4;
		if (shiftBelow) widgets.stream().filter(w -> w.getY() >= y).forEach(w -> w.setY(w.getY() + 24));
		int w = options.getWidth(); // one half-row (98 px in vanilla), centred under the whole button block
		widgets.add(Button.builder(Component.literal("Phi HUD"), b -> Compat.setScreen(Compat.mc(), Compat.newEditor(screen)))
			.bounds((options.getX() + right - w) / 2, y, w, 20).build());
	}

	private static final String WORDMARK = "PHI LAUNCHER";
	private static final int WORDMARK_SPACING = 2; // extra px between glyphs
	private static final int WORDMARK_GAP = 6;     // px between the mark and the wordmark

	/** Called by LogoRendererMixin; true when the Phi lockup replaced the vanilla logo. */
	public static boolean drawLogo(Object g, int width, float alpha, int heightOffset) {
		Screen screen = Compat.screen(Compat.mc());
		if (!(screen instanceof TitleScreen)) return false;
		int buttons = Compat.widgets(screen).stream().mapToInt(AbstractWidget::getY).min().orElse(screen.height / 4 + 48);
		Font font = Compat.mc().font;
		int bottom = buttons - 8; // the lockup's baseline sits 8 px above the first button
		int mark = Math.min(64, bottom - WORDMARK_GAP - font.lineHeight - 2); // only shrinks when the screen is too short
		if (mark < 16) return false; // no room at all: leave the vanilla logo alone
		int top = bottom - font.lineHeight - WORDMARK_GAP - mark;
		Compat.drawLogo(g, (width - mark) / 2, top, mark, alpha); // 128x128 source scaled to mark x mark
		drawWordmark(Compat.wrapAny(g), font, width, top + mark + WORDMARK_GAP, alpha);
		return true;
	}

	/** "PHI LAUNCHER" centred under the mark, drawn per glyph so the letter-spacing is real. */
	private static void drawWordmark(Draw d, Font font, int width, int y, float alpha) {
		int a = (int) (alpha * 255f);
		int color = (a < 0 ? 0 : Math.min(a, 255)) << 24 | 0xB388FF;
		int total = -WORDMARK_SPACING;
		for (int i = 0; i < WORDMARK.length(); i++) total += font.width(WORDMARK.substring(i, i + 1)) + WORDMARK_SPACING;
		int x = (width - total) / 2;
		for (int i = 0; i < WORDMARK.length(); i++) {
			String c = WORDMARK.substring(i, i + 1);
			d.text(font, c, x, y, color, true);
			x += font.width(c) + WORDMARK_SPACING;
		}
	}
}
