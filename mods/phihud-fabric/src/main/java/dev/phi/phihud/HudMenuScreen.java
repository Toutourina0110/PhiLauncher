package dev.phi.phihud;

import dev.phi.phihud.HudRenderer.Group;
import dev.phi.phihud.HudRenderer.Placed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * The Phi HUD menu, in the launcher's "Blocky" style: bevelled panels, inventory-style tabs, and a live
 * preview of the overlay that the selected widget can be dragged around in (snapping to edges / centre).
 * <p>
 * Everything except the text fields and the module list is drawn and hit-tested here rather than built from
 * vanilla widgets, so the look does not change between Minecraft versions and no per-version render hook is
 * needed: {@code Compat.newEditor} only has to call {@link #drawBackground} and supply the list / row
 * subclasses. Every change is written to config/phihud.json immediately; Escape or Done closes.
 */
public abstract class HudMenuScreen extends Screen {
    // Blocky palette (launcher/resources/themes/blocky/theme.json)
    private static final int PANEL = 0xFF2B2B2B, SUNKEN = 0xFF1E1E1E, BUTTON = 0xFF6F6F6F, LIGHT = 0xFFB4B4B4,
        DARK = 0xFF373737, SHADOW = 0xFF101010, GREEN = 0xFF3A8F3A, LINK = 0xFF55FF55, ACCENT = 0xFFB388FF,
        TEXT = 0xFFF0F0F0, MUTED = 0xFF9A9A9A, OFF = 0xFF5A5A5A;

    static final int SNAP = 4, ROW = 14, BTN_H = 18, GAP = 4, PAD = 6;
    private static final String[] TABS = { "Modules", "Layout", "Global", "Presets" };
    private static final int T_MODULES = 0, T_LAYOUT = 1, T_GLOBAL = 2, T_PRESETS = 3;

    private static final Map<String, String> NAMES = Map.ofEntries(
        Map.entry("fps", "FPS"), Map.entry("tps", "TPS"), Map.entry("coords", "Coordinates"), Map.entry("direction", "Direction"),
        Map.entry("ping", "Ping"), Map.entry("memory", "Memory"), Map.entry("clock", "Clock"), Map.entry("armor", "Armor"),
        Map.entry("inventory", "Inventory"), Map.entry("keystrokes", "Keystrokes"), Map.entry("cps", "CPS"), Map.entry("speed", "Speed"),
        Map.entry("biome", "Biome"), Map.entry("gametime", "Game time"), Map.entry("light", "Light"), Map.entry("target", "Target"),
        Map.entry("server", "Server"), Map.entry("session", "Session"), Map.entry("effects", "Effects"), Map.entry("hunger", "Hunger"),
        Map.entry("health", "Health"));

    /** Ready-made sets of enabled modules; positions are left alone so a preset never undoes a layout. */
    private static final Map<String, List<String>> PRESETS = new LinkedHashMap<>();
    static {
        PRESETS.put("Minimal", List.of("fps", "coords"));
        PRESETS.put("Performance", List.of("fps", "tps", "ping", "memory"));
        PRESETS.put("PvP", List.of("fps", "cps", "keystrokes", "ping", "armor", "health", "hunger"));
        PRESETS.put("Explorer", List.of("coords", "direction", "biome", "light", "clock", "speed"));
        PRESETS.put("Everything", List.copyOf(HudConfig.DEFAULT_WIDGETS.keySet()));
        PRESETS.put("Nothing", List.of());
    }

    private final Screen parent;
    private int tab = T_MODULES;
    private String selected;
    private String search = "";
    private String dragId;         // widget being dragged in the preview
    private double dragDx, dragDy; // mouse - box corner, in screen px
    private Slider dragSlider;
    private boolean updating;      // suppress EditBox responders while refresh() sets values

    private WidgetList list;
    private EditBox searchBox, colorBox, xBox, yBox, globalColorBox;
    private final List<Button> buttons = new ArrayList<>();
    private final List<Slider> sliders = new ArrayList<>();

    protected HudMenuScreen(Screen parent) {
        super(Component.literal("Phi HUD"));
        this.parent = parent;
    }

    static HudConfig cfg() { return HudConfig.get(); }
    static String name(String id) { return NAMES.getOrDefault(id, id); }

    // ---- version hooks (list render signatures differ per Minecraft version) ----
    protected abstract WidgetList newList(int width, int height, int y);
    protected abstract Row newRow(String id);

    // ---- geometry ----

    /** Panel and column boxes, recomputed from the screen size on every use (all values are GUI pixels). */
    private final class Box {
        final int px = 6, py = 6 + BTN_H, pw = width - 12, ph = height - 12 - BTN_H;
        final int headerY = py + PAD;
        final int contentY = headerY + font.lineHeight + 8;
        final int barY = py + ph - PAD - BTN_H;
        final int contentBottom = barY - 6;
        final int leftW = Math.max(120, Math.min(200, (pw - 3 * PAD) * 28 / 100));
        final int rightW = Math.max(130, Math.min(215, (pw - 3 * PAD) * 32 / 100));
        final int leftX = px + PAD;
        final int rightX = px + pw - PAD - rightW;
        final int centerX = leftX + leftW + PAD;
        final int centerW = rightX - PAD - centerX;
    }

    private Box box() { return new Box(); }

    // ---- setup ----

    @Override
    protected void init() {
        Box b = box();
        buttons.clear();
        sliders.clear();

        searchBox = addRenderableWidget(new EditBox(font, b.leftX, b.contentY + font.lineHeight + 3, b.leftW, 14, Component.literal("Search")));
        searchBox.setHint(Component.literal("Search..."));
        searchBox.setValue(search);
        searchBox.setResponder(s -> {
            search = s;
            fillList();
        });

        int listY = b.contentY + font.lineHeight + 3 + 18;
        list = addRenderableWidget(newList(b.leftW, Math.max(ROW, b.contentBottom - listY), listY));
        list.setX(b.leftX);
        fillList();

        int y = b.contentY + font.lineHeight + 3;
        colorBox = field(b.rightX, y + 20, b.rightW, s -> {
            if (updating || selected == null)
                return;
            widget().color = s.isBlank() ? null : HudConfig.hex(s);
            if (s.isBlank() || HudConfig.hex(s) != null)
                cfg().save();
        });
        xBox = field(b.rightX, y + 82, b.rightW / 2 - 2, s -> position(s, true));
        yBox = field(b.rightX + b.rightW / 2 + 2, y + 82, b.rightW - b.rightW / 2 - 2, s -> position(s, false));

        globalColorBox = field(b.leftX, b.contentY + 3 * (BTN_H + GAP) + font.lineHeight + 3, 90, s -> {
            if (updating)
                return;
            String hex = HudConfig.hex(s);
            if (hex != null) {
                cfg().color = hex;
                cfg().save();
            }
        });

        refresh();
    }

    private EditBox field(int x, int y, int w, java.util.function.Consumer<String> onEdit) {
        EditBox edit = addRenderableWidget(new EditBox(font, x, y, w, 14, Component.literal("")));
        edit.setResponder(onEdit::accept);
        return edit;
    }

    private void position(String s, boolean horizontal) {
        if (updating || selected == null)
            return;
        try {
            double v = Math.max(0, Math.min(1, Double.parseDouble(s.trim())));
            HudConfig.Widget w = widget();
            if (horizontal)
                w.x = v;
            else
                w.y = v;
            if (w.x == null)
                w.x = 0.0;
            if (w.y == null)
                w.y = 0.0;
            cfg().save();
        } catch (NumberFormatException ignored) {
            // half-typed numbers are normal while editing; the field is re-read on refresh
        }
    }

    private HudConfig.Widget widget() { return cfg().widgets.get(selected); }

    private void fillList() {
        List<Row> rows = new ArrayList<>();
        String needle = search.trim().toLowerCase(Locale.ROOT);
        for (String id : cfg().widgets.keySet())
            if (name(id).toLowerCase(Locale.ROOT).contains(needle))
                rows.add(newRow(id));
        list.replaceEntries(rows);
        list.setSelected(rows.stream().filter(r -> r.id.equals(selected)).findFirst().orElse(null));
    }

    void select(String id) {
        selected = id;
        list.setSelected(list.children().stream().filter(r -> r.id.equals(id)).findFirst().orElse(null));
        refresh();
    }

    /** Shows the widgets that belong to the current tab and reloads the selected module's values into them. */
    private void refresh() {
        boolean modules = tab == T_MODULES;
        searchBox.visible = modules;
        list.visible = modules;
        boolean showWidget = modules && selected != null;
        colorBox.visible = xBox.visible = yBox.visible = showWidget;
        globalColorBox.visible = tab == T_GLOBAL;

        updating = true;
        if (showWidget) {
            HudConfig.Widget w = widget();
            colorBox.setValue(w.color == null ? "" : w.color);
            xBox.setValue(w.x == null ? "" : String.format(Locale.ROOT, "%.3f", w.x));
            yBox.setValue(w.y == null ? "" : String.format(Locale.ROOT, "%.3f", w.y));
        }
        globalColorBox.setValue(cfg().color);
        updating = false;
    }

    @Override
    public void onClose() {
        cfg().save();
        Compat.setScreen(minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ---- immediate-mode controls ----

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }
    }

    /** A bevelled button: {@code accent} tints it (0 = plain, otherwise the fill colour). */
    private record Button(Rect r, String label, int accent, Runnable onClick) {}

    /** A bevelled slider over [min, max] in steps of {@code step}. */
    private record Slider(Rect r, String label, double min, double max, double step, DoubleSupplier get, DoubleConsumer set) {
        double value(double mouseX) {
            double t = Math.max(0, Math.min(1, (mouseX - r.x - 3) / (r.w - 6.0)));
            return Math.round((min + t * (max - min)) / step) * step;
        }

        double fraction() { return Math.max(0, Math.min(1, (get.getAsDouble() - min) / (max - min))); }
    }

    private void button(Rect r, String label, int accent, Runnable onClick) { buttons.add(new Button(r, label, accent, onClick)); }

    private void slider(Rect r, String label, double min, double max, double step, DoubleSupplier get, DoubleConsumer set) {
        sliders.add(new Slider(r, label, min, max, step, get, set));
    }

    /** Rebuilds the control list for the current tab; called before drawing and before hit-testing. */
    private void buildControls() {
        buttons.clear();
        sliders.clear();
        Box b = box();
        HudConfig cfg = cfg();

        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            int w = Math.max(56, Math.min(100, (width - 12) / 5));
            button(new Rect(6 + i * (w + 2), 6, w, BTN_H), TABS[i], 0, () -> {
                tab = index;
                refresh();
            });
        }

        button(new Rect(b.px + PAD, b.barY, 92, BTN_H), "Overlay: " + (cfg.enabled ? "ON" : "OFF"), cfg.enabled ? GREEN : OFF, () -> {
            cfg().enabled = !cfg().enabled;
            cfg().save();
        });
        button(new Rect(b.px + b.pw - PAD - 60, b.barY, 60, BTN_H), "Done", ACCENT, this::onClose);
        button(new Rect(b.px + b.pw - PAD - 60 - GAP - 92, b.barY, 92, BTN_H), "Reset layout", 0, () -> {
            cfg().widgets.values().forEach(w -> w.x = w.y = null);
            cfg().save();
        });

        if (tab == T_MODULES && selected != null)
            buildWidgetControls(b);
        else if (tab == T_GLOBAL)
            buildGlobalControls(b);
        else if (tab == T_PRESETS)
            buildPresetControls(b);
    }

    private void buildWidgetControls(Box b) {
        HudConfig.Widget w = widget();
        int y = b.contentY + font.lineHeight + 3;
        button(new Rect(b.rightX, y, b.rightW, BTN_H), "Module: " + (w.enabled ? "ON" : "OFF"), w.enabled ? GREEN : OFF, () -> {
            widget().enabled = !widget().enabled;
            cfg().save();
        });
        // colorBox sits at y + 20
        slider(new Rect(b.rightX, y + 40, b.rightW, BTN_H), "Scale", 0.5, 3, 0.05, () -> cfg().scale(w), v -> {
            widget().scale = v;
            cfg().save();
        });
        button(new Rect(b.rightX, y + 60, b.rightW, BTN_H),
            "Background: " + (w.background == null ? "Inherit" : w.background ? "On" : "Off"), 0, () -> {
                HudConfig.Widget wd = widget();
                wd.background = wd.background == null ? Boolean.TRUE : wd.background ? Boolean.FALSE : null;
                cfg().save();
            });
        // xBox / yBox sit at y + 82
        button(new Rect(b.rightX, y + 102, b.rightW, BTN_H), "Reset this module", 0, () -> {
            HudConfig.Widget wd = widget();
            wd.x = wd.y = null;
            wd.scale = null;
            wd.color = null;
            wd.background = null;
            cfg().save();
            refresh();
        });
    }

    private void buildGlobalControls(Box b) {
        HudConfig cfg = cfg();
        int x = b.leftX, w = Math.min(220, b.pw / 2 - PAD), y = b.contentY;
        button(new Rect(x, y, w, BTN_H), "Overlay: " + (cfg.enabled ? "ON" : "OFF"), cfg.enabled ? GREEN : OFF, () -> {
            cfg().enabled = !cfg().enabled;
            cfg().save();
        });
        button(new Rect(x, y + BTN_H + GAP, w, BTN_H), "Text shadow: " + (cfg.shadow ? "ON" : "OFF"), cfg.shadow ? GREEN : OFF, () -> {
            cfg().shadow = !cfg().shadow;
            cfg().save();
        });
        button(new Rect(x, y + 2 * (BTN_H + GAP), w, BTN_H), "Background: " + (cfg.background ? "ON" : "OFF"), cfg.background ? GREEN : OFF,
            () -> {
                cfg().background = !cfg().background;
                cfg().save();
            });
        // globalColorBox sits under the third button
        int right = x + w + PAD * 2, rw = Math.min(220, b.px + b.pw - PAD - right);
        slider(new Rect(right, y, rw, BTN_H), "Scale", 0.5, 3, 0.05, () -> cfg().scale, v -> {
            cfg().scale = v;
            cfg().save();
        });
        slider(new Rect(right, y + BTN_H + GAP, rw, BTN_H), "Background opacity", 0, 1, 0.05, () -> cfg().backgroundOpacity, v -> {
            cfg().backgroundOpacity = v;
            cfg().save();
        });
        slider(new Rect(right, y + 2 * (BTN_H + GAP), rw, BTN_H), "Screen margin", 0, 32, 1, () -> cfg().margin, v -> {
            cfg().margin = (int) v;
            cfg().save();
        });
    }

    private void buildPresetControls(Box b) {
        int i = 0, w = Math.min(160, b.pw / 3 - PAD);
        for (Map.Entry<String, List<String>> preset : PRESETS.entrySet()) {
            final List<String> ids = preset.getValue();
            button(new Rect(b.leftX + (i % 3) * (w + PAD), b.contentY + (i / 3) * (BTN_H + GAP + 10), w, BTN_H), preset.getKey(), 0, () -> {
                cfg().widgets.forEach((id, w2) -> w2.enabled = ids.contains(id));
                cfg().save();
            });
            i++;
        }
    }

    // ---- drawing ----

    /** Fill plus a 2 px bevel: light on the top/left, dark on the bottom/right (or the reverse when sunken). */
    private static void bevel(Draw g, Rect r, int fill, boolean sunken) {
        int top = sunken ? SHADOW : LIGHT, bottom = sunken ? LIGHT : SHADOW;
        g.fill(r.x, r.y, r.x + r.w, r.y + r.h, fill);
        g.fill(r.x, r.y, r.x + r.w, r.y + 2, top);
        g.fill(r.x, r.y, r.x + 2, r.y + r.h, top);
        g.fill(r.x, r.y + r.h - 2, r.x + r.w, r.y + r.h, bottom);
        g.fill(r.x + r.w - 2, r.y, r.x + r.w, r.y + r.h, bottom);
    }

    private void text(Draw g, String s, int x, int y, int argb) { g.text(font, Ui.text(s), x, y, argb, true); }

    private void centered(Draw g, String s, Rect r, int argb) {
        text(g, s, r.x + (r.w - Ui.width(font, s)) / 2, r.y + (r.h - font.lineHeight) / 2 + 1, argb);
    }

    /** Draws the whole menu; called from the version-specific background hook, before the text fields render. */
    protected void drawBackground(Draw g, int mouseX, int mouseY) {
        buildControls();
        Box b = box();
        HudConfig cfg = cfg();

        drawTabs(g, mouseX, mouseY);
        bevel(g, new Rect(b.px, b.py, b.pw, b.ph), PANEL, false);

        text(g, "PHI HUD", b.px + PAD, b.headerY, ACCENT);
        String saved = "config/phihud.json";
        text(g, saved, b.px + b.pw - PAD - Ui.width(font, saved), b.headerY, MUTED);

        switch (tab) {
            case T_MODULES -> drawModulesTab(g, b, cfg, mouseX, mouseY);
            case T_LAYOUT -> drawLayoutTab(g, b, cfg);
            case T_GLOBAL -> drawGlobalTab(g, b);
            default -> drawPresetsTab(g, b);
        }

        for (Button button : buttons)
            if (button.r.y >= b.barY)
                drawButton(g, button, mouseX, mouseY);
    }

    private void drawTabs(Draw g, int mouseX, int mouseY) {
        for (int i = 0; i < TABS.length; i++) {
            Button t = buttons.get(i);
            boolean active = i == tab;
            Rect r = active ? new Rect(t.r.x, t.r.y, t.r.w, t.r.h + 4) : t.r;
            bevel(g, r, active ? PANEL : DARK, false);
            if (active)
                g.fill(r.x + 4, r.y + 5, r.x + 9, r.y + 10, ACCENT);
            centered(g, t.label, new Rect(active ? r.x + 8 : r.x, r.y, r.w, BTN_H),
                active ? TEXT : t.r.contains(mouseX, mouseY) ? LINK : MUTED);
        }
    }

    private void drawButton(Draw g, Button button, int mouseX, int mouseY) {
        boolean hovered = button.r.contains(mouseX, mouseY);
        int fill = button.accent != 0 ? button.accent : hovered ? LIGHT : BUTTON;
        bevel(g, button.r, fill, false);
        centered(g, button.label, button.r, button.accent == ACCENT ? SHADOW : TEXT);
    }

    private void drawSlider(Draw g, Slider s, int mouseX, int mouseY) {
        bevel(g, s.r, SUNKEN, true);
        int knobX = s.r.x + 3 + (int) ((s.r.w - 6 - 8) * s.fraction());
        bevel(g, new Rect(knobX, s.r.y + 2, 8, s.r.h - 4), s.r.contains(mouseX, mouseY) || dragSlider == s ? LIGHT : BUTTON, false);
        String value = s.step >= 1 ? String.valueOf((int) s.get.getAsDouble()) : String.format(Locale.ROOT, "%.2f", s.get.getAsDouble());
        centered(g, s.label + ": " + value, s.r, TEXT);
    }

    private void drawModulesTab(Draw g, Box b, HudConfig cfg, int mouseX, int mouseY) {
        long on = cfg.widgets.values().stream().filter(w -> w.enabled).count();
        text(g, "Modules", b.leftX, b.contentY, MUTED);
        String count = on + " / " + cfg.widgets.size() + " on";
        text(g, count, b.leftX + b.leftW - Ui.width(font, count), b.contentY, MUTED);
        bevel(g, new Rect(b.leftX - 2, b.contentY + font.lineHeight + 20, b.leftW + 4, b.contentBottom - b.contentY - font.lineHeight - 20),
            SUNKEN, true);

        text(g, "Layout preview", b.centerX, b.contentY, MUTED);
        String hint = "drag to move";
        text(g, hint, b.centerX + b.centerW - Ui.width(font, hint), b.contentY, MUTED);
        int previewTop = b.contentY + font.lineHeight + 3;
        drawPreview(g, new Rect(b.centerX, previewTop, b.centerW, Math.min(previewHeight(b.centerW), b.contentBottom - previewTop)), cfg);

        if (selected == null) {
            text(g, "Select a module", b.rightX, b.contentY, MUTED);
            text(g, "Pick one on the left to", b.rightX, b.contentY + font.lineHeight + 8, MUTED);
            text(g, "change its scale, colour", b.rightX, b.contentY + 2 * (font.lineHeight + 2) + 6, MUTED);
            text(g, "and position.", b.rightX, b.contentY + 3 * (font.lineHeight + 2) + 4, MUTED);
            return;
        }
        text(g, name(selected), b.rightX, b.contentY, TEXT);
        String tag = "selected";
        text(g, tag, b.rightX + b.rightW - Ui.width(font, tag), b.contentY, MUTED);
        int y = b.contentY + font.lineHeight + 3;
        text(g, "Text colour (blank = inherit)", b.rightX, y + 20 - font.lineHeight - 2, MUTED);
        text(g, "Position (fraction of screen)", b.rightX, y + 82 - font.lineHeight - 2, MUTED);
        for (Button button : buttons)
            if (button.r.x >= b.rightX && button.r.y < b.barY)
                drawButton(g, button, mouseX, mouseY);
        for (Slider s : sliders)
            drawSlider(g, s, mouseX, mouseY);
    }

    private void drawLayoutTab(Draw g, Box b, HudConfig cfg) {
        text(g, "Drag a widget to move it. It snaps to the screen edges and centre within " + SNAP + " px.", b.leftX, b.contentY, MUTED);
        int w = b.pw - 2 * PAD;
        drawPreview(g, new Rect(b.leftX, b.contentY + font.lineHeight + 4, w, Math.min(previewHeight(w), b.contentBottom - b.contentY - 16)),
            cfg);
    }

    private void drawGlobalTab(Draw g, Box b) {
        text(g, "Defaults for every module. A module can override them in the Modules tab.", b.leftX, b.contentY - font.lineHeight - 4,
            MUTED);
        text(g, "Text colour", b.leftX, b.contentY + 3 * (BTN_H + GAP) - 2, MUTED);
        for (Button button : buttons)
            if (button.r.y < b.barY && button.r.y > b.py)
                drawButton(g, button, -1, -1);
        for (Slider s : sliders)
            drawSlider(g, s, -1, -1);
    }

    private void drawPresetsTab(Draw g, Box b) {
        text(g, "A preset only switches modules on and off; positions and colours are kept.", b.leftX,
            b.contentY - font.lineHeight - 4, MUTED);
        int i = 0;
        for (Map.Entry<String, List<String>> preset : PRESETS.entrySet()) {
            Button button = buttons.get(buttons.size() - PRESETS.size() + i);
            drawButton(g, button, -1, -1);
            String modules = preset.getValue().isEmpty() ? "no module"
                : preset.getValue().size() + (preset.getValue().size() == 1 ? " module" : " modules");
            text(g, modules, button.r.x, button.r.y + BTN_H + 2, MUTED);
            i++;
        }
    }

    // ---- preview ----

    private Rect preview = new Rect(0, 0, 1, 1); // last drawn frame, used to map clicks back to screen pixels

    private int previewHeight(int w) { return Math.max(1, w * height / Math.max(1, width)); }

    private float previewScale() { return preview.w / (float) Math.max(1, width); }

    private Group[] groups() { return HudRenderer.layout(cfg(), font, width, height, selected); }

    private Placed find(double sx, double sy) {
        Group[] groups = groups();
        for (int i = groups.length - 1; i >= 0; i--) // topmost (drawn last) wins
            for (Placed p : groups[i].items())
                if (p.contains(sx, sy))
                    return p;
        return null;
    }

    private void drawPreview(Draw g, Rect r, HudConfig cfg) {
        preview = r;
        bevel(g, new Rect(r.x - 2, r.y - 2, r.w + 4, r.h + 4), SUNKEN, true);
        float f = previewScale();
        g.pose().pushMatrix();
        g.pose().translate(r.x, r.y);
        g.pose().scale(f, f);
        for (Group grp : groups()) {
            HudRenderer.drawGroupBackground(g, grp, cfg);
            for (Placed p : grp.items()) {
                HudRenderer.drawWidget(g, font, p, cfg, !cfg.widgets.get(p.id()).enabled);
                if (p.id().equals(selected)) {
                    int pad = p.pad(), x1 = p.x() - pad, y1 = p.y() - pad, x2 = p.x() + p.w() + pad, y2 = p.y() + p.h() + pad;
                    g.fill(x1, y1, x2, y1 + 1, ACCENT);
                    g.fill(x1, y2 - 1, x2, y2, ACCENT);
                    g.fill(x1, y1, x1 + 1, y2, ACCENT);
                    g.fill(x2 - 1, y1, x2, y2, ACCENT);
                }
            }
        }
        g.pose().popMatrix();
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (super.mouseClicked(e, doubleClick))
            return true;
        if (e.button() != 0)
            return false;
        buildControls();
        for (Button button : buttons)
            if (button.r.contains(e.x(), e.y())) {
                button.onClick.run();
                return true;
            }
        for (Slider s : sliders)
            if (s.r.contains(e.x(), e.y())) {
                dragSlider = s;
                s.set.accept(s.value(e.x()));
                return true;
            }
        if (tab != T_MODULES && tab != T_LAYOUT)
            return false;
        if (!preview.contains(e.x(), e.y()))
            return false;
        double sx = (e.x() - preview.x) / previewScale(), sy = (e.y() - preview.y) / previewScale();
        Placed p = find(sx, sy);
        if (p == null)
            return false;
        select(p.id());
        dragId = p.id();
        dragDx = sx - p.x();
        dragDy = sy - p.y();
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragSlider != null) {
            dragSlider.set.accept(dragSlider.value(e.x()));
            return true;
        }
        if (dragId == null)
            return super.mouseDragged(e, dx, dy);
        HudConfig cfg = cfg();
        HudConfig.Widget w = cfg.widgets.get(dragId);
        Placed p = null;
        for (Group grp : groups())
            for (Placed q : grp.items())
                if (q.id().equals(dragId))
                    p = q;
        if (p == null)
            return true;
        double sx = (e.x() - preview.x) / previewScale(), sy = (e.y() - preview.y) / previewScale();
        int edge = Math.round(cfg.margin * HudRenderer.scale(cfg)) + p.pad();
        int x = snap(sx - dragDx, edge, (width - p.w()) / 2, width - edge - p.w());
        int y = snap(sy - dragDy, edge, (height - p.h()) / 2, height - edge - p.h());
        x = Math.max(0, Math.min(width - p.w(), x));
        y = Math.max(0, Math.min(height - p.h(), y));
        // pivot: the corner nearest the box centre, so the fraction stays on the same side of 0.5
        w.x = x + p.w() / 2.0 < width / 2.0 ? x / (double) width : (x + p.w()) / (double) width;
        w.y = y + p.h() / 2.0 < height / 2.0 ? y / (double) height : (y + p.h()) / (double) height;
        return true;
    }

    private static int snap(double v, int... targets) {
        for (int t : targets)
            if (Math.abs(v - t) <= SNAP)
                return t;
        return (int) Math.round(v);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (dragId != null || dragSlider != null) {
            cfg().save(); // one write per drag, not per mouse event
            if (dragId != null)
                refresh();
        }
        dragId = null;
        dragSlider = null;
        return super.mouseReleased(e);
    }

    // ---- vanilla widget subclasses ----

    /** The module list; the version subclass blanks the vanilla list background so the panel shows through. */
    protected abstract static class WidgetList extends ObjectSelectionList<Row> {
        protected WidgetList(Minecraft mc, int width, int height, int y) { super(mc, width, height, y, ROW); }

        @Override public int getRowWidth() { return getWidth() - 10; }
        @Override protected int scrollBarX() { return getRight() - 5; }
        @Override public boolean isMouseOver(double x, double y) { return visible && super.isMouseOver(x, y); }
        @Override public boolean mouseClicked(MouseButtonEvent e, boolean d) { return visible && super.mouseClicked(e, d); }
        @Override public boolean mouseScrolled(double x, double y, double dx, double dy) { return visible && super.mouseScrolled(x, y, dx, dy); }
    }

    /** One module row: status square, name and an ON/OFF pill. Clicking the pill toggles, clicking elsewhere selects. */
    protected abstract class Row extends ObjectSelectionList.Entry<Row> {
        final String id;

        protected Row(String id) { this.id = id; }

        @Override public Component getNarration() { return Ui.text(name(id)); }

        @Override
        public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
            if (e.x() >= getX() + getWidth() - 28) {
                HudConfig.Widget w = cfg().widgets.get(id);
                w.enabled = !w.enabled;
                cfg().save();
            } else {
                select(id);
            }
            return true;
        }

        protected void draw(Draw g, int mouseX, int mouseY, boolean hovered) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            boolean on = cfg().widgets.get(id).enabled;
            if (id.equals(selected))
                g.fill(x, y, x + w, y + h, 0x60B388FF);
            else if (hovered)
                g.fill(x, y, x + w, y + h, 0x30FFFFFF);
            int dot = y + (h - 5) / 2;
            g.fill(x + 3, dot, x + 8, dot + 5, on ? GREEN : OFF);
            g.text(font, Ui.text(name(id)), x + 12, y + (h - font.lineHeight) / 2 + 1, on ? TEXT : MUTED, true);
            Rect pill = new Rect(x + w - 26, y + (h - 11) / 2, 24, 11);
            bevel(g, pill, on ? GREEN : OFF, false);
            String label = on ? "ON" : "OFF";
            g.text(font, Ui.text(label), pill.x + (pill.w - Ui.width(font, label)) / 2, pill.y + (pill.h - font.lineHeight) / 2 + 1, TEXT,
                false);
        }
    }
}
