package dev.phi.phihud;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** config/phihud.json — see PHIHUD_SPEC.md. Unknown keys/widgets are ignored, missing ones use defaults. */
public final class HudConfig {
	public static final class Widget {
		public Boolean enabled;
		public String anchor;
		public Integer order;

		Widget() {}

		Widget(boolean enabled, String anchor, int order) {
			this.enabled = enabled;
			this.anchor = anchor;
			this.order = order;
		}
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

	/** ARGB text color. */
	public int argb() {
		try {
			return 0xFF000000 | Integer.parseInt(color.trim().replace("#", ""), 16);
		} catch (RuntimeException e) {
			return 0xFFFFFFFF;
		}
	}

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
				widgets.put(id, def);
				return;
			}
			if (w.enabled == null) w.enabled = def.enabled;
			if (w.anchor == null) w.anchor = def.anchor;
			if (w.order == null) w.order = def.order;
		});
		return this;
	}

	// ---- loading + hot reload ----

	private static final Gson GSON = new Gson();
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
		long mtime;
		try {
			mtime = Files.getLastModifiedTime(FILE).toMillis();
		} catch (IOException e) {
			mtime = 0; // missing file = defaults
		}
		if (mtime == lastMtime) return current;
		lastMtime = mtime;
		current = mtime == 0 ? new HudConfig().fillDefaults() : load();
		return current;
	}

	private static HudConfig load() {
		try (Reader r = Files.newBufferedReader(FILE)) {
			HudConfig c = GSON.fromJson(r, HudConfig.class);
			return (c == null ? new HudConfig() : c).fillDefaults();
		} catch (IOException | RuntimeException e) {
			PhiHud.LOGGER.warn("[phihud] could not read {}: {}", FILE, e.toString());
			return new HudConfig().fillDefaults();
		}
	}
}
