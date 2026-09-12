package com.moneyakshaders.client;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.render.ResourceGeneration;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/**
 * Creates real, bounded texture-resolution LOD variants for high-resolution entity/equipment
 * textures. Texture loading stays off the render thread; the original remains in use until the
 * derived texture is atomically registered, so an unavailable or dynamic texture always falls
 * back safely.
 */
public final class EntityTextureLodCache {
	private static final ConcurrentHashMap<Key, Identifier> READY = new ConcurrentHashMap<>();
	private static final Set<Key> IN_FLIGHT = ConcurrentHashMap.newKeySet();
	// Dynamic skin/map textures and ordinary 16/64px assets cannot produce a useful pack-backed
	// LOD. Remember that outcome for this resource generation so a crowded distant entity scene
	// does not continuously enqueue doomed background image reads every frame.
	private static final Set<Key> UNSUPPORTED = ConcurrentHashMap.newKeySet();
	/** One reusable scope per render thread; avoids boxing a Double for every living entity. */
	private static final ThreadLocal<DistanceScope> ENTITY_DISTANCE = ThreadLocal.withInitial(DistanceScope::new);

	private EntityTextureLodCache() { }

	public static void beginEntityScope(double distanceSq) {
		DistanceScope scope = ENTITY_DISTANCE.get();
		scope.distanceSq = distanceSq;
		scope.active = true;
	}
	public static void endEntityScope() { ENTITY_DISTANCE.get().active = false; }

	public static Identifier resolveScoped(Identifier source) {
		DistanceScope scope = ENTITY_DISTANCE.get();
		return scope.active ? resolve(source, scope.distanceSq) : source;
	}

	public static Identifier resolve(Identifier source, double distanceSq) {
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		if (!cfg.entityTextureDistanceLod || source == null) return source;
		int level = level(distanceSq, cfg);
		if (level == 0) return source;
		Key key = new Key(source, level, ResourceGeneration.current());
		Identifier ready = READY.get(key);
		if (ready != null) return ready;
		if (UNSUPPORTED.contains(key)) return source;
		if (IN_FLIGHT.add(key)) schedule(key);
		return source;
	}

	private static int level(double distanceSq, MoneyakShadersConfig cfg) {
		double near = Math.max(8, cfg.entityTextureLodNear);
		double far = Math.max(near + 1, cfg.entityTextureLodFar);
		if (distanceSq < near * near) return 0;
		if (distanceSq < ((near + far) * 0.5) * ((near + far) * 0.5)) return 1;
		return distanceSq < far * far ? 2 : 3;
	}

	private static void schedule(Key key) {
		ChunkMeshExecutor.executeBackground(() -> {
			NativeImage target = null;
			try {
				MinecraftClient client = MinecraftClient.getInstance();
				ResourceManager resources = client == null ? null : client.getResourceManager();
				if (resources == null) {
					UNSUPPORTED.add(key);
					return;
				}
				var resource = resources.getResource(key.source);
				if (resource.isEmpty()) {
					UNSUPPORTED.add(key); // skins/dynamic textures have no pack resource
					return;
				}
				try (InputStream input = resource.get().getInputStream(); NativeImage original = NativeImage.read(input)) {
					int width = Math.max(1, original.getWidth() >> key.level);
					int height = Math.max(1, original.getHeight() >> key.level);
					// Do not duplicate ordinary vanilla-size textures; hardware mip LOD handles those already.
					if (original.getWidth() <= 128 && original.getHeight() <= 128) {
						UNSUPPORTED.add(key);
						return;
					}
					target = new NativeImage(width, height, false);
					original.resizeSubRectTo(0, 0, original.getWidth(), original.getHeight(), target);
				}
				NativeImage completed = target;
				target = null;
				MinecraftClient.getInstance().execute(() -> install(key, completed));
			} catch (Exception ignored) {
				// Invalid or non-PNG pack texture: retain the original render-layer texture.
				UNSUPPORTED.add(key);
			} finally {
				if (target != null) target.close();
				IN_FLIGHT.remove(key);
			}
		});
	}

	private static void install(Key key, NativeImage image) {
		if (!ResourceGeneration.isCurrent(key.generation)) {
			image.close();
			return;
		}
		Identifier id = Identifier.of("moneyakshaders", "entity_lod/" + Integer.toUnsignedString(key.source.hashCode(), 36) + "/" + key.level);
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			image.close();
			return;
		}
		client.getTextureManager().registerTexture(id,
				new NativeImageBackedTexture(() -> "moneyakshaders_entity_lod", image));
		READY.put(key, id);
	}

	public static void clear() {
		// READY only owns generated client textures. Remove the registrations as well as the lookup
		// entries: resource reloads otherwise keep every old high-resolution LOD texture resident in
		// VRAM until disconnect, even though a new generation can never select it again.
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && !READY.isEmpty()) {
			Set<Identifier> ids = new HashSet<>(READY.values());
			for (Identifier id : ids) {
				client.getTextureManager().destroyTexture(id);
			}
		}
		READY.clear();
		IN_FLIGHT.clear();
		UNSUPPORTED.clear();
	}

	private record Key(Identifier source, int level, long generation) { }

	private static final class DistanceScope {
		double distanceSq;
		boolean active;
	}
}
