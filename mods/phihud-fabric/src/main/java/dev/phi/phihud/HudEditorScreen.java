package dev.phi.phihud;

import dev.phi.phihud.HudRenderer.Group;
import dev.phi.phihud.HudRenderer.Placed;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * In-game layout editor: every widget is a draggable box with live content. Left-drag moves (snapping to
 * edges / center within 4 scaled px), right-click toggles enabled. Saves after every change; Escape/Done close.
 * Rendering entry points differ per Minecraft version, so {@code Compat.newEditor} subclasses this and calls
 * {@link #drawBoxes} from the version's background hook.
 */
public abstract class HudEditorScreen extends Screen {
	private static final int SNAP = 4;
	private final Screen parent;
	private String dragId;
	private double dragDx, dragDy; // mouse - box corner, scaled px

	protected HudEditorScreen(Screen parent) {
		super(Component.literal("Phi HUD"));
		this.parent = parent;
	}

	private static HudConfig cfg() { return HudConfig.get(); }

	@Override
	protected void init() {
		int y = height - 26;
		addRenderableWidget(Button.builder(Component.literal("Reset layout"), b -> {
			cfg().widgets.values().forEach(w -> w.x = w.y = null);
			cfg().save();
		}).bounds(width / 2 - 155, y, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 50, y, 100, 20).build());
		addRenderableWidget(Checkbox.builder(Component.literal("Show overlay"), font).pos(width / 2 + 55, y + 1)
			.selected(cfg().enabled).onValueChange((cb, v) -> { cfg().enabled = v; cfg().save(); }).build());
	}

	@Override
	public void onClose() {
		cfg().save();
		Compat.setScreen(minecraft, parent);
	}

	private List<Group> groups() {
		float s = HudRenderer.scale(cfg());
		return HudRenderer.layout(cfg(), font, (int) (width / s), (int) (height / s), true);
	}

	private Placed find(double mx, double my) {
		float s = HudRenderer.scale(cfg());
		List<Group> groups = groups();
		for (int i = groups.size() - 1; i >= 0; i--) // topmost (drawn last) wins
			for (Placed p : groups.get(i).items())
				if (p.contains(mx / s, my / s)) return p;
		return null;
	}

	/** Draws the widget boxes in HUD space; called from the version-specific background hook. */
	protected void drawBoxes(Draw g, int mouseX, int mouseY) {
		HudConfig cfg = cfg();
		float s = HudRenderer.scale(cfg);
		Placed hover = dragId == null ? find(mouseX, mouseY) : null;
		g.pose().pushMatrix();
		g.pose().scale(s, s);
		for (Group grp : groups()) {
			for (Placed p : grp.items()) {
				boolean off = !cfg.widgets.get(p.id()).enabled;
				int x1 = p.x() - HudRenderer.PAD, y1 = p.y() - HudRenderer.PAD, x2 = p.x() + p.w() + HudRenderer.PAD, y2 = p.y() + p.h() + HudRenderer.PAD;
				g.fill(x1, y1, x2, y2, cfg.background ? cfg.backgroundArgb() : 0x30000000);
				int border = p.id().equals(dragId) || p == hover ? 0xFFFFFFFF : off ? 0x66FFFFFF : 0xAAFFFFFF;
				g.fill(x1, y1, x2, y1 + 1, border);
				g.fill(x1, y2 - 1, x2, y2, border);
				g.fill(x1, y1, x1 + 1, y2, border);
				g.fill(x2 - 1, y1, x2, y2, border);
				HudRenderer.drawWidget(g, font, p, cfg, off);
			}
		}
		g.pose().popMatrix();
		g.text(font, "Drag to move, right-click to toggle, Esc to close", 4, 4, 0xFFAAAAAA, true);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) return true;
		Placed p = find(e.x(), e.y());
		if (p == null) return false;
		float s = HudRenderer.scale(cfg());
		if (e.button() == 1) {
			HudConfig.Widget w = cfg().widgets.get(p.id());
			w.enabled = !w.enabled;
			cfg().save();
		} else if (e.button() == 0) {
			dragId = p.id();
			dragDx = e.x() / s - p.x();
			dragDy = e.y() / s - p.y();
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragId == null) return super.mouseDragged(e, dx, dy);
		HudConfig cfg = cfg();
		HudConfig.Widget w = cfg.widgets.get(dragId);
		float s = HudRenderer.scale(cfg);
		int sw = (int) (width / s), sh = (int) (height / s);
		Placed p = null;
		for (Group grp : groups()) for (Placed q : grp.items()) if (q.id().equals(dragId)) p = q;
		if (p == null) return true;
		int edge = cfg.margin + HudRenderer.PAD;
		int x = snap(e.x() / s - dragDx, edge, (sw - p.w()) / 2, sw - edge - p.w());
		int y = snap(e.y() / s - dragDy, edge, (sh - p.h()) / 2, sh - edge - p.h());
		x = Math.max(0, Math.min(sw - p.w(), x));
		y = Math.max(0, Math.min(sh - p.h(), y));
		// pivot: the corner nearest the box center, so the fraction stays on the same side of 0.5
		w.x = x + p.w() / 2.0 < sw / 2.0 ? x / (double) sw : (x + p.w()) / (double) sw;
		w.y = y + p.h() / 2.0 < sh / 2.0 ? y / (double) sh : (y + p.h()) / (double) sh;
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
}
