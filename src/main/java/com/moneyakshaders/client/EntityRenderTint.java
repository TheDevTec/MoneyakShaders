package com.moneyakshaders.client;

import java.util.Arrays;
import java.util.IdentityHashMap;

import com.moneyakshaders.render.ExperimentalSectionRender;

import net.minecraft.client.MinecraftClient;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.command.LayeredCustomCommandRenderer;
import net.minecraft.client.particle.BillboardParticleSubmittable;
import net.minecraft.client.texture.TextureManager;
import com.mojang.blaze3d.systems.RenderPass;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;

/**
 * Render-thread scope carrying one RGB light multiplier through Minecraft's deferred entity command
 * queue. Commands copy the colour while the entity renderer is submitting them; custom vertex and
 * item paths retain it explicitly because they execute only later in the frame.
 */
public final class EntityRenderTint {
	private static int[] tintStack = new int[8];
	private static int depth;
	private static final IdentityHashMap<int[], Integer> ITEM_TINTS = new IdentityHashMap<>();
	private static final IdentityHashMap<EntityRenderState, Entity> STATE_ENTITIES = new IdentityHashMap<>();

	private EntityRenderTint() {
	}

	/** Custom command that has already folded the current tint into its own vertex colors. */
	public interface PreTintedCustom extends OrderedRenderCommandQueue.Custom {
	}

	/** Layered command retaining the submitting entity/block-entity tint until its deferred execution. */
	public interface PreTintedLayeredCustom extends OrderedRenderCommandQueue.LayeredCustom {
	}

	public static void associate(EntityRenderState state, Entity entity) {
		if (STATE_ENTITIES.size() > 16_384) STATE_ENTITIES.clear();
		STATE_ENTITIES.put(state, entity);
	}

	public static void begin(EntityRenderState state, double x, double y, double z) {
		Entity entity = STATE_ENTITIES.remove(state);
		// A dropped torch/glowstone/etc. is itself an emitter. Multiplying its authored texture by
		// nearby emitter hues made piles flip from yellow to white/green as items merged or separated.
		push(DynamicLightSources.isDroppedLight(entity) ? -1 : ExperimentalSectionRender.lightTintAt(x, y, z));
	}

	/** RGB scope for block entities and RP renderers using the standard deferred command queue. */
	public static void beginAt(double x, double y, double z) {
		push(ExperimentalSectionRender.lightTintAt(x, y, z));
	}

