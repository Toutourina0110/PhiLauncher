package phihud;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.List;

/** Drag-and-drop layout editor. Left-drag moves a widget (snaps to edges/center), right-click toggles it. */
public class EditorScreen extends GuiScreen {
    private static final int SNAP = 4;
    private final PhiHud mod;
    private GuiButton showBtn;
    private String dragId;
    private int dragOffX, dragOffY;

    EditorScreen(PhiHud mod) { this.mod = mod; }

    @Override
    public void initGui() {
        int y = height - 24, x = width / 2 - 145;
        buttonList.add(new GuiButton(0, x, y, 90, 20, "Reset layout"));
        buttonList.add(new GuiButton(1, x + 95, y, 60, 20, "Done"));
        buttonList.add(showBtn = new GuiButton(2, x + 160, y, 130, 20, ""));
        updateShowBtn();
    }

    private void updateShowBtn() {
        showBtn.displayString = "Show overlay: " + (mod.cfg.enabled ? "ON" : "OFF");
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (mc.theWorld == null) drawBackground(0);
        else drawRect(0, 0, width, height, 0x60000000);
        HudConfig c = mod.cfg;
        float s = c.scale;
        int W = (int) (width / s), H = (int) (height / s);
        int mx = (int) (mouseX / s), my = (int) (mouseY / s);

        GlStateManager.pushMatrix();
        GlStateManager.scale(s, s, 1f);
        for (PhiHud.Group g : mod.layout(c, W, H, true)) {
            for (PhiHud.Box b : g.boxes) {
                int x0 = g.x, x1 = g.x + g.w, y0 = b.y - PhiHud.PAD, y1 = b.y + b.item.h + PhiHud.PAD;
                boolean hot = b.item.id.equals(dragId) || (dragId == null && mx >= x0 && mx < x1 && my >= y0 && my < y1);
                drawRect(x0, y0, x1, y1, hot ? 0xA0303040 : 0x80202020);
                GlStateManager.color(1f, 1f, 1f, 1f);
                mod.draw(b.item, b.x, b.y, c);
                if (!b.item.enabled) {
                    drawRect(x0, y0, x1, y1, 0x99000000); // 40% opacity
                    if (b.item.text == null) fontRendererObj.drawStringWithShadow(b.item.id + " (off)", b.x, b.y + 1, 0xFFFFFF);
                }
                if (hot) {
                    drawHorizontalLine(x0, x1 - 1, y0, 0xFFFFFFFF);
                    drawHorizontalLine(x0, x1 - 1, y1 - 1, 0xFFFFFFFF);
                    drawVerticalLine(x0, y0, y1 - 1, 0xFFFFFFFF);
                    drawVerticalLine(x1 - 1, y0, y1 - 1, 0xFFFFFFFF);
                }
                GlStateManager.color(1f, 1f, 1f, 1f);
            }
        }
        GlStateManager.popMatrix();
        drawCenteredString(fontRendererObj, "Drag to move, right-click to toggle, " + Keyboard.getKeyName(mod.editorKey.getKeyCode()) + "/Esc to close", width / 2, height - 36, 0xA0A0A0);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private PhiHud.Box hit(int mx, int my) {
        HudConfig c = mod.cfg;
        List<PhiHud.Group> groups = mod.layout(c, (int) (width / c.scale), (int) (height / c.scale), true);
        for (int i = groups.size() - 1; i >= 0; i--) {
            PhiHud.Group g = groups.get(i);
            for (PhiHud.Box b : g.boxes)
                if (mx >= g.x && mx < g.x + g.w && my >= b.y - PhiHud.PAD && my < b.y + b.item.h + PhiHud.PAD) return b;
        }
        return null;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        super.mouseClicked(mouseX, mouseY, button);
        for (GuiButton b : buttonList) if (b.mousePressed(mc, mouseX, mouseY)) return;
        float s = mod.cfg.scale;
        int mx = (int) (mouseX / s), my = (int) (mouseY / s);
        PhiHud.Box b = hit(mx, my);
        if (b == null) return;
        HudConfig.Widget w = mod.cfg.widgets.get(b.item.id);
        if (button == 0) {
            dragId = b.item.id;
            dragOffX = mx - b.x;
            dragOffY = my - b.y;
        } else if (button == 1) {
            w.enabled = !w.enabled;
            mod.save();
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long time) {
        if (dragId == null) return;
        HudConfig c = mod.cfg;
        HudConfig.Widget w = c.widgets.get(dragId);
        float s = c.scale;
        int W = (int) (width / s), H = (int) (height / s);
        int mx = (int) (mouseX / s), my = (int) (mouseY / s);
        PhiHud.Item it = null;
        for (PhiHud.Group g : mod.layout(c, W, H, true))
            for (PhiHud.Box b : g.boxes) if (b.item.id.equals(dragId)) it = b.item;
        if (it == null) return;
        int gw = it.w + 2 * PhiHud.PAD, gh = it.h + 2 * PhiHud.PAD;
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
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            for (HudConfig.Widget w : mod.cfg.widgets.values()) w.x = w.y = null;
            mod.save();
        } else if (button.id == 1) {
            close();
        } else if (button.id == 2) {
            mod.cfg.enabled = !mod.cfg.enabled;
            mod.save();
            updateShowBtn();
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_ESCAPE || key == mod.editorKey.getKeyCode()) close();
    }

    private void close() {
        mod.save();
        mc.displayGuiScreen(null);
    }
}
