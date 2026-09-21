package dev.phi.phihud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** Lays out the widgets (anchor stacking or v2 free positions) and draws them. */
public final class HudRenderer {
	static final int PAD = 2;   // background padding
	private static final int GAP = 1;   // between widgets in a group
	private static final int SLOT = 18; // item slot pitch (16 px icon + border)
	static final String OFF = " (off)";
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

	interface Widget {
		int width(Font font);
		int height(Font font);
		/** @param argb text color; alpha < 0xFF dims text widgets (item icons ignore it). */
		void draw(Draw g, Font font, int x, int y, HudConfig cfg, int argb, String suffix);
	}

	/** One-line text; {@code sample} is shown when there is no player (editor on the title screen). */
	private record Text(Supplier<String> text, String sample) implements Widget {
		Text(Supplier<String> text) { this(text, null); }
		String get() { return sample != null && player() == null ? sample : text.get(); }
		public int width(Font font) { return font.width(get()); }
		public int height(Font font) { return font.lineHeight; }
		public void draw(Draw g, Font font, int x, int y, HudConfig cfg, int argb, String suffix) {
			g.text(font, get() + suffix, x, y, argb, cfg.shadow);
		}
	}

	/** A grid of item stacks: 16 px icons on translucent slot backgrounds, with vanilla count/durability decorations. */
	private record Items(int cols, int rows, Supplier<List<ItemStack>> stacks) implements Widget {
		public int width(Font font) { return cols * SLOT; }
		public int height(Font font) { return rows * SLOT; }
		public void draw(Draw g, Font font, int x, int y, HudConfig cfg, int argb, String suffix) {
			List<ItemStack> list = player() == null ? List.of() : stacks.get();
			for (int i = 0; i < cols * rows; i++) {
				int sx = x + (i % cols) * SLOT + 1, sy = y + (i / cols) * SLOT + 1;
				g.fill(sx, sy, sx + 16, sy + 16, 0x40FFFFFF);
				if (i >= list.size() || list.get(i).isEmpty()) continue;
				g.item(list.get(i), sx, sy);
				g.itemDecorations(font, list.get(i), sx, sy);
			}
			// ponytail: item icons are drawn opaque even when dimmed; the suffix is the "(off)" cue
			if (!suffix.isEmpty()) g.text(font, suffix.trim(), x + 1, y + 1, argb, true);
		}
	}

	private static final Map<String, Widget> WIDGETS = Map.of(
		"fps", new Text(() -> "FPS: " + mc().getFps()),
		"tps", new Text(() -> Tps.get() < 0 ? "TPS: --" : String.format(Locale.ROOT, "TPS: %.1f", Tps.get())),
		"coords", new Text(() -> String.format(Locale.ROOT, "XYZ: %.1f / %.1f / %.1f", player().getX(), player().getY(), player().getZ()), "XYZ: 0.0 / 64.0 / 0.0"),
		"direction", new Text(HudRenderer::direction, "Facing: North (-Z) (0.0 / 0.0)"),
		"ping", new Text(HudRenderer::ping, "Ping: --"),
		"memory", new Text(HudRenderer::memory),
		"clock", new Text(() -> LocalTime.now().format(CLOCK)),
		"armor", new Items(5, 1, () -> List.of(
			player().getItemBySlot(EquipmentSlot.HEAD), player().getItemBySlot(EquipmentSlot.CHEST),
			player().getItemBySlot(EquipmentSlot.LEGS), player().getItemBySlot(EquipmentSlot.FEET),
			player().getMainHandItem())),
		"inventory", new Items(9, 3, () -> {
			List<ItemStack> l = new ArrayList<>(27);
			for (int i = 9; i < 36; i++) l.add(player().getInventory().getItem(i)); // main inventory, hotbar (0-8) excluded
			return l;
		})
	);

	private static Minecraft mc() { return Minecraft.getInstance(); }
	private static LocalPlayer player() { return mc().player; }

	private static String direction() {
		LocalPlayer p = player();
		Direction d = p.getDirection();
		String name = d.getName().substring(0, 1).toUpperCase(Locale.ROOT) + d.getName().substring(1);
		String axis = (d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? "+" : "-") + d.getAxis().getName().toUpperCase(Locale.ROOT);
		return String.format(Locale.ROOT, "Facing: %s (%s) (%.1f / %.1f)", name, axis, Mth.wrapDegrees(p.getYRot()), Mth.wrapDegrees(p.getXRot()));
	}

	private static String ping() {
		Minecraft mc = mc();
		PlayerInfo info = mc.getSingleplayerServer() != null || mc.getConnection() == null ? null
			: mc.getConnection().getPlayerInfo(mc.player.getUUID());
		return info == null ? "Ping: --" : "Ping: " + info.getLatency() + " ms";
	}

	private static String memory() {
		Runtime rt = Runtime.getRuntime();
		long max = rt.maxMemory() >> 20, used = (rt.totalMemory() - rt.freeMemory()) >> 20;
		return String.format(Locale.ROOT, "Mem: %d / %d MB (%d%%)", used, max, max == 0 ? 0 : used * 100 / max);
	}

	// ---- layout (screen pixels; each widget is drawn at its own scale) ----

