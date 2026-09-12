package com.moneyakshaders.client;

import java.util.UUID;

/** Per-render-state decision consumed later by the deferred model command queue. */
public interface OplAnimationLodState {
	void moneyakshaders$setAnimationLod(int entityId, UUID entityUuid, boolean enabled, boolean refreshPose);

	int moneyakshaders$entityId();

	UUID moneyakshaders$entityUuid();

	boolean moneyakshaders$animationLodEnabled();

	boolean moneyakshaders$refreshPose();
}
