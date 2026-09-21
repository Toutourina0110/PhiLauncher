package dev.phi.phihud;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/** Left/right clicks per second over a rolling 1 s window, fed by KeyMappingMixin on every key press. */
public final class Cps {
	// ponytail: 128-slot ring per button; presses beyond 128/s fall out of the window early
	private static final int N = 128;
	private static final long[] left = new long[N], right = new long[N];
	private static int li, ri;

	/** Called on every key/mouse press; only the attack / use bindings are counted. */
	public static void onPress(InputConstants.Key key) {
		Options o = Minecraft.getInstance().options;
		if (o == null) return;
		String name = key.getName();
		if (name.equals(o.keyAttack.saveString())) left[li++ & (N - 1)] = System.currentTimeMillis();
		else if (name.equals(o.keyUse.saveString())) right[ri++ & (N - 1)] = System.currentTimeMillis();
	}

	public static int left() { return count(left); }
	public static int right() { return count(right); }

	private static int count(long[] ring) {
		long cutoff = System.currentTimeMillis() - 1000;
		int n = 0;
		for (int i = 0; i < N; i++) if (ring[i] > cutoff) n++;
		return n;
	}
}
