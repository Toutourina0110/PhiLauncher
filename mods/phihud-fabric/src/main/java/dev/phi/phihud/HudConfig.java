package dev.phi.phihud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * config/phihud.json — see PHIHUD_SPEC.md. Unknown keys/widgets are ignored, missing ones use defaults.
 * Writes are read-modify-write: the parsed file is kept in {@link #raw} and only the keys the menu edits are patched
 * (version, the root options, each widget's enabled / x / y / scale / color / background).
 */
public final class HudConfig {
	public static final class Widget {
		public Boolean enabled;
		public String anchor;
		public Integer order;
		/** v2 free position (fractions of the screen, pivot = nearest corner); null = anchor stacking. */
		public Double x, y;
		/** v3 per-widget overrides; null = inherit the root value. */
		public Double scale;
		public String color;
		public Boolean background;

		Widget() {}

		Widget(boolean enabled, String anchor, int order) {
			this.enabled = enabled;
			this.anchor = anchor;
			this.order = order;
		}

		public boolean free() { return x != null && y != null; }
	}

	public static final Map<String, Widget> DEFAULT_WIDGETS = new LinkedHashMap<>();
	static {
		DEFAULT_WIDGETS.put("fps", new Widget(true, "top-left", 0));
		DEFAULT_WIDGETS.put("tps", new Widget(true, "top-left", 1));
		DEFAULT_WIDGETS.put("coords", new Widget(true, "top-left", 2));
		DEFAULT_WIDGETS.put("direction", new Widget(true, "top-left", 3));
		DEFAULT_WIDGETS.put("ping", new Widget(false, "top-right", 0));
		DEFAULT_WIDGETS.put("memory", new Widget(false, "top-right", 1));
		DEFAULT_WIDGETS.put("clock", new Widget(false, "top-right", 2));
		DEFAULT_WIDGETS.put("armor", new Widget(false, "bottom-left", 0));
		DEFAULT_WIDGETS.put("inventory", new Widget(false, "bottom-right", 0));
	}

	public boolean enabled = true;
	public double scale = 1.0;
	public String color = "#FFFFFF";
	public boolean background = true;
	public double backgroundOpacity = 0.4;
	public boolean shadow = true;
	public int margin = 4;
	public Map<String, Widget> widgets = new LinkedHashMap<>();

	/** The file as parsed (null when there was no file); preserved on save so unknown keys survive. */
	private transient JsonObject raw;

	/** ARGB text color. */
	public int argb() { return argb(color); }

	static int argb(String hex) {
		try {
			return 0xFF000000 | Integer.parseInt(hex.trim().replace("#", ""), 16);
		} catch (RuntimeException e) {
			return 0xFFFFFFFF;
		}
	}

	/** "#RRGGBB" or null when the text is not a hex color. */
	static String hex(String text) {
		String t = text.trim().replace("#", "");
		return t.matches("[0-9a-fA-F]{6}") ? "#" + t.toUpperCase() : null;
	}

	public static float clampScale(double s) { return (float) Math.max(0.25, Math.min(4.0, s)); }

	// ---- per-widget values, inheriting the root when the widget has no override ----
	public float scale(Widget w) { return clampScale(w.scale != null ? w.scale : scale); }
	public int argb(Widget w) { return argb(w.color != null ? w.color : color); }
	public boolean background(Widget w) { return w.background != null ? w.background : background; }

	/** ARGB background color. */
	public int backgroundArgb() {
		int a = (int) Math.round(Math.max(0, Math.min(1, backgroundOpacity)) * 255);
		return a << 24;
	}

	private HudConfig fillDefaults() {
		if (widgets == null) widgets = new LinkedHashMap<>();
		DEFAULT_WIDGETS.forEach((id, def) -> {
			Widget w = widgets.get(id);
			if (w == null) {
				w = new Widget();
				widgets.put(id, w);
			}
			if (w.enabled == null) w.enabled = def.enabled;
			if (w.anchor == null) w.anchor = def.anchor;
			if (w.order == null) w.order = def.order;
			if (w.x == null || w.y == null) w.x = w.y = null; // half a position is no position
		});
		return this;
	}

	// ---- loading + hot reload ----

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("phihud.json");
	private static final long POLL_MS = 2000;

	private static HudConfig current = new HudConfig().fillDefaults();
	private static long lastPoll;
	private static long lastMtime = -1;

	/** Returns the current config, re-reading the file if its mtime changed (checked every 2 s). */
	public static HudConfig get() {
		long now = System.currentTimeMillis();
		if (now - lastPoll < POLL_MS) return current;
		lastPoll = now;
		long mtime = mtime();
		if (mtime == lastMtime) return current;
		lastMtime = mtime;
		current = mtime == 0 ? new HudConfig().fillDefaults() : load();
		return current;
	}

	private static long mtime() {
		try {
			return Files.getLastModifiedTime(FILE).toMillis();
		} catch (IOException e) {
			return 0; // missing file = defaults
		}
	}

	private static HudConfig load() {
		try (Reader r = Files.newBufferedReader(FILE)) {
			JsonObject raw = JsonParser.parseReader(r).getAsJsonObject();
			HudConfig c = GSON.fromJson(raw, HudConfig.class).fillDefaults();
			c.raw = raw;
			return c;
		} catch (IOException | RuntimeException e) {
			PhiHud.LOGGER.warn("[phihud] could not read {}: {}", FILE, e.toString());
			return new HudConfig().fillDefaults();
		}
	}

	/** Patches version 2, the root options and each widget's enabled/x/y/scale/color/background into the file, keeping everything else. */
	public void save() {
		JsonObject root = raw != null ? raw : GSON.toJsonTree(this).getAsJsonObject();
		root.addProperty("version", 2);
		root.addProperty("enabled", enabled);
		root.addProperty("scale", scale);
		root.addProperty("color", color);
		root.addProperty("background", background);
		root.addProperty("backgroundOpacity", backgroundOpacity);
		root.addProperty("shadow", shadow);
		root.addProperty("margin", margin);
		JsonObject ws = root.get("widgets") instanceof JsonObject o ? o : new JsonObject();
		root.add("widgets", ws);
		widgets.forEach((id, w) -> {
			JsonObject o = ws.get(id) instanceof JsonObject e ? e : new JsonObject();
			ws.add(id, o);
			o.addProperty("enabled", w.enabled);
			if (w.free()) {
				o.addProperty("x", w.x);
				o.addProperty("y", w.y);
			} else {
				o.remove("x");
				o.remove("y");
			}
			if (w.scale != null) o.addProperty("scale", w.scale); else o.remove("scale");
			if (w.color != null) o.addProperty("color", w.color); else o.remove("color");
			if (w.background != null) o.addProperty("background", w.background); else o.remove("background");
		});
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer out = Files.newBufferedWriter(FILE)) {
				GSON.toJson(root, out);
			}
			raw = root;
			lastMtime = mtime(); // our own write is not a change to re-read
		} catch (IOException e) {
			PhiHud.LOGGER.warn("[phihud] could not write {}: {}", FILE, e.toString());
		}
	}
}
