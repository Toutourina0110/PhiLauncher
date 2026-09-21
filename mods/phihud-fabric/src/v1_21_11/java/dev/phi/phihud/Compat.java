package dev.phi.phihud;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

import java.util.List;
import java.util.function.Consumer;

/** Minecraft 1.21.11: GuiGraphics, Minecraft.screen, Options.hideGui, Fabric screen API v3 / key-binding API. */
final class Compat {
	static final String LOGO_METHOD = "renderLogo(Lnet/minecraft/client/gui/GuiGraphics;IFI)V";
	private static final Identifier LOGO = Identifier.fromNamespaceAndPath("phihud", "textures/gui/phi_logo.png");

	static Minecraft mc() { return Minecraft.getInstance(); }

	static Draw wrap(GuiGraphics g) {
		return new Draw() {
			public int width() { return g.guiWidth(); }
			public int height() { return g.guiHeight(); }
			public Matrix3x2fStack pose() { return g.pose(); }
			public void fill(int x1, int y1, int x2, int y2, int argb) { g.fill(x1, y1, x2, y2, argb); }
			public void text(Font f, String s, int x, int y, int argb, boolean shadow) { g.drawString(f, s, x, y, argb, shadow); }
			public void item(ItemStack stack, int x, int y) { g.renderItem(stack, x, y); }
			public void itemDecorations(Font f, ItemStack stack, int x, int y) { g.renderItemDecorations(f, stack, x, y); }
		};
	}

	static void registerHud(Consumer<Draw> renderer) {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("phihud", "hud"), (g, tick) -> renderer.accept(wrap(g)));
	}

	/** True while a screen is open, the GUI is hidden (F1) or the debug screen (F3) is shown. */
	static boolean hudHidden(Minecraft mc) {
		return mc.screen != null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen();
	}

	static Screen screen(Minecraft mc) { return mc.screen; }
	static void setScreen(Minecraft mc, Screen s) { mc.setScreen(s); }
	static KeyMapping registerKey(KeyMapping key) { return KeyBindingHelper.registerKeyBinding(key); }
	static List<AbstractWidget> widgets(Screen screen) { return Screens.getButtons(screen); }

	static void afterRender(Screen screen, Consumer<Draw> draw) {
		ScreenEvents.afterRender(screen).register((s, g, mx, my, tick) -> draw.accept(wrap(g)));
	}

	/** Phi logo, 192x96, centered where the vanilla logo + edition banner sit. */
	static void drawLogo(Object graphics, int width, float alpha, int heightOffset) {
		((GuiGraphics) graphics).blit(RenderPipelines.GUI_TEXTURED, LOGO, width / 2 - 96, heightOffset - 22, 0, 0, 192, 96, 512, 256, ARGB.white(alpha));
	}

	static Screen newEditor(Screen parent) {
		return new HudEditorScreen(parent) {
			@Override
			public void renderBackground(GuiGraphics g, int mx, int my, float tick) {
				if (minecraft.level == null) super.renderBackground(g, mx, my, tick); // panorama on the title screen, clear view in-game
				drawBoxes(wrap(g), mx, my);
			}
		};
	}
}
