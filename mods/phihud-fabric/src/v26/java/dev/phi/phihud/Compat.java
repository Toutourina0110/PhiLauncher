package dev.phi.phihud;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

import java.util.List;
import java.util.function.Consumer;

/** Minecraft 26.2+: GuiGraphicsExtractor, Gui.screen(), Hud.isHidden(), Fabric screen API v5 / key-mapping API. */
final class Compat {
	static final String LOGO_METHOD = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IFI)V";
	private static final Identifier LOGO = Identifier.fromNamespaceAndPath("phihud", "textures/gui/phi_logo.png");

	static Minecraft mc() { return Minecraft.getInstance(); }

	static Draw wrap(GuiGraphicsExtractor g) {
		return new Draw() {
			public int width() { return g.guiWidth(); }
			public int height() { return g.guiHeight(); }
			public Matrix3x2fStack pose() { return g.pose(); }
			public void fill(int x1, int y1, int x2, int y2, int argb) { g.fill(x1, y1, x2, y2, argb); }
			public void text(Font f, String s, int x, int y, int argb, boolean shadow) { g.text(f, s, x, y, argb, shadow); }
			public void text(Font f, Component c, int x, int y, int argb, boolean shadow) { g.text(f, c, x, y, argb, shadow); }
			public void item(ItemStack stack, int x, int y) { g.item(stack, x, y); }
			public void itemDecorations(Font f, ItemStack stack, int x, int y) { g.itemDecorations(f, stack, x, y); }
		};
	}

	/** Same wrapper for the @Coerce'd Object the logo mixin hands over. */
	static Draw wrapAny(Object g) { return wrap((GuiGraphicsExtractor) g); }

	static void registerHud(Consumer<Draw> renderer) {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("phihud", "hud"), (g, tick) -> renderer.accept(wrap(g)));
	}

	/** True while a screen is open, the GUI is hidden (F1) or the debug screen (F3) is shown. */
	static boolean hudHidden(Minecraft mc) {
		return mc.gui.screen() != null || mc.gui.hud.isHidden() || mc.getDebugOverlay().showDebugScreen();
	}

	/** World day time in ticks (0 = dawn of day 0). */
	static long dayTime(ClientLevel level) { return level.getOverworldClockTime(); }

	static Screen screen(Minecraft mc) { return mc.gui.screen(); }
	static void setScreen(Minecraft mc, Screen s) { mc.gui.setScreen(s); }
	static KeyMapping registerKey(KeyMapping key) { return KeyMappingHelper.registerKeyMapping(key); }
	static List<AbstractWidget> widgets(Screen screen) { return Screens.getWidgets(screen); }

	static void afterRender(Screen screen, Consumer<Draw> draw) {
		ScreenEvents.afterExtract(screen).register((s, g, mx, my, tick) -> draw.accept(wrap(g)));
	}

	/** Phi logo: the whole 128x128 texture (source region 128x128) scaled into a {@code size} x {@code size} box. */
	static void drawLogo(Object graphics, int x, int y, int size, float alpha) {
		((GuiGraphicsExtractor) graphics).blit(RenderPipelines.GUI_TEXTURED, LOGO, x, y, 0f, 0f, size, size, 128, 128, 128, 128, ARGB.white(alpha));
	}

	static Screen newEditor(Screen parent) {
		return new HudMenuScreen(parent) {
			@Override
			public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float tick) {
				if (minecraft.level == null) super.extractBackground(g, mx, my, tick); // panorama on the title screen, clear view in-game
				drawBackground(wrap(g), mx, my);
			}

			@Override
			protected WidgetList newList(int width, int height, int y) {
				return new WidgetList(minecraft, width, height, y) {
					@Override protected void extractListBackground(GuiGraphicsExtractor g) {}
					@Override protected void extractListSeparators(GuiGraphicsExtractor g) {}
				};
			}

			@Override
			protected Row newRow(String id) {
				return new Row(id) {
					@Override
					public void extractContent(GuiGraphicsExtractor g, int mx, int my, boolean hovered, float tick) { draw(wrap(g), mx, my, hovered); }
				};
			}
		};
	}
}
