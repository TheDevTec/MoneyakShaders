package com.moneyakshaders.render;

/** Conservative producer headroom; the configured time bucket and cell cap still apply. */
final class SnapshotBurstBudget {
	private SnapshotBurstBudget() { }

	static long nanos(double targetMs, double latestMs, double p95Ms) {
		// Unknown history and slow hardware retain the old startup guard. Spend only half
		// of measured spare time, with an absolute ceiling even when the user targets 30 FPS.
		if (!Double.isFinite(targetMs) || !Double.isFinite(latestMs) || !Double.isFinite(p95Ms)
				|| targetMs <= 0 || latestMs <= 0 || p95Ms <= 0) return 2_500_000L;
		double spareMs = Math.max(0.0, targetMs - Math.max(latestMs, p95Ms));
		return (long) (Math.min(6.0, 2.5 + spareMs * 0.5) * 1_000_000.0);
	}
}
