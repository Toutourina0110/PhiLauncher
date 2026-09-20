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

/** Lays out the enabled widgets per anchor and draws them. */
public final class HudRenderer {
	private static final int PAD = 2;   // background padding
	private static final int GAP = 1;   // between widgets in a group
	private static final int SLOT = 18; // item slot pitch (16 px icon + border)
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

	interface Widget {
		int width(Font font);
		int height(Font font);
		void draw(Draw g, Font font, int x, int y, HudConfig cfg);
	}

	private record Text(Supplier<String> text) implements Widget {
		public int width(Font font) { return font.width(text.get()); }
		public int height(Font font) { return font.lineHeight; }
		public void draw(Draw g, Font font, int x, int y, HudConfig cfg) {
			g.text(font, text.get(), x, y, cfg.argb(), cfg.shadow);
		}
	}

	/** A grid of item stacks: 16 px icons on translucent slot backgrounds, with vanilla count/durability decorations. */
	private record Items(int cols, int rows, Supplier<List<ItemStack>> stacks) implements Widget {
		public int width(Font font) { return cols * SLOT; }
		public int height(Font font) { return rows * SLOT; }
		public void draw(Draw g, Font font, int x, int y, HudConfig cfg) {
			List<ItemStack> list = stacks.get();
			for (int i = 0; i < cols * rows && i < list.size(); i++) {
				int sx = x + (i % cols) * SLOT + 1, sy = y + (i / cols) * SLOT + 1;
				g.fill(sx, sy, sx + 16, sy + 16, 0x40FFFFFF);
				ItemStack stack = list.get(i);
				if (stack.isEmpty()) continue;
				g.item(stack, sx, sy);
				g.itemDecorations(font, stack, sx, sy);
			}
		}
	}

	private static final Map<String, Widget> WIDGETS = Map.of(
		"fps", new Text(() -> "FPS: " + mc().getFps()),
		"tps", new Text(() -> Tps.get() < 0 ? "TPS: --" : String.format(Locale.ROOT, "TPS: %.1f", Tps.get())),
		"coords", new Text(() -> String.format(Locale.ROOT, "XYZ: %.1f / %.1f / %.1f", player().getX(), player().getY(), player().getZ())),
		"direction", new Text(HudRenderer::direction),
		"ping", new Text(HudRenderer::ping),
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

	public static void render(Draw g) {
		Minecraft mc = mc();
		HudConfig cfg = HudConfig.get();
		if (!cfg.enabled || mc.player == null || mc.level == null || Compat.hudHidden(mc)) return;

		float scale = (float) Math.max(0.25, Math.min(4.0, cfg.scale));
		Font font = mc.font;
		int w = (int) (g.width() / scale), h = (int) (g.height() / scale);

		g.pose().pushMatrix();
		g.pose().scale(scale, scale);
		for (String anchor : new String[]{"top-left", "top-right", "bottom-left", "bottom-right"}) {
			List<Widget> group = new ArrayList<>();
			cfg.widgets.entrySet().stream()
				.filter(e -> e.getValue().enabled && anchor.equals(e.getValue().anchor) && WIDGETS.containsKey(e.getKey()))
				.sorted(Comparator.comparingInt(e -> e.getValue().order))
				.forEach(e -> group.add(WIDGETS.get(e.getKey())));
			if (group.isEmpty()) continue;
			boolean right = anchor.endsWith("right"), bottom = anchor.startsWith("bottom");
			if (bottom) Collections.reverse(group); // bottom anchors stack upward: order 0 sits at the edge

			int gw = 0, gh = -GAP;
			for (Widget wd : group) {
				gw = Math.max(gw, wd.width(font));
				gh += wd.height(font) + GAP;
			}
			int x = right ? w - cfg.margin - PAD - gw : cfg.margin + PAD;
			int y = bottom ? h - cfg.margin - PAD - gh : cfg.margin + PAD;
			if (cfg.background) g.fill(x - PAD, y - PAD, x + gw + PAD, y + gh + PAD, cfg.backgroundArgb());
			for (Widget wd : group) {
				wd.draw(g, font, right ? x + gw - wd.width(font) : x, y, cfg);
				y += wd.height(font) + GAP;
			}
		}
		g.pose().popMatrix();
	}
}
