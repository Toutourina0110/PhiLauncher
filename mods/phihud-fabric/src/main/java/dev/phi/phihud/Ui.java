package dev.phi.phihud;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/** The mod's TrueType UI font ({@code assets/phihud/font/ui.json}): styles menu text with it and measures it with the real font metrics. */
final class Ui {
	static final Identifier FONT = Identifier.fromNamespaceAndPath("phihud", "ui");
	private static final Style STYLE = Style.EMPTY.withFont(new FontDescription.Resource(FONT));

	private Ui() {}

	static Component text(String s) { return Component.literal(s).setStyle(STYLE); }

	static int width(Font font, String s) { return font.width(text(s)); }
}
