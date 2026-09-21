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
import net.minecraft.client.multiplayer.ClientLevel;
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

	/** World day time in ticks (0 = dawn of day 0). */
	static long dayTime(ClientLevel level) { return level.getDayTime(); }

	static Screen screen(Minecraft mc) { return mc.screen; }
	static void setScreen(Minecraft mc, Screen s) { mc.setScreen(s); }
	static KeyMapping registerKey(KeyMapping key) { return KeyBindingHelper.registerKeyBinding(key); }
	static List<AbstractWidget> widgets(Screen screen) { return Screens.getButtons(screen); }

	static void afterRender(Screen screen, Consumer<Draw> draw) {
		ScreenEvents.afterRender(screen).register((s, g, mx, my, tick) -> draw.accept(wrap(g)));
	}

	/** Phi logo: 128x128 texture drawn 1:1 (region == texture size; {@code size} < 128 only shrinks it on tiny screens). */
	static void drawLogo(Object graphics, int x, int y, int size, float alpha) {
		((GuiGraphics) graphics).blit(RenderPipelines.GUI_TEXTURED, LOGO, x, y, 0f, 0f, size, size, 128, 128, 128, 128, ARGB.white(alpha));
	}

	static Screen newEditor(Screen parent) {
		return new HudMenuScreen(parent) {
			@Override
			public void renderBackground(GuiGraphics g, int mx, int my, float tick) {
				if (minecraft.level == null) super.renderBackground(g, mx, my, tick); // panorama on the title screen, clear view in-game
				drawBackground(wrap(g), mx, my);
			}

			@Override
			protected WidgetList newList(int width, int height, int y) {
				return new WidgetList(minecraft, width, height, y) {
					@Override protected void renderListBackground(GuiGraphics g) {}
					@Override protected void renderListSeparators(GuiGraphics g) {}
				};
			}

			@Override
			protected Row newRow(String id) {
				return new Row(id) {
					@Override
					public void renderContent(GuiGraphics g, int mx, int my, boolean hovered, float tick) { draw(wrap(g), mx, my, hovered); }
				};
			}
		};
	}
}
