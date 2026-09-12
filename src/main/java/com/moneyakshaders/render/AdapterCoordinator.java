package com.moneyakshaders.render;

import java.util.Arrays;

/**
 * Spec §14 coordinator: holds registered {@link RenderAdapter}s and picks the highest-tier one
 * that supports a given source. NATIVE > CAPTURE > PROXY.
 *
 * <p>Registration is rare (mod/bootstrap lifecycle); extraction is potentially per rendered object.
 * Publish-on-write keeps that hot path lock-free and allocation-free. An optional third-party adapter
 * is never allowed to take down the world renderer: a bad support/extraction call simply declines and
 * lets the caller use its normal capture/proxy fallback.
 */
public final class AdapterCoordinator {
	private static final RenderAdapter[] EMPTY = new RenderAdapter[0];
	private static volatile RenderAdapter[] adapters = EMPTY;

	private AdapterCoordinator() {
	}

	public static synchronized void register(RenderAdapter adapter) {
		if (adapter == null) {
			return;
		}
		RenderAdapter[] current = adapters;
		for (RenderAdapter existing : current) {
			if (existing == adapter) {
				return;
			}
		}
		RenderAdapter[] next = Arrays.copyOf(current, current.length + 1);
		next[current.length] = adapter;
		adapters = next;
	}

	public static synchronized void unregister(RenderAdapter adapter) {
		RenderAdapter[] current = adapters;
		for (int i = 0; i < current.length; i++) {
			if (current[i] == adapter) {
				RenderAdapter[] next = new RenderAdapter[current.length - 1];
				System.arraycopy(current, 0, next, 0, i);
				System.arraycopy(current, i + 1, next, i, current.length - i - 1);
				adapters = next;
				return;
			}
		}
	}

	/** Resolve without taking a lock; called from render/extraction code. */
	public static RenderAdapter resolve(Object source) {
		RenderAdapter best = null;
		for (RenderAdapter a : adapters) {
			boolean supported;
			try {
				supported = a.supports(source);
			} catch (Throwable ignored) {
				continue; // optional mod adapter failed; normal renderer/capture remains authoritative
			}
			if (!supported) continue;
			if (best == null || a.tier().ordinal() < best.tier().ordinal()) {
				best = a;
			}
		}
		return best;
	}

	/**
	 * Extract a compatible immutable snapshot, rejecting stale resource data and faulty adapters.
	 * {@code null} deliberately means "use the generic capture/proxy path" rather than an error.
	 */
	public static RenderableInstance extract(Object source, float partialTicks) {
		RenderAdapter adapter = resolve(source);
		if (adapter == null) {
			return null;
		}
		try {
			RenderableInstance instance = adapter.extract(source, partialTicks);
			return instance != null && ResourceGeneration.isCurrent(instance.resourceGeneration) ? instance : null;
		} catch (Throwable ignored) {
			return null;
		}
	}

	public static int registeredCount() {
		return adapters.length;
	}
}
