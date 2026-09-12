package com.moneyakshaders.mixin.client;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.world.entity.ClientEntityManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientWorld.class)
public interface ClientWorldEntityAccess {
    @Accessor("entityManager")
    ClientEntityManager<Entity> moneyakshaders$entityManager();
}
