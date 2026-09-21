package dev.phi.phihud;

import dev.phi.phihud.HudRenderer.Group;
import dev.phi.phihud.HudRenderer.Placed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleConsumer;

/**
 * The Phi HUD menu: a widget list with ON/OFF pills on the left (or the global settings), a framed live preview
 * of the layout on the right where the selected widget can be dragged (snapping to edges / center within 4 px).
 * Every change is saved to config/phihud.json immediately; Escape / Done close.
 * The render entry points differ per Minecraft version, so {@code Compat.newEditor} subclasses this: it calls
 * {@link #drawBackground} from the version's background hook and supplies the list / row subclasses.
 */
public abstract class HudMenuScreen extends Screen {
	static final int PANEL = 140, SNAP = 4, ROW = 16;
	private static final Map<String, String> NAMES = Map.ofEntries(
		Map.entry("fps", "FPS"), Map.entry("tps", "TPS"), Map.entry("coords", "Coordinates"), Map.entry("direction", "Direction"),
		Map.entry("ping", "Ping"), Map.entry("memory", "Memory"), Map.entry("clock", "Clock"), Map.entry("armor", "Armor"),
		Map.entry("inventory", "Inventory"), Map.entry("keystrokes", "Keystrokes"), Map.entry("cps", "CPS"), Map.entry("speed", "Speed"),
		Map.entry("biome", "Biome"), Map.entry("gametime", "Game time"), Map.entry("light", "Light"), Map.entry("target", "Target"),
		Map.entry("server", "Server"), Map.entry("session", "Session"), Map.entry("effects", "Effects"), Map.entry("hunger", "Hunger"),
		Map.entry("health", "Health"));

	private final Screen parent;
	private String selected;    // widget shown in the per-widget block, null = none
	private boolean settings;   // global settings replace the list
	private String search = "";
	private String dragId;
	private double dragDx, dragDy; // mouse - box corner, screen px
	private boolean updating;      // suppress EditBox responders while refresh() sets values

	private WidgetList list;
	private EditBox searchBox;
	private Button settingsButton, wBackground;
	private Slider wScale;
	private EditBox wColor;
	private final List<AbstractWidget> widgetControls = new ArrayList<>(), globalControls = new ArrayList<>();

	protected HudMenuScreen(Screen parent) {
		super(Component.literal("Phi HUD"));
		this.parent = parent;
	}

	static HudConfig cfg() { return HudConfig.get(); }
	static String name(String id) { return NAMES.getOrDefault(id, id); }

	// ---- version hooks (entry/list render signatures differ per Minecraft version) ----
	protected abstract WidgetList newList(int width, int height, int y);
	protected abstract Row newRow(String id);