	/** Block-entity models are submitted after the terrain post pass. Fold the local underwater
	 * medium into their vertex tint so chests/signs do not look like dry air pockets. */
	public static void beginBlockEntityAt(double x, double y, double z) {
		int tint = ExperimentalSectionRender.lightTintAt(x, y, z);
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && client.gameRenderer != null
				&& client.gameRenderer.getCamera().getSubmersionType() == net.minecraft.block.enums.CameraSubmersionType.WATER) {
			tint = multiply(tint, 0xFF5A92A6);
		}
		push(tint);
	}

	private static void push(int tint) {
		if (depth == tintStack.length) tintStack = Arrays.copyOf(tintStack, depth << 1);
		tintStack[depth++] = tint;
	}

	public static void end() {
		if (depth > 0) depth--;
	}

	public static int current() {
		return depth == 0 ? -1 : tintStack[depth - 1];
	}

	public static int multiplyCurrent(int color) {
		return multiply(color, current());
	}

	public static int multiply(int color, int tint) {
		if (tint == -1) return color;
		int a = color >>> 24;
		int r = ((color >>> 16 & 255) * (tint >>> 16 & 255) + 127) / 255;
		int g = ((color >>> 8 & 255) * (tint >>> 8 & 255) + 127) / 255;
		int b = ((color & 255) * (tint & 255) + 127) / 255;
		return a << 24 | r << 16 | g << 8 | b;
	}

	public static float redScale() {
		int tint = current();
		return tint == -1 ? 1f : (tint >>> 16 & 255) / 255f;
	}

	public static float greenScale() {
		int tint = current();
		return tint == -1 ? 1f : (tint >>> 8 & 255) / 255f;
	}

	public static float blueScale() {
		int tint = current();
		return tint == -1 ? 1f : (tint & 255) / 255f;
	}

	/** Clone so an item command owns a stable identity without mutating RP/model-owned tint arrays. */
	public static int[] prepareItemTints(int[] original) {
		int tint = current();
		if (tint == -1) return original;
		int[] copy = original == null ? new int[0] : original.clone();
		ITEM_TINTS.put(copy, tint);
		return copy;
	}

	/** Consumes the marker on the normal item pass; the following outline pass intentionally stays neutral. */
	public static VertexConsumerProvider tintItemProvider(int[] tints, VertexConsumerProvider provider) {
		Integer tint = ITEM_TINTS.remove(tints);
		return tint == null || tint == -1 ? provider : wrapProvider(provider, tint);
	}

	public static OrderedRenderCommandQueue.Custom wrapCustom(OrderedRenderCommandQueue.Custom custom) {
		int tint = current();
		if (tint == -1) return custom;
		return (matrices, vertices) -> custom.render(matrices, new TintedVertexConsumer(vertices, tint));
	}

	public static OrderedRenderCommandQueue.LayeredCustom wrapLayeredCustom(
			OrderedRenderCommandQueue.LayeredCustom custom) {
		int tint = current();
		if (tint == -1 || custom instanceof PreTintedLayeredCustom) return custom;
		return new PreTintedLayeredCustom() {
			@Override
			public BillboardParticleSubmittable.Buffers submit(LayeredCustomCommandRenderer.VerticesCache cache) {
				push(tint);
				try {
					return custom.submit(cache);
				} finally {
					end();
				}
			}

			@Override
			public void render(BillboardParticleSubmittable.Buffers buffers,
					LayeredCustomCommandRenderer.VerticesCache cache, RenderPass pass,
					TextureManager textures, boolean translucent) {
				push(tint);
				try {
					custom.render(buffers, cache, pass, textures, translucent);
				} finally {
					end();
				}
			}
		};
	}

	private static VertexConsumerProvider wrapProvider(VertexConsumerProvider provider, int tint) {
		return layer -> isGlintLayer(layer)
				? provider.getBuffer(layer)
				: new TintedVertexConsumer(provider.getBuffer(layer), tint);
	}

	/**
	 * Glint is a separate, animated additive pass. Colouring it with a spatial light multiplier makes
	 * that overlay pulse independently from the item/armor underneath as limbs move between samples.
	 * The opaque/cutout base still receives the coloured light, so only the authored glint stays neutral.
	 */
	public static boolean isGlintLayer(RenderLayer layer) {
		return layer == RenderLayers.armorEntityGlint()
				|| layer == RenderLayers.entityGlint()
				|| layer == RenderLayers.glint()
				|| layer == RenderLayers.glintTranslucent();
	}

	private static final class TintedVertexConsumer implements VertexConsumer {
		private final VertexConsumer delegate;
		private final int tint;

		private TintedVertexConsumer(VertexConsumer delegate, int tint) {
			this.delegate = delegate;
			this.tint = tint;
		}

		@Override public VertexConsumer vertex(float x, float y, float z) {
			delegate.vertex(x, y, z); return this;
		}

		@Override public VertexConsumer color(int red, int green, int blue, int alpha) {
			delegate.color(red * (tint >>> 16 & 255) / 255,
					green * (tint >>> 8 & 255) / 255, blue * (tint & 255) / 255, alpha);
			return this;
		}

		@Override public VertexConsumer color(int argb) {
			delegate.color(multiply(argb, tint)); return this;
		}

		@Override public VertexConsumer texture(float u, float v) {
			delegate.texture(u, v); return this;
		}

		@Override public VertexConsumer overlay(int u, int v) {
			delegate.overlay(u, v); return this;
		}

		@Override public VertexConsumer light(int u, int v) {
			delegate.light(u, v); return this;
		}

		@Override public VertexConsumer normal(float x, float y, float z) {
			delegate.normal(x, y, z); return this;
		}

		@Override public VertexConsumer lineWidth(float width) {
			delegate.lineWidth(width); return this;
		}
	}
}
