
package com.moneyakshaders.mixin.client;

import java.util.List;

import net.minecraft.util.math.Vec3d;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeFunction;
import net.minecraft.world.attribute.WeightedAttributeList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft 1.21.11 evaluates positional environment attributes for every random display sample.
 * Its enhanced-for loop allocates an iterator from the immutable modification list each time; during
 * chunk flight JFR attributed roughly 60% of all sampled allocation pressure to that one iterator.
 * Indexed access is semantically identical for this immutable list and creates no per-sample object.
 */
@Mixin(targets = "net.minecraft.world.attribute.WorldEnvironmentAttributeAccess$Entry")
abstract class WorldEnvironmentAttributeEntryMixin {
	@Shadow @Final private EnvironmentAttribute<Object> attribute;
	@Shadow @Final Object defaultValue;
	@Shadow @Final private List<EnvironmentAttributeFunction<Object>> modifications;
	@Shadow private int age;

	@Inject(method = "computeAt", at = @At("HEAD"), cancellable = true)
	private void moneyakshaders$computeAtWithoutIterator(Vec3d pos, WeightedAttributeList weightedAttributes,
			CallbackInfoReturnable<Object> cir) {
		Object value = this.defaultValue;
		for (int i = 0, size = this.modifications.size(); i < size; i++) {
			EnvironmentAttributeFunction<Object> modification = this.modifications.get(i);
			if (modification instanceof EnvironmentAttributeFunction.Constant<Object> constant) {
				value = constant.applyConstant(value);
			} else if (modification instanceof EnvironmentAttributeFunction.TimeBased<Object> timeBased) {
				value = timeBased.applyTimeBased(value, this.age);
			} else if (modification instanceof EnvironmentAttributeFunction.Positional<Object> positional) {
				value = positional.applyPositional(value, pos, weightedAttributes);
			}
		}
		cir.setReturnValue(this.attribute.clamp(value));
	}

	@Inject(method = "compute", at = @At("HEAD"), cancellable = true)
	private void moneyakshaders$computeWithoutIterator(CallbackInfoReturnable<Object> cir) {
		Object value = this.defaultValue;
		for (int i = 0, size = this.modifications.size(); i < size; i++) {
			EnvironmentAttributeFunction<Object> modification = this.modifications.get(i);
			if (modification instanceof EnvironmentAttributeFunction.Constant<Object> constant) {
				value = constant.applyConstant(value);
			} else if (modification instanceof EnvironmentAttributeFunction.TimeBased<Object> timeBased) {
				value = timeBased.applyTimeBased(value, this.age);
			}
		}
		cir.setReturnValue(this.attribute.clamp(value));
	}
}