	@Override
	protected void init() {
		HudConfig cfg = cfg();
		widgetControls.clear();
		globalControls.clear();
		int x = 6, w = PANEL - 12;
		int listBottom = height - 118;

		searchBox = addRenderableWidget(new EditBox(font, x, 18, w, 16, Component.literal("Search")));
		searchBox.setHint(Component.literal("Search..."));
		searchBox.setValue(search);
		searchBox.setResponder(s -> { search = s; fillList(); });

		list = addRenderableWidget(newList(PANEL, listBottom - 38, 38));
		fillList();

		// per-widget block: scale (+ reset to inherit), color, background
		int y = listBottom + 4;
		wScale = new Slider(x, y, w - 22, "Scale", 0.5, 3, 0.05, cfg.scale, v -> { widget().scale = v; cfg().save(); });
		widgetControls.add(addRenderableWidget(wScale));
		widgetControls.add(addRenderableWidget(Button.builder(Component.literal("x"), b -> { widget().scale = null; cfg().save(); refresh(); })
			.bounds(x + w - 20, y, 20, 20).build()));
		wColor = new EditBox(font, x, y + 22, w, 20, Component.literal("Color"));
		wColor.setHint(Component.literal("Color: inherit"));
		wColor.setResponder(s -> {
			if (updating) return;
			if (s.isBlank()) widget().color = null;
			else if (HudConfig.hex(s) != null) widget().color = HudConfig.hex(s);
			else return;
			cfg().save();
		});
		widgetControls.add(addRenderableWidget(wColor));
		wBackground = Button.builder(Component.empty(), b -> {
			HudConfig.Widget wd = widget();
			wd.background = wd.background == null ? Boolean.TRUE : wd.background ? Boolean.FALSE : null; // inherit -> on -> off
			cfg().save();
			refresh();
		}).bounds(x, y + 44, w, 20).build();
		widgetControls.add(addRenderableWidget(wBackground));

		// global settings block (shown instead of the list)
		y = 38;
		Checkbox overlay = Checkbox.builder(Component.literal("Overlay"), font).pos(x, y).selected(cfg.enabled)
			.onValueChange((cb, v) -> { cfg().enabled = v; cfg().save(); }).build();
		globalControls.add(addRenderableWidget(overlay));
		globalControls.add(addRenderableWidget(Checkbox.builder(Component.literal("Shadow"), font).pos(x + overlay.getWidth() + 4, y).selected(cfg.shadow)
			.onValueChange((cb, v) -> { cfg().shadow = v; cfg().save(); }).build()));
		globalControls.add(addRenderableWidget(new Slider(x, y + 22, w, "Scale", 0.5, 3, 0.05, cfg.scale, v -> { cfg().scale = v; cfg().save(); })));
		EditBox gColor = new EditBox(font, x, y + 44, w, 20, Component.literal("Color"));
		gColor.setValue(cfg.color);
		gColor.setResponder(s -> { if (HudConfig.hex(s) != null) { cfg().color = HudConfig.hex(s); cfg().save(); } });
		globalControls.add(addRenderableWidget(gColor));
		globalControls.add(addRenderableWidget(Checkbox.builder(Component.literal("Background"), font).pos(x, y + 66).selected(cfg.background)
			.onValueChange((cb, v) -> { cfg().background = v; cfg().save(); }).build()));
		globalControls.add(addRenderableWidget(new Slider(x, y + 88, w, "Opacity", 0, 1, 0.05, cfg.backgroundOpacity, v -> { cfg().backgroundOpacity = v; cfg().save(); })));
		globalControls.add(addRenderableWidget(new Slider(x, y + 110, w, "Margin", 0, 32, 1, cfg.margin, v -> { cfg().margin = (int) v; cfg().save(); })));

		settingsButton = addRenderableWidget(Button.builder(Component.empty(), b -> { settings = !settings; refresh(); }).bounds(x, height - 48, w, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Reset layout"), b -> {
			cfg().widgets.values().forEach(wd -> wd.x = wd.y = null);
			cfg().save();
		}).bounds(x, height - 24, w / 2 - 2, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x + w / 2 + 2, height - 24, w / 2 - 2, 20).build());
		refresh();
	}

	private HudConfig.Widget widget() { return cfg().widgets.get(selected); }

	private void fillList() {
		List<Row> rows = new ArrayList<>();
		for (String id : cfg().widgets.keySet())
			if (name(id).toLowerCase(Locale.ROOT).contains(search.trim().toLowerCase(Locale.ROOT))) rows.add(newRow(id));
		list.replaceEntries(rows);
		list.setSelected(rows.stream().filter(r -> r.id.equals(selected)).findFirst().orElse(null));
	}

	void select(String id) {
		selected = id;
		list.setSelected(list.children().stream().filter(r -> r.id.equals(id)).findFirst().orElse(null));
		refresh();
	}

	/** Shows the list or the global settings, and loads the selected widget's overrides into its controls. */
	private void refresh() {
		HudConfig cfg = cfg();
		list.visible = searchBox.visible = !settings;
		settingsButton.setMessage(Component.literal(settings ? "Back" : "Settings"));
		HudConfig.Widget w = selected == null ? null : cfg.widgets.get(selected);
		boolean showW = !settings && w != null;
		for (AbstractWidget c : widgetControls) c.visible = showW;
		for (AbstractWidget c : globalControls) c.visible = settings;
		if (!showW) return;
		wScale.set(cfg.scale(w), w.scale == null ? "inherit" : null);
		updating = true;
		wColor.setValue(w.color == null ? "" : w.color);
		updating = false;
		wBackground.setMessage(Component.literal("Background: " + (w.background == null ? "inherit" : w.background ? "on" : "off")));
	}

	@Override
	public void onClose() {
		cfg().save();
		Compat.setScreen(minecraft, parent);
	}

	// ---- preview: the whole screen scaled into the area right of the panel ----

	private float previewScale() {
		return Math.min((width - PANEL - 12) / (float) width, (height - 12) / (float) height);
	}
	private float previewX() { return PANEL + 6 + (width - PANEL - 12 - width * previewScale()) / 2; }
	private float previewY() { return 6 + (height - 12 - height * previewScale()) / 2; }

	private Group[] groups() { return HudRenderer.layout(cfg(), font, width, height, selected); }

	private Placed find(double sx, double sy) {
		Group[] groups = groups();
		for (int i = groups.length - 1; i >= 0; i--) // topmost (drawn last) wins
			for (Placed p : groups[i].items())
				if (p.contains(sx, sy)) return p;
		return null;
	}

	/** Draws the framed preview and the left panel; called from the version-specific background hook before the widgets. */
	protected void drawBackground(Draw g, int mouseX, int mouseY) {
		HudConfig cfg = cfg();
		float f = previewScale();
		g.pose().pushMatrix();
		g.pose().translate(previewX(), previewY());
		g.pose().scale(f, f);
		g.fill(0, 0, width, height, 0x50000000);
		g.fill(0, 0, width, 1, 0x80FFFFFF);
		g.fill(0, height - 1, width, height, 0x80FFFFFF);
		g.fill(0, 0, 1, height, 0x80FFFFFF);
		g.fill(width - 1, 0, width, height, 0x80FFFFFF);
		for (Group grp : groups()) {
			HudRenderer.drawGroupBackground(g, grp, cfg);
			for (Placed p : grp.items()) {
				HudRenderer.drawWidget(g, font, p, cfg, !cfg.widgets.get(p.id()).enabled);
				if (p.id().equals(selected)) {
					int pad = p.pad(), x1 = p.x() - pad, y1 = p.y() - pad, x2 = p.x() + p.w() + pad, y2 = p.y() + p.h() + pad;
					g.fill(x1, y1, x2, y1 + 1, 0xFFFFFFFF);
					g.fill(x1, y2 - 1, x2, y2, 0xFFFFFFFF);
					g.fill(x1, y1, x1 + 1, y2, 0xFFFFFFFF);
					g.fill(x2 - 1, y1, x2, y2, 0xFFFFFFFF);
				}
			}
		}
		g.pose().popMatrix();
		g.fill(0, 0, PANEL, height, 0xD0101010);
		g.text(font, "Phi HUD", 6, 5, 0xFFB388FF, true);
		if (selected != null && !settings) g.text(font, name(selected), PANEL - 6 - font.width(name(selected)), 5, 0xFFAAAAAA, true);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) return true;
		if (e.button() != 0 || e.x() < PANEL) return false;
		double sx = (e.x() - previewX()) / previewScale(), sy = (e.y() - previewY()) / previewScale();
		Placed p = find(sx, sy);
		if (p == null) return false;
		select(p.id());
		dragId = p.id();
		dragDx = sx - p.x();
		dragDy = sy - p.y();
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragId == null) return super.mouseDragged(e, dx, dy);
		HudConfig cfg = cfg();
		HudConfig.Widget w = cfg.widgets.get(dragId);
		Placed p = null;
		for (Group grp : groups()) for (Placed q : grp.items()) if (q.id().equals(dragId)) p = q;
		if (p == null) return true;
		double sx = (e.x() - previewX()) / previewScale(), sy = (e.y() - previewY()) / previewScale();
		int edge = Math.round(cfg.margin * HudRenderer.scale(cfg)) + p.pad();
		int x = snap(sx - dragDx, edge, (width - p.w()) / 2, width - edge - p.w());
		int y = snap(sy - dragDy, edge, (height - p.h()) / 2, height - edge - p.h());
		x = Math.max(0, Math.min(width - p.w(), x));
		y = Math.max(0, Math.min(height - p.h(), y));
		// pivot: the corner nearest the box center, so the fraction stays on the same side of 0.5
		w.x = x + p.w() / 2.0 < width / 2.0 ? x / (double) width : (x + p.w()) / (double) width;
		w.y = y + p.h() / 2.0 < height / 2.0 ? y / (double) height : (y + p.h()) / (double) height;
		return true;
	}

	private static int snap(double v, int... targets) {
		for (int t : targets) if (Math.abs(v - t) <= SNAP) return t;
		return (int) Math.round(v);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (dragId != null) cfg().save(); // one write per drag, not per mouse event
		dragId = null;
		return super.mouseReleased(e);
	}

	@Override
	public boolean isPauseScreen() { return false; }

	// ---- vanilla widget subclasses ----

	/** The widget list; the version subclass blanks the vanilla list background so the panel shows through. */
	protected abstract static class WidgetList extends ObjectSelectionList<Row> {
		protected WidgetList(Minecraft mc, int width, int height, int y) {
			super(mc, width, height, y, ROW);
		}
		@Override public int getRowWidth() { return getWidth() - 14; }
		@Override protected int scrollBarX() { return getRight() - 6; }
		@Override public boolean isMouseOver(double x, double y) { return visible && super.isMouseOver(x, y); }
		@Override public boolean mouseClicked(MouseButtonEvent e, boolean d) { return visible && super.mouseClicked(e, d); }
		@Override public boolean mouseScrolled(double x, double y, double dx, double dy) { return visible && super.mouseScrolled(x, y, dx, dy); }
	}

	/** One widget row: name + ON/OFF pill. Clicking the pill toggles, clicking elsewhere selects. */
	protected abstract class Row extends ObjectSelectionList.Entry<Row> {
		final String id;
		protected Row(String id) { this.id = id; }

		@Override public Component getNarration() { return Component.literal(name(id)); }

		@Override
		public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
			if (e.x() >= getX() + getWidth() - 26) {
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
			if (hovered) g.fill(x, y, x + w, y + h, 0x30FFFFFF);
			g.text(font, name(id), x + 4, y + (h - 8) / 2, 0xFFFFFFFF, true);
			boolean on = cfg().widgets.get(id).enabled;
			int px = x + w - 22, py = y + (h - 8) / 2, c = on ? 0xFF3DBE5B : 0xFF5A5A5A;
			g.fill(px + 1, py, px + 17, py + 8, c);
			g.fill(px, py + 1, px + 18, py + 7, c);
			int kx = on ? px + 11 : px + 1;
			g.fill(kx, py + 1, kx + 6, py + 7, 0xFFFFFFFF);
		}
	}

	/** A labelled slider over [min, max] in steps; {@code override} text (e.g. "inherit") replaces the value until moved. */
	private static final class Slider extends AbstractSliderButton {
		private final String label;
		private final double min, max, step;
		private final DoubleConsumer apply;
		private String override;

		Slider(int x, int y, int w, String label, double min, double max, double step, double v, DoubleConsumer apply) {
			super(x, y, w, 20, Component.empty(), 0);
			this.label = label;
			this.min = min;
			this.max = max;
			this.step = step;
			this.apply = apply;
			set(v, null);
		}

		double get() { return Math.round((min + value * (max - min)) / step) * step; }

		void set(double v, String override) {
			value = Math.max(0, Math.min(1, (v - min) / (max - min)));
			this.override = override;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			String v = override != null ? override : step >= 1 ? String.valueOf((int) get()) : String.format(Locale.ROOT, "%.2f", get());
			setMessage(Component.literal(label + ": " + v));
		}

		@Override
		protected void applyValue() {
			override = null;
			apply.accept(get());
		}
	}
}
