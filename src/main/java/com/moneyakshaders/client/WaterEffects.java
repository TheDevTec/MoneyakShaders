package com.moneyakshaders.client;

import com.moneyakshaders.MoneyakShadersConfig;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Client-side water particle effects:
 * <ul>
 *   <li><b>Splash</b> — when an entity or dropped item falls into water (the frame it first touches
 *       water), spray splash + a few bubble particles at the surface.</li>
 *   <li><b>Chest bubbles</b> — an opened chest / ender chest that is underwater leaks rising air
 *       bubbles (driven from the chest block-entity client tick).</li>
 * </ul>
 *
 * All client-only, gated on {@link MoneyakShadersConfig#waterSplash}. The splash detector tracks
 * which nearby entities were touching water last tick so it only fires on the entry transition.
 */
public final class WaterEffects {
	/** Weather-derived particles belong to one ClientWorld; proxy transfers replace that world. */
	private static ClientWorld lastWorld;
	private static final IntOpenHashSet inWaterLastTick = new IntOpenHashSet();
	private static final IntOpenHashSet inWaterThisTick = new IntOpenHashSet();
	// Client tick is serialized; these avoid short-lived positions from recurring particle scans.
	private static final BlockPos.Mutable RAIN_RIPPLE_POS = new BlockPos.Mutable();
	private static final BlockPos.Mutable RAIN_MIST_POS = new BlockPos.Mutable();
	private static final BlockPos.Mutable PLUNGE_SCAN_POS = new BlockPos.Mutable();

	private WaterEffects() {
	}

	/** Called once per client tick. Detects fresh water entries among nearby entities → splash. */
	public static void tick(MinecraftClient client) {
		ClientWorld currentWorld = client == null ? null : client.world;
		if (currentWorld != lastWorld) {
			resetForWorldChange(currentWorld);
		}
		if (client == null || currentWorld == null || client.player == null
				|| !MoneyakShadersConfig.get().waterSplash) {
			inWaterLastTick.clear();
			return;
		}
		ClientWorld world = currentWorld;
		double r = 40.0;
		Box box = client.player.getBoundingBox().expand(r);
		inWaterThisTick.clear();
		// Keep dry entities out of the result list itself. The safety wrapper preserves the prior
		// behaviour for an unusual entity that throws while its fluid state is being updated.
		for (Entity e : world.getOtherEntities(null, box, WaterEffects::isTouchingWaterSafely)) {
			inWaterThisTick.add(e.getId());
			// Fire only on the transition (was dry last tick) and only when moving DOWN into the
			// water (so swimming around doesn't keep splashing). Players/mobs and items all count.
			if (!inWaterLastTick.contains(e.getId()) && e.getVelocity().y < -0.08) {
				splash(world, e.getX(), e.getY() + e.getFluidHeight(FluidTags.WATER), e.getZ(),
						e instanceof ItemEntity ? 0.5f : 1.0f);
			}
		}
		inWaterLastTick.clear();
		inWaterLastTick.addAll(inWaterThisTick);

		rainRipples(client, world);
		// separate from rainRipples: the mist logic must ALSO run when rain has stopped (its whole
		// point is the after-rain window), while ripples early-return the moment rain hits zero
		rainMist(client, world, world.getRainGradient(1.0f));
		waterfallChurn(client, world);
	}

	/** Drop rain-transition and world-positioned particle state on dimension/server replacement. */
	private static void resetForWorldChange(ClientWorld world) {
		lastWorld = world;
		inWaterLastTick.clear();
		inWaterThisTick.clear();
		wasRaining = false;
		mistUntilMs = 0L;
		plungeSites.clear();
		plungeStrength.clear();
		scannedPlungeSites.clear();
		scannedPlungeStrength.clear();
		plungeRescanIn = 0;
		plungeScanActive = false;
		plungeScanColumn = 0;
		UnderwaterFogSync.clear();
	}

	private static boolean isTouchingWaterSafely(Entity entity) {
		try {
			return entity.isTouchingWater();
		} catch (Throwable ignored) {
			return false;
		}
	}

	/** While it's raining, small splash "ripples" where raindrops hit exposed water around the player. */
	private static void rainRipples(MinecraftClient client, ClientWorld world) {
		if (!MoneyakShadersConfig.get().rainRipples) {
			return;
		}
		float rain = world.getRainGradient(1.0f);
		if (rain <= 0.01f) {
			return; // not raining
		}
		int px = (int) Math.floor(client.player.getX());
		int pz = (int) Math.floor(client.player.getZ());
		int drops = 4 + (int) (rain * 16f); // more drops in heavier rain
		net.minecraft.util.math.BlockPos.Mutable m = RAIN_RIPPLE_POS;
		for (int i = 0; i < drops; i++) {
			int x = px + world.random.nextInt(33) - 16;
			int z = pz + world.random.nextInt(33) - 16;
			int topY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z);
			m.set(x, topY - 1, z);
			FluidState fs = world.getFluidState(m);
			if (!fs.isIn(FluidTags.WATER)) {
				continue; // top of the column isn't open water
			}
			if (!world.getBiome(m).value().hasPrecipitation()) {
				continue; // desert etc. — no rain actually falls here
			}
			double sx = x + world.random.nextDouble();
			double sz = z + world.random.nextDouble();
			// Just above the actual fluid surface (spawning under it made the effect invisible).
			double sy = (topY - 1) + fs.getHeight() + 0.02;
			// Flat expanding ripple ring ON the surface (custom particle, particle-rain style).
			world.addParticleClient(com.moneyakshaders.OplParticles.RIPPLE, sx, sy, sz, 0.0, 0.0, 0.0);
			if (world.random.nextInt(4) == 0) {
				world.addParticleClient(ParticleTypes.SPLASH, sx, sy + 0.06, sz,
						(world.random.nextDouble() - 0.5) * 0.08, 0.12, (world.random.nextDouble() - 0.5) * 0.08);
			}
		}
	}

	// Post-rain ground fog: mist forms AFTER the rain stops (wet ground evaporating), not during it.
	// Sticky flag, NOT a previous-tick sample: the rain gradient ramps down smoothly (~0.01/tick),
	// so consecutive ticks never see >0.5 → <0.05 and a naive edge detector would never fire.
	private static boolean wasRaining;
	private static long mistUntilMs;

	/**
	 * Flat ankle-height fog patches over LAND for ~90 s after a rain ends.
	 *
	 * <p>The particle itself is deliberately wide, so a heightmap point is not enough: a leaf canopy,
	 * fence, or one-block ridge can pass the heightmap test while most of the sheet hangs in mid-air or
	 * clips through foliage.  Only accept a small, open, level patch of real full-block ground.
	 */
	private static void rainMist(MinecraftClient client, ClientWorld world, float rain) {
		if (!MoneyakShadersConfig.get().rainMist) {
			wasRaining = false;
			return;
		}
		if (rain > 0.5f) {
			wasRaining = true;
		} else if (wasRaining && rain < 0.05f) {
			wasRaining = false;
			mistUntilMs = System.currentTimeMillis() + 90_000L; // rain just ended → fog window
		}
		if (rain > 0.05f || System.currentTimeMillis() > mistUntilMs) {
			return;
		}
		net.minecraft.util.math.BlockPos.Mutable m = RAIN_MIST_POS;
		int px = (int) Math.floor(client.player.getX());
		int pz = (int) Math.floor(client.player.getZ());
		// several patches per tick over a wide area — the particles themselves live 15–40 s, so the
		// ground builds up a persistent low fog layer instead of a few lonely puffs.
		for (int i = 0; i < 3; i++) {
			int x = px + world.random.nextInt(81) - 40;
			int z = pz + world.random.nextInt(81) - 40;
			int topY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
			// Ground fog collects at or below the viewer, never on a canopy / rooftop above them.
			if (topY < client.player.getY() - 14 || topY > client.player.getY() + 2) {
				continue;
			}
			if (!isMistPatchSurface(world, m, x, topY, z)) {
				continue;
			}
			if (!world.getBiome(m.set(x, topY, z)).value().hasPrecipitation()) {
				continue;
			}
			// ankle height — a flat sheet hugging the ground, not a cloud at head level
			world.addParticleClient(com.moneyakshaders.OplParticles.MIST,
					x + world.random.nextDouble(), topY + 0.25 + world.random.nextDouble() * 0.2, z + world.random.nextDouble(),
					0.0, 0.0, 0.0);
		}
	}

	/** A mist sheet needs a level 3x3 landing area and two clear blocks above it. */
	private static boolean isMistPatchSurface(ClientWorld world, BlockPos.Mutable pos, int centerX, int topY, int centerZ) {
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				int x = centerX + dx;
				int z = centerZ + dz;
				int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
				if (Math.abs(surfaceY - topY) > 1) {
					return false; // edge, small ledge, or narrow support
				}

				pos.set(x, surfaceY - 1, z);
				BlockState ground = world.getBlockState(pos);
				if (!ground.isOpaqueFullCube() || ground.getBlock() instanceof LeavesBlock
						|| !ground.getFluidState().isEmpty()) {
					return false; // land only — no leaves, fences, plants, or fluid surface
				}

				// Leaves are non-solid, therefore the collision test alone would still let fog spawn inside
				// a canopy.  Check two blocks so a sheet can neither intersect foliage nor a low ceiling.
				for (int y = surfaceY; y <= surfaceY + 1; y++) {
					pos.set(x, y, z);
					BlockState above = world.getBlockState(pos);
					if (above.getBlock() instanceof LeavesBlock || !above.getFluidState().isEmpty()
							|| !above.getCollisionShape(world, pos).isEmpty()) {
						return false;
					}
				}
				pos.set(x, surfaceY, z);
				if (!world.isSkyVisible(pos)) {
					return false; // rain cannot wet a covered interior, so it cannot evaporate into mist
				}
			}
		}
		return true;
	}

	// --- continuous waterfall churn -------------------------------------------------------------
	// The old approach piggybacked on randomDisplayTick, which only SAMPLES random blocks — a given
	// waterfall got hit rarely, so the plunge splash looked like sporadic one-shots. Instead we keep
	// a list of plunge sites (bottom of falling-water columns) rescanned every second, and emit big
	// swirling grey-white churn (CLOUD) + spray EVERY tick at each site → a constant roiling pool.
	private static final it.unimi.dsi.fastutil.longs.LongArrayList plungeSites = new it.unimi.dsi.fastutil.longs.LongArrayList();
	private static final it.unimi.dsi.fastutil.ints.IntArrayList plungeStrength = new it.unimi.dsi.fastutil.ints.IntArrayList();
	private static final it.unimi.dsi.fastutil.longs.LongArrayList scannedPlungeSites = new it.unimi.dsi.fastutil.longs.LongArrayList();
	private static final it.unimi.dsi.fastutil.ints.IntArrayList scannedPlungeStrength = new it.unimi.dsi.fastutil.ints.IntArrayList();
	private static int plungeRescanIn;
	private static final int PLUNGE_RADIUS = 24;
	private static final int PLUNGE_MAX_SITES = 24;
	private static final int PLUNGE_COLUMNS_PER_TICK = 96;
	private static int plungeScanCenterX, plungeScanCenterY, plungeScanCenterZ, plungeScanColumn;
	private static boolean plungeScanActive;

	private static void waterfallChurn(MinecraftClient client, ClientWorld world) {
		if (!MoneyakShadersConfig.get().waterfallParticles) {
			return;
		}
		if (--plungeRescanIn <= 0 && !plungeScanActive) {
			plungeRescanIn = 20;
			beginPlungeScan(client);
		}
		if (plungeScanActive) advancePlungeScan(world, PLUNGE_SCAN_POS);
		double camX = client.player.getX(), camY = client.player.getY(), camZ = client.player.getZ();
		for (int i = 0; i < plungeSites.size(); i++) {
			long key = plungeSites.getLong(i);
			int bx = net.minecraft.util.math.BlockPos.unpackLongX(key);
			int by = net.minecraft.util.math.BlockPos.unpackLongY(key);
			int bz = net.minecraft.util.math.BlockPos.unpackLongZ(key);
			double dx = bx + 0.5 - camX, dy = by + 0.5 - camY, dz = bz + 0.5 - camZ;
			double d2 = dx * dx + dy * dy + dz * dz;
			if (d2 > PLUNGE_RADIUS * PLUNGE_RADIUS) {
				continue;
			}
			// Particular-style restraint: at most ONE churn particle per site per tick (often none),
			// scaled by fall strength instead of flooding counts. Sites store strength = fall height.
			float strength = Math.min(1f, plungeStrength.getInt(i) / 8f); // 8+ block fall = full churn
			float distFactor = d2 < 100 ? 1f : (d2 < 300 ? 0.6f : 0.35f);
			float rate = (0.22f + 0.3f * strength) * distFactor; // expected particles per tick
			double sy = by + 1.02;
			if (world.random.nextFloat() < rate) {
				// Churn foam bank: chunky white puffs that HUG the surface around the plunge point
				// and swirl (user's reference shot) — the particle orbits the column centre passed
				// via vx/vz; vy carries fall strength for sizing. They never travel upward.
				double ang = world.random.nextDouble() * Math.PI * 2.0;
				double rad = 0.35 + world.random.nextDouble() * 0.75;
				world.addParticleClient(com.moneyakshaders.OplParticles.SPRAY,
						bx + 0.5 + Math.cos(ang) * rad, sy + 0.12, bz + 0.5 + Math.sin(ang) * rad,
						bx + 0.5, strength, bz + 0.5);
			}
			if (world.random.nextFloat() < rate * 0.7f) {
				double ang = world.random.nextDouble() * Math.PI * 2.0;
				double sp = 0.1 + world.random.nextDouble() * 0.2;
				world.addParticleClient(ParticleTypes.SPLASH,
						bx + 0.2 + world.random.nextDouble() * 0.6, sy, bz + 0.2 + world.random.nextDouble() * 0.6,
						Math.cos(ang) * sp, 0.15 + world.random.nextDouble() * 0.2, Math.sin(ang) * sp);
			}
			if (world.random.nextInt(5) == 0) {
				world.addParticleClient(ParticleTypes.BUBBLE,
						bx + world.random.nextDouble(), by + 0.6, bz + world.random.nextDouble(), 0.0, 0.08, 0.0);
			}
		}
	}

	private static void beginPlungeScan(MinecraftClient client) {
		plungeScanCenterX = (int) Math.floor(client.player.getX());
		plungeScanCenterY = (int) Math.floor(client.player.getY());
		plungeScanCenterZ = (int) Math.floor(client.player.getZ());
		plungeScanColumn = 0;
		plungeScanActive = true;
		scannedPlungeSites.clear();
		scannedPlungeStrength.clear();
	}

	/** Scan a fixed number of X/Z columns per tick instead of doing a large periodic world-query burst. */
	private static void advancePlungeScan(ClientWorld world, net.minecraft.util.math.BlockPos.Mutable m) {
		int width = PLUNGE_RADIUS * 2 + 1;
		int columns = width * width;
		int yMin = Math.max(world.getBottomY(), plungeScanCenterY - 20);
		int yMax = Math.min(world.getBottomY() + world.getHeight() - 1, plungeScanCenterY + 20);
		int limit = Math.min(columns, plungeScanColumn + PLUNGE_COLUMNS_PER_TICK);
		while (plungeScanColumn < limit && scannedPlungeSites.size() < PLUNGE_MAX_SITES) {
			int index = plungeScanColumn++;
			int x = plungeScanCenterX - PLUNGE_RADIUS + index % width;
			int z = plungeScanCenterZ - PLUNGE_RADIUS + index / width;
			for (int y = yMin; y <= yMax; y++) {
					FluidState fs = world.getFluidState(m.set(x, y, z));
					if (!fs.isIn(FluidTags.WATER) || !fs.get(FlowableFluid.FALLING)) {
						continue;
					}
					// True falling column (not merely horizontal outflow at the lip), with more
					// water directly above and a landing surface below.
					if (!world.getFluidState(m.set(x, y + 1, z)).isIn(FluidTags.WATER)) {
						continue;
					}
					net.minecraft.block.BlockState below = world.getBlockState(m.set(x, y - 1, z));
					if (below.isAir()) {
						continue; // still mid-air — not the plunge point
					}
					// A real WATERFALL falls through open air — flowing water inside a water body or a
					// flooded farm channel must NOT churn. Require the falling column to be air-exposed
					// on at least one side, and the cell above the column not fully submerged.
					boolean exposed = world.getBlockState(m.set(x + 1, y, z)).isAir()
							|| world.getBlockState(m.set(x - 1, y, z)).isAir()
							|| world.getBlockState(m.set(x, y, z + 1)).isAir()
							|| world.getBlockState(m.set(x, y, z - 1)).isAir();
					if (!exposed) {
						continue;
					}
					// Fall strength = how tall the falling column is (drives churn rate, Particular-style).
					int height = 0;
					while (height < 12 && world.getFluidState(m.set(x, y + 1 + height, z)).isIn(FluidTags.WATER)) {
						height++;
					}
					scannedPlungeSites.add(net.minecraft.util.math.BlockPos.asLong(x, y - 1, z));
					scannedPlungeStrength.add(height);
					break; // one site per column
				}
		}
		if (plungeScanColumn >= columns || scannedPlungeSites.size() >= PLUNGE_MAX_SITES) {
			plungeSites.clear();
			plungeStrength.clear();
			plungeSites.addAll(scannedPlungeSites);
			plungeStrength.addAll(scannedPlungeStrength);
			plungeScanActive = false;
		}
	}

	/** A burst of splash + bubble particles at the surface. {@code scale} sizes the burst. */
	private static void splash(ClientWorld world, double x, double y, double z, float scale) {
		// Big expanding ripple ring around the impact point (vx carries the ring size).
		world.addParticleClient(com.moneyakshaders.OplParticles.RIPPLE, x, y + 0.02, z, 1.2 * scale, 0.0, 0.0);
		int splashes = Math.max(3, (int) (10 * scale));
		for (int i = 0; i < splashes; i++) {
			double a = world.random.nextDouble() * Math.PI * 2.0;
			double sp = (0.15 + world.random.nextDouble() * 0.25) * scale;
			world.addParticleClient(ParticleTypes.SPLASH,
					x + Math.cos(a) * 0.25 * scale, y + 0.05, z + Math.sin(a) * 0.25 * scale,
					Math.cos(a) * sp, 0.25 + world.random.nextDouble() * 0.2, Math.sin(a) * sp);
		}
		int bubbles = Math.max(2, (int) (6 * scale));
		for (int i = 0; i < bubbles; i++) {
			world.addParticleClient(ParticleTypes.BUBBLE,
					x + (world.random.nextDouble() - 0.5) * 0.4 * scale, y - 0.1, z + (world.random.nextDouble() - 0.5) * 0.4 * scale,
					0.0, 0.05, 0.0);
		}
	}

	/**
	 * Called from the chest block-entity client tick: if the chest is open ({@code lidProgress} > a
	 * small threshold) and submerged (water directly above it), occasionally leak rising air bubbles
	 * from the lid. Throttled by RNG so a row of open chests doesn't flood the particle budget.
	 */
	public static void chestBubbles(World world, BlockPos pos, float lidProgress) {
		if (world == null || !world.isClient() || lidProgress < 0.15f || !MoneyakShadersConfig.get().waterSplash) {
			return;
		}
		if (!world.getFluidState(pos.up()).isIn(FluidTags.WATER)) {
			return; // not underwater
		}
		if (world.random.nextInt(3) != 0) {
			return; // ~every 3rd tick, gentle stream
		}
		double bx = pos.getX() + 0.3 + world.random.nextDouble() * 0.4;
		double bz = pos.getZ() + 0.3 + world.random.nextDouble() * 0.4;
		double by = pos.getY() + 0.9;
		world.addParticleClient(ParticleTypes.BUBBLE_COLUMN_UP, bx, by, bz,
				0.0, 0.06 + world.random.nextDouble() * 0.04, 0.0);
	}
}
