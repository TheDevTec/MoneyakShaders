package com.moneyakshaders.mixin.client;

import java.util.UUID;

import net.minecraft.client.render.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.moneyakshaders.client.OplAnimationLodState;

/** Carries an entity identity and one coherent LOD decision into deferred model commands. */
@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateAnimationLodMixin implements OplAnimationLodState {
	@Unique private int moneyakshaders$animationEntityId = -1;
	@Unique private UUID moneyakshaders$animationEntityUuid;
	@Unique private boolean moneyakshaders$animationLodEnabled;
	@Unique private boolean moneyakshaders$refreshPose = true;

	@Override
	public void moneyakshaders$setAnimationLod(int entityId, UUID entityUuid, boolean enabled, boolean refreshPose) {
		this.moneyakshaders$animationEntityId = entityId;
		this.moneyakshaders$animationEntityUuid = entityUuid;
		this.moneyakshaders$animationLodEnabled = enabled;
		this.moneyakshaders$refreshPose = refreshPose;
	}

	@Override public int moneyakshaders$entityId() { return this.moneyakshaders$animationEntityId; }
	@Override public UUID moneyakshaders$entityUuid() { return this.moneyakshaders$animationEntityUuid; }
	@Override public boolean moneyakshaders$animationLodEnabled() { return this.moneyakshaders$animationLodEnabled; }
	@Override public boolean moneyakshaders$refreshPose() { return this.moneyakshaders$refreshPose; }
}
