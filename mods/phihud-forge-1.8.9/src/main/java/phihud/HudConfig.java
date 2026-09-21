package phihud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
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

    /** The parsed file as-is; save() patches only the keys the mod owns so unknown keys survive. */
    public transient JsonObject raw = new JsonObject();

    public static class Widget {
        public boolean enabled = true;
        public String anchor = "top-left";
        public int order = 0;
        /** v2 free position (fractions of the scaled screen, pivot corner); null = anchor stacking. */
        public Double x, y;

        Widget() {}

        Widget(boolean enabled, String anchor, int order) {
            this.enabled = enabled;
            this.anchor = anchor;
            this.order = order;
        }

        public boolean free() { return x != null && y != null; }
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
        JsonObject raw = null;
        if (f.isFile()) {
            Reader r = null;
            try {
                r = new FileReader(f);
                JsonElement el = new JsonParser().parse(r);
                if (el.isJsonObject()) {
                    raw = el.getAsJsonObject();
                    c = new Gson().fromJson(raw, HudConfig.class);
                }
            } catch (Exception e) {
                System.err.println("[phihud] bad config " + f + ": " + e);
            } finally {
                if (r != null) try { r.close(); } catch (Exception ignored) {}
            }
        }
        if (c == null) c = new HudConfig();
        c.raw = raw != null ? raw : new JsonObject();
        if (c.widgets == null) c.widgets = new LinkedHashMap<String, Widget>();
        for (Map.Entry<String, Widget> d : defaultWidgets().entrySet()) {
            Widget w = c.widgets.get(d.getKey());
            if (w == null) c.widgets.put(d.getKey(), d.getValue());
            else if (w.anchor == null) w.anchor = d.getValue().anchor;
        }
        if (c.scale <= 0) c.scale = 1f;
        return c;
    }

    /** Read-modify-write: patches version, root enabled and each widget's enabled/x/y into the parsed file. */
    public void save(File f) {
        raw.addProperty("version", 2);
        raw.addProperty("enabled", enabled);
        JsonObject ws = obj(raw, "widgets");
        for (Map.Entry<String, Widget> e : widgets.entrySet()) {
            Widget w = e.getValue();
            boolean fresh = !ws.has(e.getKey()) || !ws.get(e.getKey()).isJsonObject();
            JsonObject o = obj(ws, e.getKey());
            if (fresh) {
                o.addProperty("anchor", w.anchor);
                o.addProperty("order", w.order);
            }
            o.addProperty("enabled", w.enabled);
            if (w.free()) { o.addProperty("x", w.x); o.addProperty("y", w.y); }
            else { o.remove("x"); o.remove("y"); }
        }
        Writer wr = null;
        try {
            f.getParentFile().mkdirs();
            wr = new FileWriter(f);
            new GsonBuilder().setPrettyPrinting().create().toJson(raw, wr);
        } catch (Exception ex) {
            System.err.println("[phihud] cannot write " + f + ": " + ex);
        } finally {
            if (wr != null) try { wr.close(); } catch (Exception ignored) {}
        }
    }

    /** Child object, created (replacing a non-object value) when needed. */
    private static JsonObject obj(JsonObject parent, String key) {
        JsonElement el = parent.get(key);
        if (el != null && el.isJsonObject()) return el.getAsJsonObject();
        JsonObject o = new JsonObject();
        parent.add(key, o);
        return o;
    }
}