	/** A widget placed in screen pixels: content box {@code x,y,w,h} (already multiplied by its scale {@code s}). */
	record Placed(String id, Widget widget, int x, int y, int w, int h, float s) {
		int pad() { return Math.round(PAD * s); }
		boolean contains(double px, double py) {
			return px >= x - pad() && px < x + w + pad() && py >= y - pad() && py < y + h + pad();
		}
	}

	/** Widgets sharing one background box: an anchor stack, or a single free-positioned widget. */
	record Group(List<Placed> items, int x, int y, int w, int h, int pad, boolean background) {}

	static float scale(HudConfig cfg) { return HudConfig.clampScale(cfg.scale); }

	/** Content width in the editor: disabled text widgets carry the "(off)" suffix. */
	static int width(Widget wd, Font font, boolean off) {
		return wd.width(font) + (off && wd instanceof Text ? font.width(OFF) : 0);
	}

	private static Placed measure(HudConfig cfg, Font font, String id) {
		HudConfig.Widget c = cfg.widgets.get(id);
		Widget wd = WIDGETS.get(id);
		float s = cfg.scale(c);
		return new Placed(id, wd, 0, 0, Math.round(width(wd, font, !c.enabled) * s), Math.round(wd.height(font) * s), s);
	}

	/**
	 * @param w,h    screen size in pixels
	 * @param alsoId a disabled widget to include anyway (the one selected in the menu), or null
	 */
	static List<Group> layout(HudConfig cfg, Font font, int w, int h, String alsoId) {
		List<Group> groups = new ArrayList<>();
		Map<String, List<String>> stacks = new java.util.LinkedHashMap<>();
		float gs = scale(cfg);
		int margin = Math.round(cfg.margin * gs), gpad = Math.round(PAD * gs);
		for (Map.Entry<String, HudConfig.Widget> e : cfg.widgets.entrySet()) {
			HudConfig.Widget c = e.getValue();
			if (!WIDGETS.containsKey(e.getKey()) || !(c.enabled || e.getKey().equals(alsoId))) continue;
			if (!c.free()) {
				stacks.computeIfAbsent(c.anchor, k -> new ArrayList<>()).add(e.getKey());
				continue;
			}
			Placed m = measure(cfg, font, e.getKey());
			int px = (int) Math.round(c.x * w), py = (int) Math.round(c.y * h);
			Placed p = new Placed(m.id, m.widget, c.x < 0.5 ? px : px - m.w, c.y < 0.5 ? py : py - m.h, m.w, m.h, m.s);
			groups.add(new Group(List.of(p), p.x, p.y, p.w, p.h, p.pad(), cfg.background(c)));
		}
		for (String anchor : new String[]{"top-left", "top-right", "bottom-left", "bottom-right"}) {
			List<String> ids = stacks.getOrDefault(anchor, List.of());
			if (ids.isEmpty()) continue;
			ids.sort(Comparator.comparingInt(id -> cfg.widgets.get(id).order));
			boolean right = anchor.endsWith("right"), bottom = anchor.startsWith("bottom");
			if (bottom) Collections.reverse(ids); // bottom anchors stack upward: order 0 sits at the edge

			List<Placed> sizes = new ArrayList<>();
			int gw = 0, gh = -GAP;
			boolean bg = false;
			for (String id : ids) {
				Placed m = measure(cfg, font, id);
				sizes.add(m);
				gw = Math.max(gw, m.w);
				gh += m.h + GAP;
				bg |= cfg.background(cfg.widgets.get(id)); // ponytail: one box per stack; any member with background on draws it
			}
			int x = right ? w - margin - gpad - gw : margin + gpad;
			int y = bottom ? h - margin - gpad - gh : margin + gpad;
			List<Placed> items = new ArrayList<>();
			for (Placed m : sizes) {
				items.add(new Placed(m.id, m.widget, right ? x + gw - m.w : x, y, m.w, m.h, m.s));
				y += m.h + GAP;
			}
			groups.add(new Group(items, x, items.get(0).y, gw, gh, gpad, bg));
		}
		return groups;
	}

	static void drawGroupBackground(Draw g, Group grp, HudConfig cfg) {
		if (grp.background) g.fill(grp.x - grp.pad, grp.y - grp.pad, grp.x + grp.w + grp.pad, grp.y + grp.h + grp.pad, cfg.backgroundArgb());
	}

	static void drawWidget(Draw g, Font font, Placed p, HudConfig cfg, boolean off) {
		int argb = cfg.argb(cfg.widgets.get(p.id));
		if (off) argb = (argb & 0xFFFFFF) | 0x66000000;
		g.pose().pushMatrix();
		g.pose().translate(p.x, p.y);
		g.pose().scale(p.s, p.s);
		p.widget.draw(g, font, 0, 0, cfg, argb, off ? OFF : "");
		g.pose().popMatrix();
	}

	public static void render(Draw g) {
		Minecraft mc = mc();
		HudConfig cfg = HudConfig.get();
		if (!cfg.enabled || mc.player == null || mc.level == null || Compat.hudHidden(mc)) return;
		Font font = mc.font;
		for (Group grp : layout(cfg, font, g.width(), g.height(), null)) {
			drawGroupBackground(g, grp, cfg);
			for (Placed p : grp.items) drawWidget(g, font, p, cfg, false);
		}
	}
}
