package phihud;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Phi HUD menu. Left panel: search, widget rows with ON/OFF pills, a Settings row, and the settings of the
 * selection. Right: live preview (region right of the panel) where the selected widget is outlined and draggable.
 */
public class EditorScreen extends GuiScreen {
    static final int PANEL = 140, ROW = 14, SNAP = 4, PILL_W = 22, PILL_H = 10;
    private static final String SETTINGS = "settings";
    private final PhiHud mod;
    private GuiTextField search;
    private String selected;
    private String dragId;
    private int dragOffX, dragOffY, scroll;
    private final List<Row> rows = new ArrayList<Row>();
    private final List<String> visible = new ArrayList<String>();

    /** One settings row: a stepper (dec/inc), a cycle (inc only), a pill (on/set) or a text field. */
    private static final class Row {
        final String label;
        Supplier<String> value;
        Runnable dec, inc;
        BooleanSupplier on;
        Consumer<Boolean> set;
        GuiTextField field;
        Consumer<String> apply;
        Row(String label) { this.label = label; }
    }

    EditorScreen(PhiHud mod) { this.mod = mod; }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.add(new GuiButton(0, 4, height - 22, 66, 20, "Reset layout"));
        buttonList.add(new GuiButton(1, 74, height - 22, PANEL - 78, 20, "Done"));
        String q = search == null ? "" : search.getText();
        search = new GuiTextField(0, fontRendererObj, 4, 4, PANEL - 8, 14);
        search.setMaxStringLength(20);
        search.setText(q);
        buildRows();
    }

    @Override
    public void onGuiClosed() { Keyboard.enableRepeatEvents(false); }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    @Override
    public void updateScreen() {
        search.updateCursorCounter();
        for (Row r : rows) if (r.field != null) r.field.updateCursorCounter();
    }

    // ---- rows of the selection ----

    private void buildRows() {
        rows.clear();
        final HudConfig c = mod.cfg;
        if (SETTINGS.equals(selected)) {
            pill("Overlay", () -> c.enabled, v -> c.enabled = v);
            step("Scale", () -> fmt(c.scale), () -> c.scale = clampScale(c.scale - 0.25f), () -> c.scale = clampScale(c.scale + 0.25f));
            field("Color", c.color, s -> { if (HudConfig.validHex(s)) c.color = norm(s); });
            pill("Background", () -> c.background, v -> c.background = v);
            step("Opacity", () -> Math.round(c.backgroundOpacity * 100) + "%",
                    () -> c.backgroundOpacity = Math.max(0f, Math.round((c.backgroundOpacity - 0.1f) * 10) / 10f),
                    () -> c.backgroundOpacity = Math.min(1f, Math.round((c.backgroundOpacity + 0.1f) * 10) / 10f));
            pill("Shadow", () -> c.shadow, v -> c.shadow = v);
            step("Margin", () -> c.margin + " px", () -> c.margin = Math.max(0, c.margin - 1), () -> c.margin = Math.min(32, c.margin + 1));
        } else if (selected != null) {
            final HudConfig.Widget w = c.widgets.get(selected);
            step("Scale", () -> w.scale == null ? "inherit" : fmt(w.scale),
                    () -> { float v = (w.scale == null ? c.scale : w.scale) - 0.25f; w.scale = v < 0.5f ? null : v; },
                    () -> w.scale = clampScale((w.scale == null ? c.scale : w.scale) + 0.25f));
            field("Color", w.color == null ? "" : w.color, s -> { if (s.isEmpty()) w.color = null; else if (HudConfig.validHex(s)) w.color = norm(s); });
            step("Background", () -> w.background == null ? "inherit" : w.background ? "on" : "off", null,
                    () -> w.background = w.background == null ? Boolean.TRUE : w.background ? Boolean.FALSE : null);
        }
    }

    /** dec == null makes the row a cycle: any click runs inc. */
    private void step(String label, Supplier<String> value, Runnable dec, Runnable inc) {
        Row r = new Row(label);
        r.value = value; r.dec = dec; r.inc = inc;
        rows.add(r);
    }

    private void pill(String label, BooleanSupplier on, Consumer<Boolean> set) {
        Row r = new Row(label);
        r.on = on; r.set = set;
        rows.add(r);
    }

    private void field(String label, String text, Consumer<String> apply) {
        Row r = new Row(label);
        r.field = new GuiTextField(1, fontRendererObj, PANEL - 70, 0, 66, 12);
        r.field.setMaxStringLength(7);
        r.field.setText(text);
        r.apply = apply;
        rows.add(r);
    }

    private static float clampScale(float v) { return Math.max(0.5f, Math.min(3f, Math.round(v * 4) / 4f)); }
    private static String fmt(float v) { return (v == (int) v ? String.valueOf((int) v) : String.valueOf(v)) + "x"; }
    private static String norm(String hex) { return "#" + hex.trim().replace("#", "").toUpperCase(); }
    private static String name(String id) { return id.length() <= 3 ? id.toUpperCase() : Character.toUpperCase(id.charAt(0)) + id.substring(1); }

    /** Changed a setting: mark the root keys dirty when editing the global settings and write the file. */
    private void changed() {
        if (SETTINGS.equals(selected)) mod.cfg.globalsDirty = true;
        mod.save();
    }

    // ---- vertical layout of the panel ----

    private int subHeight() { return rows.isEmpty() ? 0 : rows.size() * ROW + ROW; }
    private int subTop() { return height - 26 - subHeight(); }
    private int settingsRowY() { return subTop() - ROW; }
    private int listTop() { return 22; }
    private int listBottom() { return settingsRowY() - 2; }

    private void filter() {
        visible.clear();
        String q = search.getText().trim().toLowerCase();
        for (String id : HudConfig.IDS) if (id.contains(q)) visible.add(id);
        int max = Math.max(0, visible.size() * ROW - (listBottom() - listTop()));
        scroll = Math.max(0, Math.min(max, scroll));
    }

    // ---- drawing ----

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (mc.theWorld == null) drawBackground(0);
        else drawRect(PANEL, 0, width, height, 0x40000000);
        HudConfig c = mod.cfg;
        filter();

        // preview: region right of the panel
        int W = width - PANEL, H = height, mx = mouseX - PANEL, my = mouseY;
        GlStateManager.pushMatrix();
        GlStateManager.translate(PANEL, 0f, 0f);
        for (PhiHud.Group g : mod.layout(c, W, H, true)) {
            for (PhiHud.Box b : g.boxes) {
                boolean sel = b.item.id.equals(selected);
                if (!b.item.enabled && !sel) continue;
                mod.drawBox(g, b, c);
                if (!b.item.enabled) {
                    drawRect(g.x, b.y0, g.x + g.w, b.y1, 0x99000000);
                    if (!b.item.text()) fontRendererObj.drawStringWithShadow(b.item.id + " (off)", b.x, b.y + 1, 0xFFFFFF);
                }
                boolean hot = sel || (dragId == null && mx >= g.x && mx < g.x + g.w && my >= b.y0 && my < b.y1);
                if (hot) outline(g.x, b.y0, g.x + g.w, b.y1, sel ? 0xFFB3A6FF : 0x80FFFFFF);
                GlStateManager.color(1f, 1f, 1f, 1f);
            }
        }
        GlStateManager.popMatrix();

        // panel
        drawRect(0, 0, PANEL, height, 0xC0101018);
        drawVerticalLine(PANEL - 1, 0, height, 0x40FFFFFF);
        search.drawTextBox();
        if (search.getText().isEmpty() && !search.isFocused()) fontRendererObj.drawString("Search...", 8, 7, 0x808080);

        int top = listTop(), bottom = listBottom();
        for (int i = 0; i < visible.size(); i++) {
            String id = visible.get(i);
            int y = top + i * ROW - scroll;
            if (y < top || y + ROW > bottom) continue;
            boolean sel = id.equals(selected), hover = mouseX < PANEL && mouseY >= y && mouseY < y + ROW;
            if (sel || hover) drawRect(0, y, PANEL - 1, y + ROW, sel ? 0x50B3A6FF : 0x20FFFFFF);
            fontRendererObj.drawString(name(id), 6, y + 3, sel ? 0xFFFFFF : 0xC0C0C0);
            pill(PANEL - 8 - PILL_W, y + 2, mod.cfg.widgets.get(id).enabled);
        }

        int sy = settingsRowY();
        boolean selS = SETTINGS.equals(selected), hoverS = mouseX < PANEL && mouseY >= sy && mouseY < sy + ROW;
        drawHorizontalLine(0, PANEL - 2, sy - 1, 0x40FFFFFF);
        if (selS || hoverS) drawRect(0, sy, PANEL - 1, sy + ROW, selS ? 0x50B3A6FF : 0x20FFFFFF);
        fontRendererObj.drawString("Settings", 6, sy + 3, selS ? 0xFFFFFF : 0xC0C0C0);

        if (!rows.isEmpty()) {
            int y = subTop();
            drawHorizontalLine(0, PANEL - 2, y - 1, 0x40FFFFFF);
            fontRendererObj.drawString(selS ? "Global settings" : name(selected), 6, y + 3, 0xB3A6FF);
            y += ROW;
            for (Row r : rows) {
                fontRendererObj.drawString(r.label, 6, y + 3, 0xC0C0C0);
                if (r.on != null) pill(PANEL - 8 - PILL_W, y + 2, r.on.getAsBoolean());
                else if (r.field != null) {
                    r.field.yPosition = y + 1;
                    r.field.drawTextBox();
                    if (r.field.getText().isEmpty() && !r.field.isFocused()) fontRendererObj.drawString("inherit", PANEL - 66, y + 3, 0x808080);
                } else {
                    String v = r.value.get();
                    if (r.dec != null) fontRendererObj.drawString("-", PANEL - 70, y + 3, 0xFFFFFF);
                    fontRendererObj.drawString(v, PANEL - 41 - fontRendererObj.getStringWidth(v) / 2, y + 3, 0xFFFFFF);
                    fontRendererObj.drawString("+", PANEL - 12, y + 3, 0xFFFFFF);
                }
                y += ROW;
            }
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void pill(int x, int y, boolean on) {
        int bg = on ? 0xFF3CB371 : 0xFF555555;
        drawRect(x + 1, y, x + PILL_W - 1, y + PILL_H, bg);
        drawRect(x, y + 1, x + PILL_W, y + PILL_H - 1, bg);
        int kx = on ? x + PILL_W - PILL_H + 1 : x + 1;
        drawRect(kx, y + 1, kx + PILL_H - 2, y + PILL_H - 1, 0xFFFFFFFF);
    }

    private void outline(int x0, int y0, int x1, int y1, int color) {
        drawHorizontalLine(x0, x1 - 1, y0, color);
        drawHorizontalLine(x0, x1 - 1, y1 - 1, color);
        drawVerticalLine(x0, y0, y1 - 1, color);
        drawVerticalLine(x1 - 1, y0, y1 - 1, color);
    }

    // ---- input ----

    /** Preview hit test in region coordinates; only visible boxes (enabled or selected) count. */
    private PhiHud.Box hit(int mx, int my) {
        List<PhiHud.Group> groups = mod.layout(mod.cfg, width - PANEL, height, true);
        for (int i = groups.size() - 1; i >= 0; i--) {
            PhiHud.Group g = groups.get(i);
            for (PhiHud.Box b : g.boxes)
                if ((b.item.enabled || b.item.id.equals(selected)) && mx >= g.x && mx < g.x + g.w && my >= b.y0 && my < b.y1) return b;
        }
        return null;
    }

    private void select(String id) {
        if (id == null ? selected == null : id.equals(selected)) return;
        selected = id;
        buildRows();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        super.mouseClicked(mouseX, mouseY, button);
        for (GuiButton b : buttonList) if (b.mousePressed(mc, mouseX, mouseY)) return;
        search.mouseClicked(mouseX, mouseY, button);
        for (Row r : rows) if (r.field != null) r.field.mouseClicked(mouseX, mouseY, button);
        if (mouseX < PANEL) { panelClick(mouseX, mouseY); return; }

        int mx = mouseX - PANEL;
        PhiHud.Box b = hit(mx, mouseY);
        if (b == null) return;
        select(b.item.id);
        if (button == 0) {
            dragId = b.item.id;
            dragOffX = mx - b.x;
            dragOffY = mouseY - b.y;
        } else if (button == 1) {
            HudConfig.Widget w = mod.cfg.widgets.get(b.item.id);
            w.enabled = !w.enabled;
            mod.save();
        }
    }

    private void panelClick(int mouseX, int mouseY) {
        int top = listTop(), bottom = listBottom();
        if (mouseY >= top && mouseY < bottom) {
            int i = (mouseY - top + scroll) / ROW;
            if (i < 0 || i >= visible.size()) return;
            String id = visible.get(i);
            if (mouseX >= PANEL - 8 - PILL_W) {
                HudConfig.Widget w = mod.cfg.widgets.get(id);
                w.enabled = !w.enabled;
                mod.save();
            } else select(id);
            return;
        }
        int sy = settingsRowY();
        if (mouseY >= sy && mouseY < sy + ROW) { select(SETTINGS); return; }
        int y = subTop() + ROW;
        for (Row r : rows) {
            if (mouseY >= y && mouseY < y + ROW && mouseX >= PANEL - 74) {
                if (r.on != null) r.set.accept(!r.on.getAsBoolean());
                else if (r.field == null) { if (r.dec != null && mouseX < PANEL - 41) r.dec.run(); else r.inc.run(); }
                else return;
                changed();
                return;
            }
            y += ROW;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long time) {
        if (dragId == null) return;
        HudConfig c = mod.cfg;
        HudConfig.Widget w = c.widgets.get(dragId);
        int W = width - PANEL, H = height, mx = mouseX - PANEL, my = mouseY;
        PhiHud.Group grp = null;
        for (PhiHud.Group g : mod.layout(c, W, H, true))
            for (PhiHud.Box b : g.boxes) if (b.item.id.equals(dragId)) grp = g;
        if (grp == null) return;
        int gw = grp.w, gh = grp.h;
        int gx = snap(mx - dragOffX - PhiHud.PAD, gw, W), gy = snap(my - dragOffY - PhiHud.PAD, gh, H);
        w.x = gx + gw / 2.0 < W / 2.0 ? gx / (double) W : (gx + gw) / (double) W;
        w.y = gy + gh / 2.0 < H / 2.0 ? gy / (double) H : (gy + gh) / (double) H;
        mod.save();
    }

    /** Clamps a box of size len to [0, total] and snaps its edges/center to the screen edges/center within SNAP px. */
    private static int snap(int pos, int len, int total) {
        pos = Math.max(0, Math.min(total - len, pos));
        int[] targets = {0, (total - len) / 2, total - len};
        for (int t : targets) if (Math.abs(pos - t) <= SNAP) return t;
        return pos;
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        dragId = null;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d != 0 && Mouse.getEventX() * width / mc.displayWidth < PANEL) scroll += d < 0 ? ROW : -ROW;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            for (HudConfig.Widget w : mod.cfg.widgets.values()) w.x = w.y = null;
            mod.save();
        } else if (button.id == 1) {
            close();
        }
    }

    @Override
    protected void keyTyped(char ch, int key) {
        if (key == Keyboard.KEY_ESCAPE) { close(); return; }
        if (search.isFocused() && search.textboxKeyTyped(ch, key)) { scroll = 0; return; }
        for (Row r : rows) {
            if (r.field != null && r.field.isFocused() && r.field.textboxKeyTyped(ch, key)) {
                r.apply.accept(r.field.getText());
                changed();
                return;
            }
        }
        if (key == mod.editorKey.getKeyCode()) close();
    }

    private void close() {
        mod.save();
        mc.displayGuiScreen(null);
    }
}
