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

	// ---- layout ----

	/** A widget placed at scaled-screen coordinates (content box, background adds PAD around it). */
	record Placed(String id, Widget widget, int x, int y, int w, int h) {
		boolean contains(double px, double py) {
			return px >= x - PAD && px < x + w + PAD && py >= y - PAD && py < y + h + PAD;
		}
	}

	/** Widgets sharing one background box: an anchor stack, or a single free-positioned widget. */
	record Group(List<Placed> items, int x, int y, int w, int h) {}

	static float scale(HudConfig cfg) { return (float) Math.max(0.25, Math.min(4.0, cfg.scale)); }

	/** Content width in the editor: disabled text widgets carry the "(off)" suffix. */
	static int width(Widget wd, Font font, boolean off) {
		return wd.width(font) + (off && wd instanceof Text ? font.width(OFF) : 0);
	}

	/**
	 * @param w,h    scaled screen size
	 * @param editor include disabled widgets (drawn with the "(off)" suffix) so they can be dragged
	 */
	static List<Group> layout(HudConfig cfg, Font font, int w, int h, boolean editor) {
		List<Group> groups = new ArrayList<>();
		Map<String, List<String>> stacks = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, HudConfig.Widget> e : cfg.widgets.entrySet()) {
			HudConfig.Widget c = e.getValue();
			Widget wd = WIDGETS.get(e.getKey());
			if (wd == null || !(c.enabled || editor)) continue;
			if (!c.free()) {
				stacks.computeIfAbsent(c.anchor, k -> new ArrayList<>()).add(e.getKey());
				continue;
			}
			int ww = width(wd, font, !c.enabled), hh = wd.height(font);
			int px = (int) Math.round(c.x * w), py = (int) Math.round(c.y * h);
			Placed p = new Placed(e.getKey(), wd, c.x < 0.5 ? px : px - ww, c.y < 0.5 ? py : py - hh, ww, hh);
			groups.add(new Group(List.of(p), p.x, p.y, ww, hh));
		}
		for (String anchor : new String[]{"top-left", "top-right", "bottom-left", "bottom-right"}) {
			List<String> ids = stacks.getOrDefault(anchor, List.of());
			if (ids.isEmpty()) continue;
			ids.sort(Comparator.comparingInt(id -> cfg.widgets.get(id).order));
			boolean right = anchor.endsWith("right"), bottom = anchor.startsWith("bottom");
			if (bottom) Collections.reverse(ids); // bottom anchors stack upward: order 0 sits at the edge

			int gw = 0, gh = -GAP;
			for (String id : ids) {
				gw = Math.max(gw, width(WIDGETS.get(id), font, !cfg.widgets.get(id).enabled));
				gh += WIDGETS.get(id).height(font) + GAP;
			}
			int x = right ? w - cfg.margin - PAD - gw : cfg.margin + PAD;
			int y = bottom ? h - cfg.margin - PAD - gh : cfg.margin + PAD;
			List<Placed> items = new ArrayList<>();
			for (String id : ids) {
				Widget wd = WIDGETS.get(id);
				int ww = width(wd, font, !cfg.widgets.get(id).enabled);
				items.add(new Placed(id, wd, right ? x + gw - ww : x, y, ww, wd.height(font)));
				y += wd.height(font) + GAP;
			}
			groups.add(new Group(items, x, items.get(0).y, gw, gh));
		}
		return groups;
	}

	static void drawWidget(Draw g, Font font, Placed p, HudConfig cfg, boolean off) {
		int argb = off ? (cfg.argb() & 0xFFFFFF) | 0x66000000 : cfg.argb();
		p.widget.draw(g, font, p.x, p.y, cfg, argb, off ? OFF : "");
	}

	public static void render(Draw g) {
		Minecraft mc = mc();
		HudConfig cfg = HudConfig.get();
		if (!cfg.enabled || mc.player == null || mc.level == null || Compat.hudHidden(mc)) return;

		float scale = scale(cfg);
		Font font = mc.font;
		g.pose().pushMatrix();
		g.pose().scale(scale, scale);
		for (Group grp : layout(cfg, font, (int) (g.width() / scale), (int) (g.height() / scale), false)) {
			if (cfg.background) g.fill(grp.x - PAD, grp.y - PAD, grp.x + grp.w + PAD, grp.y + grp.h + PAD, cfg.backgroundArgb());
			for (Placed p : grp.items) drawWidget(g, font, p, cfg, false);
		}
		g.pose().popMatrix();
	}
}
