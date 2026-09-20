package dev.phi.phihud;

/** Client-side TPS estimate: server ticks between consecutive time packets / wall-clock seconds between them. */
public final class Tps {
	private static long lastNanos;
	private static long lastGameTime;
	private static double tps = -1;

	/** Called from the mixin after the client handled a ClientboundSetTimePacket. */
	public static void onTimePacket(long gameTime) {
		long now = System.nanoTime();
		if (lastNanos != 0 && gameTime > lastGameTime) {
			double sec = (now - lastNanos) / 1e9;
			if (sec > 0.05) {
				double sample = Math.min(20.0, (gameTime - lastGameTime) / sec);
				tps = tps < 0 ? sample : tps * 0.7 + sample * 0.3; // light smoothing against network jitter
			}
		}
		lastNanos = now;
		lastGameTime = gameTime;
	}

	public static void reset() {
		lastNanos = 0;
		lastGameTime = 0;
		tps = -1;
	}

	/** -1 when no measurement yet. */
	public static double get() {
		return tps;
	}
}
