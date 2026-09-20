package dev.phi.phihud;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

import java.util.function.Consumer;

/** Minecraft 1.21.11: GuiGraphics, Minecraft.screen, Options.hideGui. */
final class Compat {
	static void registerHud(Consumer<Draw> renderer) {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("phihud", "hud"), (g, tick) -> renderer.accept(new Draw() {
			public int width() { return g.guiWidth(); }
			public int height() { return g.guiHeight(); }
			public Matrix3x2fStack pose() { return g.pose(); }
			public void fill(int x1, int y1, int x2, int y2, int argb) { g.fill(x1, y1, x2, y2, argb); }
			public void text(Font f, String s, int x, int y, int argb, boolean shadow) { g.drawString(f, s, x, y, argb, shadow); }
			public void item(ItemStack stack, int x, int y) { g.renderItem(stack, x, y); }
			public void itemDecorations(Font f, ItemStack stack, int x, int y) { g.renderItemDecorations(f, stack, x, y); }
		}));
	}

	/** True while a screen is open, the GUI is hidden (F1) or the debug screen (F3) is shown. */
	static boolean hudHidden(Minecraft mc) {
		return mc.screen != null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen();
	}
}
