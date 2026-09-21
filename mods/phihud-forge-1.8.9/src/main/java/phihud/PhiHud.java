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
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.EnumSkyBlock;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.MouseEvent;
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
    private static final String[] IDS = HudConfig.IDS;

    final Minecraft mc = Minecraft.getMinecraft();
    private File cfgFile;
    private long cfgMtime;
    HudConfig cfg = new HudConfig();
    KeyBinding editorKey;
    private int titleFirstButtonY = 108; // vanilla: height / 4 + 48 at 240 px

    // TPS: wall-clock interval between S03PacketTimeUpdate packets (server sends one every 20 ticks).
    private volatile double tps = -1;
    private long lastTimeNanos, lastTotalTime;
    private final double[] samples = new double[5];
    private int sampleCount;

    // ---- per-tick caches (v4): strings are formatted here, never in the render path ----
    private int tick;
    private final String[] text = new String[IDS.length];
    private final int[] textW = new int[IDS.length];
    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm");
    private final Date clockDate = new Date();
    private long sessionStart;
    /** Attack/use edges counted from mouse events, binned per tick into a 20-tick (1 s) window. */
    private int lmbEdges, rmbEdges;
    private final byte[] lmb = new byte[20], rmb = new byte[20];
    private final double[] spd = new double[5];
    /** Effects list: one cached line per active effect (at least one line), plus the widest width. */
    private final String[] effectLines = new String[32];
    private int effectN = 1, effectW;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        cfgFile = new File(mc.mcDataDir, "config/phihud.json");
        reload();
        editorKey = new KeyBinding("key.phihud.editor", Keyboard.KEY_H, "key.categories.phihud");
        ClientRegistry.registerKeyBinding(editorKey);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void reload() {
        cfgMtime = cfgFile.lastModified(); // 0 when missing
        cfg = HudConfig.load(cfgFile);
        dirty = true;
    }

    /** Writes the config; remembers the mtime so the poll does not reload our own write. */
    void save() {
        cfg.save(cfgFile);
        cfgMtime = cfgFile.lastModified();
        dirty = true;
    }

    void openEditor() {
        mc.displayGuiScreen(new EditorScreen(this));
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        tick++;
        sample();
        boolean all = mc.currentScreen instanceof EditorScreen;
        HudConfig c = cfg;
        for (int i = 0; i < IDS.length; i++) {
            String id = IDS[i];
            HudConfig.Widget w = c.widgets.get(id);
            if (w == null || (!w.enabled && !all)) continue;
            boolean fast = "fps".equals(id) || "cps".equals(id) || "speed".equals(id);
            if (!fast && (tick & 1) != 0) continue;
            if ("effects".equals(id)) updateEffects();
            else setText(i, format(id));
        }
        if (tick % 40 == 0) { // 2 s poll, one stat call
            long m = cfgFile.lastModified();
            if (m != cfgMtime) reload();
        }
    }

    private void setText(int i, String s) {
        if (s == null || s.equals(text[i])) return;
        text[i] = s;
        int w = mc.fontRendererObj.getStringWidth(s);
        if (w != textW[i]) { textW[i] = w; dirty = true; }
    }

    /** CPS bins and speed ring, once per client tick. */
    private void sample() {
        lmb[tick % 20] = (byte) lmbEdges;
        rmb[tick % 20] = (byte) rmbEdges;
        lmbEdges = rmbEdges = 0;
        EntityPlayer p = mc.thePlayer;
        double dx = p == null ? 0 : p.posX - p.prevPosX, dz = p == null ? 0 : p.posZ - p.prevPosZ;
        spd[tick % 5] = Math.sqrt(dx * dx + dz * dz);
    }

    @SubscribeEvent
    public void onMouse(MouseEvent e) {
        if (!e.buttonstate || e.button < 0) return;
        int code = e.button - 100;
        if (code == mc.gameSettings.keyBindAttack.getKeyCode()) lmbEdges++;
        else if (code == mc.gameSettings.keyBindUseItem.getKeyCode()) rmbEdges++;
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

    // ---- TPS / session ----

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent e) {
        resetTps();
        sessionStart = System.currentTimeMillis();
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
        sessionStart = 0;
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

    // ---- widget strings (tick time) ----

    private String format(String id) {
        EntityPlayer p = mc.thePlayer;
        if ("fps".equals(id)) return "FPS: " + Minecraft.getDebugFPS();
        if ("tps".equals(id)) { double v = tps; return v < 0 ? "TPS: --" : String.format("TPS: %.1f", v); }
        if ("coords".equals(id)) return p == null ? "XYZ: --" : String.format("XYZ: %.1f / %.1f / %.1f", p.posX, p.posY, p.posZ);
        if ("direction".equals(id)) {
            return p == null ? "Facing: --" : String.format("Facing: %s (%.1f / %.1f)", facing(p.getHorizontalFacing()),
                    MathHelper.wrapAngleTo180_float(p.rotationYaw), MathHelper.wrapAngleTo180_float(p.rotationPitch));
        }
        if ("ping".equals(id)) {
            NetworkPlayerInfo info = p == null || mc.isSingleplayer() || mc.getNetHandler() == null ? null
                    : mc.getNetHandler().getPlayerInfo(p.getUniqueID());
            return info == null ? "Ping: --" : "Ping: " + info.getResponseTime() + " ms";
        }
        if ("memory".equals(id)) {
            Runtime r = Runtime.getRuntime();
            long max = r.maxMemory() >> 20, used = (r.totalMemory() - r.freeMemory()) >> 20;
            return String.format("Mem: %d / %d MB (%d%%)", used, max, max == 0 ? 0 : used * 100 / max);
        }
        if ("clock".equals(id)) { clockDate.setTime(System.currentTimeMillis()); return clockFmt.format(clockDate); }
        if ("cps".equals(id)) {
            int l = 0, r = 0;
            for (int i = 0; i < 20; i++) { l += lmb[i]; r += rmb[i]; }
            return "CPS: " + l + " | " + r;
        }
        if ("speed".equals(id)) {
            double s = 0;
            for (int i = 0; i < 5; i++) s += spd[i];
            return String.format("Speed: %.1f b/s", s * 4); // 5-tick mean * 20 ticks/s
        }
        if ("biome".equals(id)) return p == null ? "Biome: --" : "Biome: " + mc.theWorld.getBiomeGenForCoords(new BlockPos(p)).biomeName;
        if ("gametime".equals(id)) {
            if (p == null) return "Day --";
            long t = mc.theWorld.getWorldTime(), d = t % 24000;
            return String.format("Day %d, %02d:%02d", t / 24000, (d / 1000 + 6) % 24, d % 1000 * 60 / 1000);
        }
        if ("light".equals(id)) {
            if (p == null) return "Light: --";
            BlockPos pos = new BlockPos(p);
            return "Light: " + mc.theWorld.getLightFromNeighbors(pos) + " (sky " + mc.theWorld.getLightFor(EnumSkyBlock.SKY, pos) + ")";
        }
        if ("target".equals(id)) {
            MovingObjectPosition m = mc.objectMouseOver;
            if (p == null || m == null || m.typeOfHit == MovingObjectPosition.MovingObjectType.MISS) return "Looking at: --";
            if (m.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) return "Looking at: " + (m.entityHit == null ? "--" : m.entityHit.getName());
            return "Looking at: " + mc.theWorld.getBlockState(m.getBlockPos()).getBlock().getLocalizedName();
        }
        if ("server".equals(id)) { ServerData s = mc.getCurrentServerData(); return s == null || mc.isSingleplayer() ? "Singleplayer" : "Server: " + s.serverIP; }
        if ("session".equals(id)) {
            if (sessionStart == 0) return "Session: --";
            long m = (System.currentTimeMillis() - sessionStart) / 60000;
            return m < 60 ? "Session: " + m + "m" : "Session: " + m / 60 + "h " + m % 60 + "m";
        }
        if ("hunger".equals(id)) return p == null ? "Food: --" : String.format("Food: %d/20  Sat: %.1f", p.getFoodStats().getFoodLevel(), p.getFoodStats().getSaturationLevel());
        if ("health".equals(id)) return p == null ? "HP: --" : String.format("HP: %.1f/%.0f  Armor: %d", p.getHealth(), p.getMaxHealth(), p.getTotalArmorValue());
        return null; // non-text widget
    }

    private void updateEffects() {
        int n = 0, w = 0;
        FontRenderer fr = mc.fontRendererObj;
        if (mc.thePlayer != null) {
            for (PotionEffect pe : mc.thePlayer.getActivePotionEffects()) {
                if (n == effectLines.length) break;
                int id = pe.getPotionID();
                Potion pot = id >= 0 && id < Potion.potionTypes.length ? Potion.potionTypes[id] : null;
                if (pot == null) continue;
                int amp = pe.getAmplifier();
                String lvl = amp == 0 ? "" : " " + (amp <= 5 ? I18n.format("potion.potency." + amp) : String.valueOf(amp + 1));
                String line = I18n.format(pot.getName()) + lvl + " " + Potion.getDurationString(pe);
                if (!line.equals(effectLines[n])) effectLines[n] = line;
                w = Math.max(w, fr.getStringWidth(line));
                n++;
            }
        }
        if (n == 0) { effectLines[0] = "Effects: --"; n = 1; w = fr.getStringWidth(effectLines[0]); }
        if (n != effectN || w != effectW) { effectN = n; effectW = w; dirty = true; }
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

    // ---- layout ----

    static final int PAD = 2, LINE = 10, SLOT = 18;
    /** Keystrokes mini keyboard: key size, gap, mouse row height, space bar height. */
    static final int KEY = 17, GAP = 1, MOUSE_H = 12, SPACE_H = 6;
    static final int KB_W = 3 * KEY + 2 * GAP, KB_H = 2 * (KEY + GAP) + MOUSE_H + GAP + SPACE_H;

    /** w/h are the natural (unscaled) size; scale/color/bg are the resolved per-widget values. */
    static final class Item {
        final String id;
        /** Index into IDS for text widgets (their string lives in the tick cache); -1 for icon/box widgets. */
        final int idx;
        final int w, h, order, color;
        final boolean enabled, bg, off;
        final float scale;
        Item(String id, int idx, int w, int h, HudConfig.Widget wd, HudConfig c, boolean all) {
            this.id = id; this.idx = idx; this.w = w; this.h = h; this.order = wd.order; this.enabled = wd.enabled;
            this.off = all && !wd.enabled;
            this.scale = wd.scale != null ? wd.scale : c.scale;
            this.color = wd.color != null ? HudConfig.argb(wd.color) : c.argb();
            this.bg = wd.background != null ? wd.background : c.background;
        }
        boolean text() { return idx >= 0; }
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

    private static final String OFF = " (off)";
    private static final Comparator<Item> BY_ORDER = new Comparator<Item>() {
        public int compare(Item a, Item b) { return a.order - b.order; }
    };
    /** Cached layout; rebuilt when dirty (config change, string width change) or the screen/mode differs. */
    private List<Group> groups = Collections.emptyList();
    private int lW, lH;
    private boolean lAll, dirty = true;

    /** Lays out widgets for a W x H (GUI px) screen. all = include disabled widgets (editor). */
    List<Group> layout(HudConfig c, int W, int H, boolean all) {
        if (!dirty && W == lW && H == lH && all == lAll) return groups;
        dirty = false; lW = W; lH = H; lAll = all;
        List<Group> out = new ArrayList<Group>();
        Map<String, List<Item>> stacks = new LinkedHashMap<String, List<Item>>();
        for (int i = 0; i < IDS.length; i++) {
            HudConfig.Widget w = c.widgets.get(IDS[i]);
            if (w == null || (!w.enabled && !all)) continue;
            Item it = make(IDS[i], i, w, c, all);
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
            Collections.sort(items, BY_ORDER);
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
        return groups = out;
    }

    private Item make(String id, int idx, HudConfig.Widget w, HudConfig c, boolean all) {
        if ("armor".equals(id)) return new Item(id, -1, 5 * SLOT, SLOT, w, c, all);
        if ("inventory".equals(id)) return new Item(id, -1, 9 * SLOT, 3 * SLOT, w, c, all);
        if ("keystrokes".equals(id)) return new Item(id, -1, KB_W, KB_H, w, c, all);
        if ("effects".equals(id)) return new Item(id, -1, effectW, effectN * LINE, w, c, all);
        int tw = textW[idx];
        if (all && !w.enabled) tw += mc.fontRendererObj.getStringWidth(OFF);
        return new Item(id, idx, tw, LINE, w, c, all);
    }

    // ---- render ----

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL) return;
        HudConfig c = cfg;
        if (!c.enabled || mc.currentScreen != null || mc.gameSettings.showDebugInfo || mc.thePlayer == null) return;

        ScaledResolution res = e.resolution;
        List<Group> gs = layout(c, res.getScaledWidth(), res.getScaledHeight(), false);
        for (int i = 0, n = gs.size(); i < n; i++) { // indexed: no iterator allocation per frame
            Group g = gs.get(i);
            List<Box> bs = g.boxes;
            for (int j = 0, m = bs.size(); j < m; j++) drawBox(g, bs.get(j), c);
        }
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

    /** Draws the item's content at the origin (caller translates/scales). */
    void draw(Item it, HudConfig c) {
        FontRenderer fr = mc.fontRendererObj;
        if (it.text()) {
            String s = text[it.idx];
            if (s != null) fr.drawString(s, 0, 1, it.color, c.shadow);
            if (it.off) fr.drawString(OFF, textW[it.idx], 1, it.color, c.shadow);
            return;
        }
        if ("effects".equals(it.id)) {
            for (int i = 0; i < effectN; i++) fr.drawString(effectLines[i], 0, i * LINE + 1, it.color, c.shadow);
            return;
        }
        if ("keystrokes".equals(it.id)) { keyboard(it.color); return; }
        if (mc.thePlayer == null) return; // title-screen editor: empty slots
        ItemStack[] inv = mc.thePlayer.inventory.mainInventory;
        ItemStack[] armor = mc.thePlayer.inventory.armorInventory;
        RenderItem ri = mc.getRenderItem();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        RenderHelper.enableGUIStandardItemLighting();
        if ("armor".equals(it.id)) {
            // helmet, chest, legs, boots, held item
            for (int i = 0; i < 4; i++) slot(ri, fr, armor[3 - i], i * SLOT, 0);
            slot(ri, fr, mc.thePlayer.getHeldItem(), 4 * SLOT, 0);
        } else {
            for (int i = 0; i < 27; i++) slot(ri, fr, inv[9 + i], (i % 9) * SLOT, (i / 9) * SLOT);
        }
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private void keyboard(int color) {
        GameSettings gs = mc.gameSettings;
        int row1 = KEY + GAP, row2 = 2 * (KEY + GAP), row3 = row2 + MOUSE_H + GAP, half = (KB_W - GAP) / 2;
        key(KEY + GAP, 0, KEY, KEY, "W", gs.keyBindForward.isKeyDown(), color);
        key(0, row1, KEY, KEY, "A", gs.keyBindLeft.isKeyDown(), color);
        key(KEY + GAP, row1, KEY, KEY, "S", gs.keyBindBack.isKeyDown(), color);
        key(2 * (KEY + GAP), row1, KEY, KEY, "D", gs.keyBindRight.isKeyDown(), color);
        key(0, row2, half, MOUSE_H, "LMB", gs.keyBindAttack.isKeyDown(), color);
        key(half + GAP, row2, half, MOUSE_H, "RMB", gs.keyBindUseItem.isKeyDown(), color);
        key(0, row3, KB_W, SPACE_H, null, gs.keyBindJump.isKeyDown(), color);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private void key(int x, int y, int w, int h, String label, boolean down, int color) {
        Gui.drawRect(x, y, x + w, y + h, down ? color : 0x80000000);
        if (label == null) return;
        FontRenderer fr = mc.fontRendererObj;
        fr.drawString(label, x + (w - fr.getStringWidth(label)) / 2, y + (h - 7) / 2, down ? 0xFF202020 : color);
    }

    private static void slot(RenderItem ri, FontRenderer fr, ItemStack st, int x, int y) {
        if (st == null) return;
        ri.renderItemAndEffectIntoGUI(st, x + 1, y + 1);
        ri.renderItemOverlayIntoGUI(fr, st, x + 1, y + 1, null);
    }
}
