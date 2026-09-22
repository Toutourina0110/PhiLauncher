package dev.phi.phihud;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

/** The few 2D drawing calls the HUD needs; implemented per Minecraft version in Compat (GuiGraphics vs GuiGraphicsExtractor). */
interface Draw {
	int width();
	int height();
	Matrix3x2fStack pose();
	void fill(int x1, int y1, int x2, int y2, int argb);
	void text(Font font, String s, int x, int y, int argb, boolean shadow);
	void text(Font font, Component c, int x, int y, int argb, boolean shadow);
	void item(ItemStack stack, int x, int y);
	void itemDecorations(Font font, ItemStack stack, int x, int y);
}
