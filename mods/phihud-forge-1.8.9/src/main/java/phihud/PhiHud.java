package phihud;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Mod(modid = "phihud", name = "Phi HUD", version = "1.0.0", clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class PhiHud {
    private final Minecraft mc = Minecraft.getMinecraft();
    private File cfgFile;
    private long cfgMtime = -1, nextPoll;
    private HudConfig cfg = new HudConfig();

    // TPS: wall-clock interval between S03PacketTimeUpdate packets (server sends one every 20 ticks).
    private volatile double tps = -1;
    private long lastTimeNanos, lastTotalTime;
    private final double[] samples = new double[5];
    private int sampleCount;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        cfgFile = new File(mc.mcDataDir, "config/phihud.json");
        reload();
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void reload() {
        cfgMtime = cfgFile.isFile() ? cfgFile.lastModified() : -1;
        cfg = HudConfig.load(cfgFile);
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

    // ---- rendering ----

    private static final int PAD = 2, LINE = 10, SLOT = 18;

    private static final class Item {
        final String id, text;
        final int w, h, order;
        Item(String id, String text, int w, int h, int order) { this.id = id; this.text = text; this.w = w; this.h = h; this.order = order; }
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL) return;
        HudConfig c = cfg;
        if (!c.enabled || mc.currentScreen != null || mc.gameSettings.showDebugInfo || mc.thePlayer == null) return;

        ScaledResolution res = e.resolution;
        float s = c.scale;
        int W = (int) (res.getScaledWidth() / s), H = (int) (res.getScaledHeight() / s);

        GlStateManager.pushMatrix();
        GlStateManager.scale(s, s, 1f);
        for (String anchor : new String[]{"top-left", "top-right", "bottom-left", "bottom-right"}) {
            List<Item> items = new ArrayList<Item>();
            for (Map.Entry<String, HudConfig.Widget> w : c.widgets.entrySet()) {
                if (w.getValue().enabled && anchor.equals(w.getValue().anchor)) {
                    Item it = make(w.getKey(), w.getValue().order);
                    if (it != null) items.add(it);
                }
            }
            if (items.isEmpty()) continue;
            boolean top = anchor.startsWith("top"), left = anchor.endsWith("left");
            Collections.sort(items, new Comparator<Item>() {
                public int compare(Item a, Item b) { return a.order - b.order; }
            });
            if (!top) Collections.reverse(items); // bottom anchors stack upward: order 0 nearest the edge
            int bw = 0, bh = 0;
            for (Item it : items) { bw = Math.max(bw, it.w); bh += it.h; }
            bw += 2 * PAD; bh += 2 * PAD;
            int x = left ? c.margin : W - c.margin - bw;
            int y = top ? c.margin : H - c.margin - bh;
            if (c.background) {
                int a = MathHelper.clamp_int((int) (c.backgroundOpacity * 255), 0, 255);
                Gui.drawRect(x, y, x + bw, y + bh, a << 24);
                GlStateManager.color(1f, 1f, 1f, 1f);
            }
            int cy = y + PAD;
            for (Item it : items) {
                draw(it, x + PAD, cy, c);
                cy += it.h;
            }
        }
        GlStateManager.popMatrix();
    }

    private Item make(String id, int order) {
        String t;
        if ("fps".equals(id)) t = "FPS: " + Minecraft.getDebugFPS();
        else if ("tps".equals(id)) { double v = tps; t = v < 0 ? "TPS: --" : String.format("TPS: %.1f", v); }
        else if ("coords".equals(id)) t = String.format("XYZ: %.1f / %.1f / %.1f", mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        else if ("direction".equals(id)) {
            EntityPlayer p = mc.thePlayer;
            t = String.format("Facing: %s (%.1f / %.1f)", facing(p.getHorizontalFacing()),
                    MathHelper.wrapAngleTo180_float(p.rotationYaw), MathHelper.wrapAngleTo180_float(p.rotationPitch));
        }
        else if ("ping".equals(id)) {
            NetworkPlayerInfo info = mc.isSingleplayer() || mc.getNetHandler() == null ? null
                    : mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
            t = info == null ? "Ping: --" : "Ping: " + info.getResponseTime() + " ms";
        }
        else if ("memory".equals(id)) {
            Runtime r = Runtime.getRuntime();
            long max = r.maxMemory() >> 20, used = (r.totalMemory() - r.freeMemory()) >> 20;
            t = String.format("Mem: %d / %d MB (%d%%)", used, max, max == 0 ? 0 : used * 100 / max);
        }
        else if ("clock".equals(id)) t = new SimpleDateFormat("HH:mm").format(new Date());
        else if ("armor".equals(id)) return new Item(id, null, 5 * SLOT, SLOT, order);
        else if ("inventory".equals(id)) return new Item(id, null, 9 * SLOT, 3 * SLOT, order);
        else return null;
        return new Item(id, t, mc.fontRendererObj.getStringWidth(t), LINE, order);
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

    private void draw(Item it, int x, int y, HudConfig c) {
        if (it.text != null) {
            mc.fontRendererObj.drawString(it.text, x, y + 1, c.argb(), c.shadow);
            return;
        }
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
