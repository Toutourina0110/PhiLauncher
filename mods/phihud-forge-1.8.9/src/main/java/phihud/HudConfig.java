package phihud;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;

/** Mirror of config/phihud.json (see PHIHUD_SPEC.md). Gson fills fields; missing keys keep these defaults. */
public class HudConfig {
    public int version = 1;
    public boolean enabled = true;
    public float scale = 1f;
    public String color = "#FFFFFF";
    public boolean background = true;
    public float backgroundOpacity = 0.4f;
    public boolean shadow = true;
    public int margin = 4;
    public Map<String, Widget> widgets = defaultWidgets();

    public static class Widget {
        public boolean enabled = true;
        public String anchor = "top-left";
        public int order = 0;

        Widget() {}

        Widget(boolean enabled, String anchor, int order) {
            this.enabled = enabled;
            this.anchor = anchor;
            this.order = order;
        }
    }

    public static final String[] IDS = {"fps", "tps", "coords", "direction", "ping", "memory", "clock", "armor", "inventory"};

    static Map<String, Widget> defaultWidgets() {
        Map<String, Widget> m = new LinkedHashMap<String, Widget>();
        m.put("fps", new Widget(true, "top-left", 0));
        m.put("tps", new Widget(true, "top-left", 1));
        m.put("coords", new Widget(true, "top-left", 2));
        m.put("direction", new Widget(true, "top-left", 3));
        m.put("ping", new Widget(false, "top-right", 0));
        m.put("memory", new Widget(false, "top-right", 1));
        m.put("clock", new Widget(false, "top-right", 2));
        m.put("armor", new Widget(false, "bottom-left", 0));
        m.put("inventory", new Widget(false, "bottom-right", 0));
        return m;
    }

    /** Text color as ARGB with full alpha. */
    public int argb() {
        try {
            return 0xFF000000 | (Integer.parseInt(color.trim().replace("#", ""), 16) & 0xFFFFFF);
        } catch (RuntimeException e) {
            return 0xFFFFFFFF;
        }
    }

    /** Parses the file; a missing/unreadable file yields all defaults. Widgets absent from the file get their default. */
    public static HudConfig load(File f) {
        HudConfig c = null;
        if (f.isFile()) {
            Reader r = null;
            try {
                r = new FileReader(f);
                c = new Gson().fromJson(r, HudConfig.class);
            } catch (Exception e) {
                System.err.println("[phihud] bad config " + f + ": " + e);
            } finally {
                if (r != null) try { r.close(); } catch (Exception ignored) {}
            }
        }
        if (c == null) c = new HudConfig();
        if (c.widgets == null) c.widgets = new LinkedHashMap<String, Widget>();
        for (Map.Entry<String, Widget> d : defaultWidgets().entrySet()) {
            Widget w = c.widgets.get(d.getKey());
            if (w == null) c.widgets.put(d.getKey(), d.getValue());
            else if (w.anchor == null) w.anchor = d.getValue().anchor;
        }
        if (c.scale <= 0) c.scale = 1f;
        return c;
    }
}
