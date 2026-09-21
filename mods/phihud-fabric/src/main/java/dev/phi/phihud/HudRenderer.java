package dev.phi.phihud;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Lays out the widgets (anchor stacking or v2 free positions) and draws them.
 * <p>
 * Performance contract (spec v4): widget content is refreshed from {@link #tick} every {@code every} client ticks and
 * cached; the HUD layout is cached and rebuilt only when the config revision, the screen size or a cached size
 * changes; {@link #render} allocates nothing.
 */
public final class HudRenderer {
	static final int PAD = 2;   // background padding
	private static final int GAP = 1;   // between widgets in a group
	private static final int SLOT = 18; // item slot pitch (16 px icon + border)
	static final String OFF = " (off)";
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

	/** A widget with a cached content size in font pixels ({@code w,h}), refreshed on the client tick. */
	abstract static class Widget {
		final int every; // refresh period in client ticks
		int w, h;
		boolean fresh;   // false until the first refresh

		Widget(int every) { this.every = every; }

		/** Recomputes the cached content; returns true when the size changed (the layout must be rebuilt). */
		boolean refresh(Font font) { return false; }

		/** Draws at the origin, already scaled. @param argb text color; alpha < 0xFF dims text widgets. */
		abstract void draw(Draw g, Font font, HudConfig cfg, int argb, String suffix);
	}

	/** One-line text; {@code sample} is shown when there is no player (menu on the title screen). */
	private static final class Text extends Widget {
		private final Supplier<String> fn;
		private final String sample;
		String text = "";

		Text(int every, String sample, Supplier<String> fn) {
			super(every);
			this.fn = fn;
			this.sample = sample;
		}

		@Override
		boolean refresh(Font font) {
			String s = sample != null && player() == null ? sample : fn.get();
			if (s.equals(text) && h != 0) return false;
			text = s;
			int nw = font.width(s), nh = font.lineHeight;
			if (nw == w && nh == h) return false;
			w = nw;
			h = nh;
			return true;
		}

		@Override
		void draw(Draw g, Font font, HudConfig cfg, int argb, String suffix) {
			g.text(font, suffix.isEmpty() ? text : text + suffix, 0, 0, argb, cfg.shadow);
		}
	}

	/** A grid of item stacks: 16 px icons on translucent slot backgrounds, with vanilla count/durability decorations. */
	private static final class Items extends Widget {
		private final int cols, rows;
		private final IntFunction<ItemStack> stack;

		Items(int cols, int rows, IntFunction<ItemStack> stack) {
			super(2);
			this.cols = cols;
			this.rows = rows;
			this.stack = stack;
			w = cols * SLOT;
			h = rows * SLOT;
		}

		@Override
		void draw(Draw g, Font font, HudConfig cfg, int argb, String suffix) {
			boolean live = player() != null;
			for (int i = 0; i < cols * rows; i++) {
				int sx = (i % cols) * SLOT + 1, sy = (i / cols) * SLOT + 1;
				g.fill(sx, sy, sx + 16, sy + 16, 0x40FFFFFF);
				if (!live) continue;
				ItemStack st = stack.apply(i);
				if (st.isEmpty()) continue;
				g.item(st, sx, sy);
				g.itemDecorations(font, st, sx, sy);
			}
			// ponytail: item icons are drawn opaque even when dimmed; the suffix is the "(off)" cue
			if (!suffix.isEmpty()) g.text(font, suffix.trim(), 1, 1, argb, true);
		}
	}

	/** Mini keyboard: W / A S D / LMB RMB / space; pressed keys are filled with the widget color. */
	private static final class Keys extends Widget {
		private static final int KW = 16, KH = 14, G = 2, BAR = 6;

		Keys() {
			super(2);
			w = 3 * KW + 2 * G;      // 52
			h = 3 * KH + 3 * G + BAR; // 54
		}

		@Override
		void draw(Draw g, Font font, HudConfig cfg, int argb, String suffix) {
			net.minecraft.client.Options o = mc().options;
			int row1 = KH + G, row2 = 2 * (KH + G), row3 = 3 * (KH + G);
			key(g, font, KW + G, 0, KW, KH, "W", o.keyUp, argb);
			key(g, font, 0, row1, KW, KH, "A", o.keyLeft, argb);
			key(g, font, KW + G, row1, KW, KH, "S", o.keyDown, argb);
			key(g, font, 2 * (KW + G), row1, KW, KH, "D", o.keyRight, argb);
			key(g, font, 0, row2, KW + (KW + G) / 2 - G / 2, KH, "LMB", o.keyAttack, argb);
			key(g, font, KW + (KW + G) / 2 + G / 2, row2, w - KW - (KW + G) / 2 - G / 2, KH, "RMB", o.keyUse, argb);
			key(g, font, 0, row3, w, BAR, "", o.keyJump, argb);
			if (!suffix.isEmpty()) g.text(font, suffix.trim(), 1, 1, argb, true);
		}

		private static void key(Draw g, Font font, int x, int y, int kw, int kh, String label, KeyMapping km, int argb) {
			boolean down = km.isDown();
			g.fill(x, y, x + kw, y + kh, down ? argb : 0x80000000);
			if (!label.isEmpty()) g.text(font, label, x + (kw - font.width(label)) / 2, y + (kh - font.lineHeight) / 2 + 1, down ? 0xFF202020 : argb, false);
		}
	}

	/** Active potion effects, one line each ("Speed II 1:23"). */
	private static final class Effects extends Widget {
		private final ArrayList<String> lines = new ArrayList<>();

		Effects() { super(2); }

		@Override
		boolean refresh(Font font) {
			lines.clear();
			LocalPlayer p = player();
			if (p == null) lines.add("Speed II 1:23");
			else {
				for (MobEffectInstance e : p.getActiveEffects()) lines.add(effect(e));
				if (lines.isEmpty()) lines.add("No effects");
			}
			int nw = 0;
			for (int i = 0; i < lines.size(); i++) nw = Math.max(nw, font.width(lines.get(i)));
			int nh = lines.size() * font.lineHeight;
			if (nw == w && nh == h) return false;
			w = nw;
			h = nh;
			return true;
		}

		@Override
		void draw(Draw g, Font font, HudConfig cfg, int argb, String suffix) {
			for (int i = 0; i < lines.size(); i++) {
				String s = lines.get(i);
				g.text(font, i == 0 && !suffix.isEmpty() ? s + suffix : s, 0, i * font.lineHeight, argb, cfg.shadow);
			}
		}

		private static String effect(MobEffectInstance e) {
			StringBuilder b = new StringBuilder(e.getEffect().value().getDisplayName().getString());
			int amp = e.getAmplifier();
			if (amp > 0) b.append(' ').append(amp < ROMAN.length ? ROMAN[amp] : String.valueOf(amp + 1));
			b.append(' ');
			if (e.isInfiniteDuration()) b.append("**:**");
			else {
				int sec = e.getDuration() / 20;
				b.append(sec / 60).append(':');
				if (sec % 60 < 10) b.append('0');
				b.append(sec % 60);
			}
			return b.toString();
		}
	}

	private static final String[] ROMAN = {"", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

	// ---- widget registry ----

	private static final Map<String, Widget> WIDGETS = new LinkedHashMap<>();
	private static final String[] IDS;
	private static final Widget[] ALL;
	static {
		WIDGETS.put("fps", new Text(1, null, () -> "FPS: " + mc().getFps()));
		WIDGETS.put("tps", new Text(2, null, () -> Tps.get() < 0 ? "TPS: --" : String.format(Locale.ROOT, "TPS: %.1f", Tps.get())));
		WIDGETS.put("coords", new Text(2, "XYZ: 0.0 / 64.0 / 0.0", () -> String.format(Locale.ROOT, "XYZ: %.1f / %.1f / %.1f", player().getX(), player().getY(), player().getZ())));
		WIDGETS.put("direction", new Text(2, "Facing: North (-Z) (0.0 / 0.0)", HudRenderer::direction));
		WIDGETS.put("ping", new Text(2, "Ping: --", HudRenderer::ping));
		WIDGETS.put("memory", new Text(2, null, HudRenderer::memory));
		WIDGETS.put("clock", new Text(2, null, () -> LocalTime.now().format(CLOCK)));
		WIDGETS.put("armor", new Items(5, 1, HudRenderer::armor));
		WIDGETS.put("inventory", new Items(9, 3, i -> player().getInventory().getItem(i + 9))); // main inventory, hotbar (0-8) excluded
		// v4
		WIDGETS.put("speed", new Text(1, "Speed: 0.0 b/s", () -> String.format(Locale.ROOT, "Speed: %.1f b/s", speed())));
		WIDGETS.put("biome", new Text(2, "Biome: Plains", HudRenderer::biome));
		WIDGETS.put("light", new Text(2, "Light: 15 (sky 15)", HudRenderer::light));
		WIDGETS.put("target", new Text(2, "Looking at: --", HudRenderer::target));
		WIDGETS.put("gametime", new Text(2, "Day 0, 06:00", HudRenderer::gameTime));
		WIDGETS.put("server", new Text(2, "Singleplayer", HudRenderer::server));
		WIDGETS.put("session", new Text(2, "Session: 0m", HudRenderer::session));
		WIDGETS.put("effects", new Effects());
		WIDGETS.put("keystrokes", new Keys());
		WIDGETS.put("cps", new Text(1, null, () -> "CPS: " + Cps.left() + " | " + Cps.right()));
		WIDGETS.put("hunger", new Text(2, "Food: 20/20  Sat: 5.0", HudRenderer::hunger));
		WIDGETS.put("health", new Text(2, "HP: 20.0/20  Armor: 0", HudRenderer::health));
		IDS = WIDGETS.keySet().toArray(new String[0]);
		ALL = WIDGETS.values().toArray(new Widget[0]);
	}

	private static Minecraft mc() { return Minecraft.getInstance(); }
	private static LocalPlayer player() { return mc().player; }

	private static ItemStack armor(int i) {
		LocalPlayer p = player();
		return switch (i) {
			case 0 -> p.getItemBySlot(EquipmentSlot.HEAD);
			case 1 -> p.getItemBySlot(EquipmentSlot.CHEST);
			case 2 -> p.getItemBySlot(EquipmentSlot.LEGS);
			case 3 -> p.getItemBySlot(EquipmentSlot.FEET);
			default -> p.getMainHandItem();
		};
	}

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

	private static String biome() {
		LocalPlayer p = player();
		String path = mc().level.getBiome(p.blockPosition()).unwrapKey().map(k -> k.identifier().getPath()).orElse("unknown");
		StringBuilder b = new StringBuilder("Biome: ");
		boolean up = true;
		for (int i = 0; i < path.length(); i++) {
			char c = path.charAt(i);
			if (c == '_') { b.append(' '); up = true; }
			else { b.append(up ? Character.toUpperCase(c) : c); up = false; }
		}
		return b.toString();
	}

	private static String light() {
		LocalPlayer p = player();
		ClientLevel level = mc().level;
		BlockPos pos = p.blockPosition();
		return "Light: " + level.getBrightness(LightLayer.BLOCK, pos) + " (sky " + level.getBrightness(LightLayer.SKY, pos) + ")";
	}

	private static String target() {
		HitResult hr = mc().hitResult;
		if (hr instanceof BlockHitResult b && hr.getType() == HitResult.Type.BLOCK)
			return "Looking at: " + mc().level.getBlockState(b.getBlockPos()).getBlock().getName().getString();
		if (hr instanceof EntityHitResult e) return "Looking at: " + e.getEntity().getName().getString();
		return "Looking at: --";
	}

	private static String gameTime() {
		long t = Compat.dayTime(mc().level);
		long day = t / 24000, tod = t % 24000;
		long hour = (tod / 1000 + 6) % 24, minute = tod % 1000 * 60 / 1000;
		return String.format(Locale.ROOT, "Day %d, %02d:%02d", day, hour, minute);
	}

	private static String server() {
		Minecraft mc = mc();
		if (mc.isLocalServer()) return "Singleplayer";
		ServerData sd = mc.getCurrentServer();
		return sd == null ? "Server: --" : "Server: " + sd.ip;
	}

	private static String session() {
		long min = joinedAt == 0 ? 0 : (System.currentTimeMillis() - joinedAt) / 60000;
		return min < 60 ? "Session: " + min + "m" : "Session: " + min / 60 + "h " + min % 60 + "m";
	}

	private static String hunger() {
		FoodData f = player().getFoodData();
		return String.format(Locale.ROOT, "Food: %d/20  Sat: %.1f", f.getFoodLevel(), f.getSaturationLevel());
	}

	private static String health() {
		LocalPlayer p = player();
		return String.format(Locale.ROOT, "HP: %.1f/%.0f  Armor: %d", p.getHealth(), p.getMaxHealth(), p.getArmorValue());
	}

	// ---- per-tick state (speed ring, session start) ----

	private static final double[] SPEED = new double[5];
	private static int tick;
	private static long joinedAt;

	private static double speed() {
		double sum = 0;
		for (double d : SPEED) sum += d;
		return sum / SPEED.length * 20;
	}

	/** ClientPlayConnectionEvents.JOIN. */
	public static void onJoin() { joinedAt = System.currentTimeMillis(); }

	/** END_CLIENT_TICK: samples speed and refreshes the cached content of the widgets that are shown. */
	public static void tick(Minecraft mc) {
		tick++;
		LocalPlayer p = mc.player;
		if (p != null) {
			double dx = p.getX() - p.xo, dz = p.getZ() - p.zo;
			SPEED[tick % SPEED.length] = Math.sqrt(dx * dx + dz * dz);
		}
		HudConfig cfg = HudConfig.get();
		boolean editor = Compat.screen(mc) instanceof HudMenuScreen; // the menu previews every widget, enabled or not
		Font font = mc.font;
		for (int i = 0; i < ALL.length; i++) {
			Widget wd = ALL[i];
			if (tick % wd.every != 0) continue;
			if (!editor && !cfg.widgets.get(IDS[i]).enabled) continue;
			if (wd.refresh(font)) layoutDirty = true;
			wd.fresh = true;
		}
	}

	// ---- layout (screen pixels; each widget is drawn at its own scale) ----

	/** A widget placed in screen pixels: content box {@code x,y,w,h} (already multiplied by its scale {@code s}). */
	record Placed(String id, Widget widget, int x, int y, int w, int h, float s, int argb) {
		int pad() { return Math.round(PAD * s); }
		boolean contains(double px, double py) {
			return px >= x - pad() && px < x + w + pad() && py >= y - pad() && py < y + h + pad();
		}
	}

	/** Widgets sharing one background box: an anchor stack, or a single free-positioned widget. */
	record Group(Placed[] items, int x, int y, int w, int h, int pad, boolean background) {}

	static float scale(HudConfig cfg) { return HudConfig.clampScale(cfg.scale); }

	/** Content width in the editor: disabled text widgets carry the "(off)" suffix. */
	static int width(Widget wd, Font font, boolean off) {
		return wd.w + (off && wd instanceof Text ? font.width(OFF) : 0);
	}

	private static Placed measure(HudConfig cfg, Font font, String id) {
		HudConfig.Widget c = cfg.widgets.get(id);
		Widget wd = WIDGETS.get(id);
		if (!wd.fresh) { wd.refresh(font); wd.fresh = true; }
		float s = cfg.scale(c);
		return new Placed(id, wd, 0, 0, Math.round(width(wd, font, !c.enabled) * s), Math.round(wd.h * s), s, cfg.argb(c));
	}

	/**
	 * @param w,h    screen size in pixels
	 * @param alsoId a disabled widget to include anyway (the one selected in the menu), or null
	 */
	static Group[] layout(HudConfig cfg, Font font, int w, int h, String alsoId) {
		List<Group> groups = new ArrayList<>();
		Map<String, List<String>> stacks = new LinkedHashMap<>();
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
			Placed p = new Placed(m.id, m.widget, c.x < 0.5 ? px : px - m.w, c.y < 0.5 ? py : py - m.h, m.w, m.h, m.s, m.argb);
			groups.add(new Group(new Placed[]{p}, p.x, p.y, p.w, p.h, p.pad(), cfg.background(c)));
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
			Placed[] items = new Placed[sizes.size()];
			for (int i = 0; i < items.length; i++) {
				Placed m = sizes.get(i);
				items[i] = new Placed(m.id, m.widget, right ? x + gw - m.w : x, y, m.w, m.h, m.s, m.argb);
				y += m.h + GAP;
			}
			groups.add(new Group(items, x, items[0].y, gw, gh, gpad, bg));
		}
		return groups.toArray(new Group[0]);
	}

	static void drawGroupBackground(Draw g, Group grp, HudConfig cfg) {
		if (grp.background) g.fill(grp.x - grp.pad, grp.y - grp.pad, grp.x + grp.w + grp.pad, grp.y + grp.h + grp.pad, cfg.backgroundArgb());
	}

	static void drawWidget(Draw g, Font font, Placed p, HudConfig cfg, boolean off) {
		int argb = off ? (p.argb & 0xFFFFFF) | 0x66000000 : p.argb;
		g.pose().pushMatrix();
		g.pose().translate(p.x, p.y);
		g.pose().scale(p.s, p.s);
		p.widget.draw(g, font, cfg, argb, off ? OFF : "");
		g.pose().popMatrix();
	}

	// ---- HUD render path: cached layout, no allocations ----

	private static Group[] cached;
	private static int cachedRev = -1, cachedW, cachedH;
	private static boolean layoutDirty;

	public static void render(Draw g) {
		Minecraft mc = mc();
		HudConfig cfg = HudConfig.get();
		if (!cfg.enabled || mc.player == null || mc.level == null || Compat.hudHidden(mc)) return;
		Font font = mc.font;
		int w = g.width(), h = g.height();
		if (cached == null || layoutDirty || cachedRev != HudConfig.revision || cachedW != w || cachedH != h) {
			cached = layout(cfg, font, w, h, null);
			cachedRev = HudConfig.revision;
			cachedW = w;
			cachedH = h;
			layoutDirty = false;
		}
		Group[] groups = cached;
		for (int i = 0; i < groups.length; i++) {
			Group grp = groups[i];
			drawGroupBackground(g, grp, cfg);
			Placed[] items = grp.items;
			for (int j = 0; j < items.length; j++) drawWidget(g, font, items[j], cfg, false);
		}
	}
}
