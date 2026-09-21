package phihud;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Mod(modid = "phihud", name = "Phi HUD", version = "1.0.0", clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class PhiHud {
    private static final ResourceLocation LOGO = new ResourceLocation("phihud", "textures/gui/phi_logo.png");
    private static final int PHI_BUTTON = 7801;

    final Minecraft mc = Minecraft.getMinecraft();
    private File cfgFile;
    private long cfgMtime = -1, nextPoll;
    HudConfig cfg = new HudConfig();
    KeyBinding editorKey;
    private int titleFirstButtonY = 108; // vanilla: height / 4 + 48 at 240 px

    // TPS: wall-clock interval between S03PacketTimeUpdate packets (server sends one every 20 ticks).
    private volatile double tps = -1;
    private long lastTimeNanos, lastTotalTime;
    private final double[] samples = new double[5];
    private int sampleCount;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        cfgFile = new File(mc.mcDataDir, "config/phihud.json");
        reload();
        editorKey = new KeyBinding("key.phihud.editor", Keyboard.KEY_H, "key.categories.phihud");
        ClientRegistry.registerKeyBinding(editorKey);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void reload() {
        cfgMtime = cfgFile.isFile() ? cfgFile.lastModified() : -1;
        cfg = HudConfig.load(cfgFile);
    }

    /** Writes the config; remembers the mtime so the poll does not reload our own write. */
    void save() {
        cfg.save(cfgFile);
        cfgMtime = cfgFile.isFile() ? cfgFile.lastModified() : -1;
    }

    void openEditor() {
        mc.displayGuiScreen(new EditorScreen(this));
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        long now = System.currentTimeMillis();
        if (now < nextPoll) return;
        nextPoll = now + 2000;
        long m = cfgFile.isFile() ? cfgFile.lastModified() : -1;
        if (m != cfgMtime) reload();
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent e) {
        if (editorKey.isPressed()) openEditor();
    }

    // ---- title screen / pause menu ----

    @SubscribeEvent
    public void onInitGui(GuiScreenEvent.InitGuiEvent.Post e) {
        boolean title = e.gui.getClass() == GuiMainMenu.class;
        if (!title && e.gui.getClass() != GuiIngameMenu.class) return;
        GuiButton options = null;
        int firstY = Integer.MAX_VALUE;
        for (GuiButton b : e.buttonList) {
            if (b.id == 0) options = b;
            firstY = Math.min(firstY, b.yPosition);
        }
        if (options == null) return;
        if (title) titleFirstButtonY = firstY;
        int y = options.yPosition + 24;
        if (!title) for (GuiButton b : e.buttonList) if (b.yPosition >= y) b.yPosition += 24; // push "Disconnect" down
        e.buttonList.add(new GuiButton(PHI_BUTTON, e.gui.width / 2 - 100, y, 200, 20, "Phi HUD"));
    }

    @SubscribeEvent
    public void onAction(GuiScreenEvent.ActionPerformedEvent.Pre e) {
        if (e.button.id != PHI_BUTTON) return;
        e.setCanceled(true);
        openEditor();
    }

    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post e) {
        if (e.gui.getClass() != GuiMainMenu.class) return;
        // The vanilla logo texture is overridden by a transparent one in this jar; draw the Phi mark in its place.
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        mc.getTextureManager().bindTexture(LOGO);
        // 128x128 texture drawn 1:1, bottom edge 8 px above the first button; shrunk (square) when the screen is too short.
        int size = Math.min(128, titleFirstButtonY - 12);
        if (size > 0) Gui.drawScaledCustomSizeModalRect(e.gui.width / 2 - size / 2, titleFirstButtonY - 8 - size, 0, 0, 128, 128, size, size, 128, 128);
        mc.fontRendererObj.drawStringWithShadow("Phi Launcher", 2, e.gui.height - 20, 0xFFB3A6FF);
    }

    // ---- TPS ----

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent e) {
        resetTps();
        e.manager.channel().pipeline().addBefore("packet_handler", "phihud_tps", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof S03PacketTimeUpdate) onTimeUpdate(((S03PacketTimeUpdate) msg).getTotalWorldTime());
                ctx.fireChannelRead(msg);
            }
        });
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
        resetTps();
    }

    private synchronized void resetTps() {
        tps = -1;
        lastTimeNanos = 0;
        sampleCount = 0;
    }

    private synchronized void onTimeUpdate(long totalTime) {
        long now = System.nanoTime();
        if (lastTimeNanos != 0) {
            long ticks = totalTime - lastTotalTime;
            double secs = (now - lastTimeNanos) / 1e9;
            if (ticks <= 0 || secs <= 0) { resetTps(); }
            else {
                samples[sampleCount++ % samples.length] = Math.min(20.0, ticks / secs);
                int n = Math.min(sampleCount, samples.length);
                double sum = 0;
                for (int i = 0; i < n; i++) sum += samples[i];
                tps = sum / n;
            }
        }
        lastTimeNanos = now;
        lastTotalTime = totalTime;
    }

    // ---- layout ----

    static final int PAD = 2, LINE = 10, SLOT = 18;

    /** w/h are the natural (unscaled) size; scale/color/bg are the resolved per-widget values. */
    static final class Item {
        final String id, text;
        final int w, h, order, color;
        final boolean enabled, bg;
        final float scale;
        Item(String id, String text, int w, int h, HudConfig.Widget wd, HudConfig c) {
            this.id = id; this.text = text; this.w = w; this.h = h; this.order = wd.order; this.enabled = wd.enabled;
            this.scale = wd.scale != null ? wd.scale : c.scale;
            this.color = wd.color != null ? HudConfig.argb(wd.color) : c.argb();
            this.bg = wd.background != null ? wd.background : c.background;
        }
        int sw() { return Math.round(w * scale); }
        int sh() { return Math.round(h * scale); }
    }

    /** A placed item: content position (x, y) and the vertical extent (y0..y1) of its background slice. */
    static final class Box {
        final Item item;
        final int x, y, y0, y1;
        Box(Item item, int x, int y, int y0, int y1) { this.item = item; this.x = x; this.y = y; this.y0 = y0; this.y1 = y1; }
    }

    /** One background box: a free-positioned widget or an anchor stack. */
    static final class Group {
        final List<Box> boxes = new ArrayList<Box>();
        int x, y, w, h;
    }

    /** Lays out widgets for a W x H (GUI px) screen. all = include disabled widgets (editor). */
    List<Group> layout(HudConfig c, int W, int H, boolean all) {
        List<Group> out = new ArrayList<Group>();
        Map<String, List<Item>> stacks = new LinkedHashMap<String, List<Item>>();
        for (Map.Entry<String, HudConfig.Widget> e : c.widgets.entrySet()) {
            HudConfig.Widget w = e.getValue();
            if (!w.enabled && !all) continue;
            Item it = make(e.getKey(), w, c, all);
            if (it == null) continue;
            if (w.free()) {
                Group g = new Group();
                g.w = it.sw() + 2 * PAD;
                g.h = it.sh() + 2 * PAD;
                int px = (int) Math.round(w.x * W), py = (int) Math.round(w.y * H);
                g.x = w.x < 0.5 ? px : px - g.w;
                g.y = w.y < 0.5 ? py : py - g.h;
                g.boxes.add(new Box(it, g.x + PAD, g.y + PAD, g.y, g.y + g.h));
                out.add(g);
            } else {
                List<Item> l = stacks.get(w.anchor);
                if (l == null) stacks.put(w.anchor, l = new ArrayList<Item>());
                l.add(it);
            }
        }
        for (Map.Entry<String, List<Item>> e : stacks.entrySet()) {
            List<Item> items = e.getValue();
            boolean top = e.getKey().startsWith("top"), left = e.getKey().endsWith("left");
            Collections.sort(items, new Comparator<Item>() {
                public int compare(Item a, Item b) { return a.order - b.order; }
            });
            if (!top) Collections.reverse(items); // bottom anchors stack upward: order 0 nearest the edge
            Group g = new Group();
            for (Item it : items) { g.w = Math.max(g.w, it.sw()); g.h += it.sh(); }
            g.w += 2 * PAD; g.h += 2 * PAD;
            int m = Math.round(c.margin * c.scale);
            g.x = left ? m : W - m - g.w;
            g.y = top ? m : H - m - g.h;
            int cy = g.y + PAD;
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                g.boxes.add(new Box(it, g.x + PAD, cy, i == 0 ? g.y : cy, i == items.size() - 1 ? g.y + g.h : cy + it.sh()));
                cy += it.sh();
            }
            out.add(g);
        }
        return out;
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL) return;
        HudConfig c = cfg;
        if (!c.enabled || mc.currentScreen != null || mc.gameSettings.showDebugInfo || mc.thePlayer == null) return;

        ScaledResolution res = e.resolution;
        for (Group g : layout(c, res.getScaledWidth(), res.getScaledHeight(), false))
            for (Box b : g.boxes) drawBox(g, b, c);
    }

    /** Background slice + content of one placed widget, at its own scale. */
    void drawBox(Group g, Box b, HudConfig c) {
        if (b.item.bg) {
            int a = MathHelper.clamp_int((int) (c.backgroundOpacity * 255), 0, 255);
            Gui.drawRect(g.x, b.y0, g.x + g.w, b.y1, a << 24);
            GlStateManager.color(1f, 1f, 1f, 1f);
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(b.x, b.y, 0f);
        GlStateManager.scale(b.item.scale, b.item.scale, 1f);
        draw(b.item, c);
        GlStateManager.popMatrix();
    }

    private Item make(String id, HudConfig.Widget w, HudConfig c, boolean all) {
        EntityPlayer p = mc.thePlayer;
        String t;
        if ("fps".equals(id)) t = "FPS: " + Minecraft.getDebugFPS();
        else if ("tps".equals(id)) { double v = tps; t = v < 0 ? "TPS: --" : String.format("TPS: %.1f", v); }
        else if ("coords".equals(id)) t = p == null ? "XYZ: --" : String.format("XYZ: %.1f / %.1f / %.1f", p.posX, p.posY, p.posZ);
        else if ("direction".equals(id)) {
            t = p == null ? "Facing: --" : String.format("Facing: %s (%.1f / %.1f)", facing(p.getHorizontalFacing()),
                    MathHelper.wrapAngleTo180_float(p.rotationYaw), MathHelper.wrapAngleTo180_float(p.rotationPitch));
        }
        else if ("ping".equals(id)) {
            NetworkPlayerInfo info = p == null || mc.isSingleplayer() || mc.getNetHandler() == null ? null
                    : mc.getNetHandler().getPlayerInfo(p.getUniqueID());
            t = info == null ? "Ping: --" : "Ping: " + info.getResponseTime() + " ms";
        }
        else if ("memory".equals(id)) {
            Runtime r = Runtime.getRuntime();
            long max = r.maxMemory() >> 20, used = (r.totalMemory() - r.freeMemory()) >> 20;
            t = String.format("Mem: %d / %d MB (%d%%)", used, max, max == 0 ? 0 : used * 100 / max);
        }
        else if ("clock".equals(id)) t = new SimpleDateFormat("HH:mm").format(new Date());
        else if ("armor".equals(id)) return new Item(id, null, 5 * SLOT, SLOT, w, c);
        else if ("inventory".equals(id)) return new Item(id, null, 9 * SLOT, 3 * SLOT, w, c);
        else return null;
        if (all && !w.enabled) t += " (off)";
        return new Item(id, t, mc.fontRendererObj.getStringWidth(t), LINE, w, c);
    }

    private static String facing(EnumFacing f) {
        switch (f) {
            case NORTH: return "North (-Z)";
            case SOUTH: return "South (+Z)";
            case WEST: return "West (-X)";
            case EAST: return "East (+X)";
            default: return f.getName();
        }
    }

    /** Draws the item's content at the origin (caller translates/scales). */
    void draw(Item it, HudConfig c) {
        int x = 0, y = 0;
        if (it.text != null) {
            mc.fontRendererObj.drawString(it.text, x, y + 1, it.color, c.shadow);
            return;
        }
        if (mc.thePlayer == null) return; // title-screen editor: empty slots
        ItemStack[] inv = mc.thePlayer.inventory.mainInventory;
        ItemStack[] armor = mc.thePlayer.inventory.armorInventory;
        RenderItem ri = mc.getRenderItem();
        FontRenderer fr = mc.fontRendererObj;
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        RenderHelper.enableGUIStandardItemLighting();
        if ("armor".equals(it.id)) {
            // helmet, chest, legs, boots, held item
            for (int i = 0; i < 4; i++) slot(ri, fr, armor[3 - i], x + i * SLOT, y);
            slot(ri, fr, mc.thePlayer.getHeldItem(), x + 4 * SLOT, y);
        } else {
            for (int i = 0; i < 27; i++) slot(ri, fr, inv[9 + i], x + (i % 9) * SLOT, y + (i / 9) * SLOT);
        }
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static void slot(RenderItem ri, FontRenderer fr, ItemStack st, int x, int y) {
        if (st == null) return;
        ri.renderItemAndEffectIntoGUI(st, x + 1, y + 1);
        ri.renderItemOverlayIntoGUI(fr, st, x + 1, y + 1, null);
    }
}
