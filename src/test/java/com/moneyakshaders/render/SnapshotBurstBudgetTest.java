package com.moneyakshaders.render;

public final class SnapshotBurstBudgetTest {
	public static void main(String[] args) {
		check(SnapshotBurstBudget.nanos(16, 0, 0) == 2_500_000, "startup");
		check(SnapshotBurstBudget.nanos(16, 34, 40) == 2_500_000, "integrated GPU");
		check(SnapshotBurstBudget.nanos(16, 6, 8) == 6_000_000, "dedicated GPU headroom");
		check(SnapshotBurstBudget.nanos(16, 30, 8) == 2_500_000, "latest hitch contracts immediately");
		check(SnapshotBurstBudget.nanos(16, 6, 30) == 2_500_000, "tail pressure");
		check(SnapshotBurstBudget.nanos(33, 5, 6) == 6_000_000, "absolute ceiling");
		check(SnapshotBurstBudget.nanos(16, Double.NaN, 6) == 2_500_000, "invalid sample");
		System.out.println("SnapshotBurstBudget tests PASS");
	}
	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
