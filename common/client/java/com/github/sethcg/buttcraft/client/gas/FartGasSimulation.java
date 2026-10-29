package com.github.sethcg.buttcraft.client.gas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import com.github.sethcg.buttcraft.FartBreeze;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * CLIENT-SIDE GAS PHYSICS.
 *
 * <p>EACH FART IS AN EMITTER THAT RELEASES A SHORT TURBULENT JET OF "PUFFS". A PUFF
 * IS A SPHERICAL PARCEL OF GAS WITH ITS OWN VELOCITY, TEMPERATURE, MASS AND RADIUS.
 * THE GPU RAY MARCHER BLENDS NEIGHBOURING PUFFS INTO ONE CONTINUOUS VOLUME AND ADDS
 * THE SMALL-SCALE DETAIL; THIS CLASS HANDLES THE LARGE-SCALE MOTION:
 *
 * <ul>
 *   <li>JET MOMENTUM WITH QUADRATIC + LINEAR AIR DRAG</li>
 *   <li>BUOYANCY FROM BODY-TEMPERATURE GAS THAT COOLS OVER TIME</li>
 *   <li>TURBULENT ENTRAINMENT (PUFFS GROW AS THEY MOVE AND DIFFUSE)</li>
 *   <li>SMOOTH PSEUDO-RANDOM TURBULENCE AND THE SERVER'S SLOWLY VARYING {@link FartBreeze}</li>
 *   <li>PRESSURE BETWEEN OVERLAPPING PUFFS SO THE CLOUD SPREADS OUT</li>
 *   <li>BLOCK COLLISIONS THAT DEFLECT AND SPREAD GAS ALONG SURFACES</li>
 *   <li>THE NEAREST WALLS AROUND EACH PUFF, SO THE SHADER DOESN'T DRAW GAS THROUGH THEM</li>
 *   <li>ENTITIES STIRRING THE GAS AS THEY MOVE THROUGH IT</li>
 *   <li>PER-PUFF SKY/BLOCK LIGHT SAMPLING FOR LIGHTING</li>
 *   <li>HOW MUCH GAS LIES BETWEEN EACH PUFF AND THE SUN / SKY, SO WHOLE CLOUDS SHADE THEMSELVES</li>
 *   <li>CLUSTERS OF NEARBY PUFFS (ONE PER CLOUD) FOR ENTITY STIRRING AND THE SCREEN BOUNDS</li>
 *   <li>A PUFF LIMIT: WHEN FULL, THE MOST-OVERLAPPING PUFFS (SETTLED ONES FIRST) MERGE INTO ONE</li>
 * </ul>
 *
 * <p>EVERY FART'S RANDOMNESS COMES FROM THE SEED THE SERVER SENT, AND THE TURBULENCE AND BREEZE
 * FOLLOW THE GAME TIME, SO EVERY PLAYER SEES MUCH THE SAME CLOUD.
 */
public final class FartGasSimulation {

    /** HARD CAP, SIZED TO THE SHADER'S PUFF ARRAY. THE MAX PUFFS SETTING MAY BE LOWER. */
    public static final int MAX_PUFFS = 256;

    /**
     * HOW MANY SCREEN RECTANGLES THE GAS IS DRAWN IN. EACH PIXEL ONLY TESTS THE PUFFS OF THE
     * RECTANGLES OVER IT, SO MORE OF THEM MEANS FEWER TESTS WHEN THERE ARE MANY CLOUDS. A
     * MULTIPLE OF 4 (THE SHADER'S RectEnds IS ivec4s); MUST MATCH fart_gas_common.glsl.
     */
    public static final int MAX_RECTS = 8;

    // EMISSION: A FART RELEASES ONE PUFF A TICK (A BURST, TWO FOR ITS FIRST FEW TICKS). EACH IS
    // PUFF_VOLUME TIMES THE VOLUME OF THE SMALL PUFFS THE GAS WAS TUNED WITH (18 PER FART), SO
    // A FART TAKES FAR LESS OF THE PUFF LIMIT BUT HOLDS ABOUT AS MUCH GAS.
    private static final int EMIT_TICKS = 7;
    private static final float PUFF_VOLUME = 2.5F;
    // RADII SCALE BY THE CUBE ROOT OF PUFF_VOLUME, AREAS BY ITS SQUARE
    private static final float PUFF_SCALE = (float) Math.cbrt(PUFF_VOLUME);
    private static final float INITIAL_RADIUS = 0.28F * PUFF_SCALE;
    // A PUFF THIS BIG HAS ITS SPAWN DENSITY; BIGGER ONES THIN OUT (SEE Puff.dilution)
    private static final float DILUTION_RADIUS = 0.56F;
    private static final double JET_SPEED_MIN = 0.22;
    private static final double JET_SPEED_MAX = 0.40;
    private static final double JET_SPREAD = 0.32;

    // MOTION (BLOCKS / TICK)
    private static final double LINEAR_DRAG = 0.93;
    private static final double QUADRATIC_DRAG = 1.0;
    private static final double BUOYANCY = 0.0011;
    private static final double SETTLING = 0.00045;
    private static final double COOLING = 0.955;
    private static final double TURBULENCE = 0.0009;
    private static final double PRESSURE = 0.008;

    // GROWTH / DISSIPATION
    // DIFFUSION GROWS radius^2, SO IT SCALES WITH AREA TO KEEP A PUFF'S VOLUME IN STEP WITH THE
    // SMALL PUFFS IT STANDS IN FOR. MAX_RADIUS + WALL_SEARCH_MARGIN MUST STAY WITHIN WALL_SEARCH_BLOCKS.
    private static final float DIFFUSION = 0.0065F * PUFF_SCALE * PUFF_SCALE;
    private static final float ENTRAINMENT = 0.16F;
    private static final float MAX_RADIUS = 2.5F;

    // A FART'S PUFFS LIVE THIS LONG PLUS UP TO PUFF_LIFETIME_SPREAD MORE, SO IT FADES OUT UNEVENLY.
    // THE SERVER'S POISON CLOUD LASTS ABOUT AS LONG AS THE MIDDLE OF THAT.
    private static final int PUFF_LIFETIME = 150;
    private static final int PUFF_LIFETIME_SPREAD = 90;

    // LIGHTING: EACH TICK A PUFF'S LIGHT MOVES THIS FAR TOWARD THE LIGHT WHERE IT IS NOW,
    // SO DRIFTING INTO SHADE OR TORCHLIGHT FADES RATHER THAN SNAPS.
    private static final float LIGHT_RESPONSE = 0.25F;

    // SHADOWS: EACH PUFF MARCHES TOWARD THE LIGHT AND STRAIGHT UP THROUGH THE SMOOTH PUFF FIELD,
    // WITH GEOMETRICALLY GROWING STEPS THAT REACH ABOUT 8 BLOCKS.
    private static final int LIGHT_STEPS = 8;
    private static final double LIGHT_FIRST_STEP = 0.2;
    private static final double LIGHT_STEP_GROWTH = 1.45;

    // THE SHADER'S METABALL FIELD: SUMMED WEIGHTS TIMES THIS GIVE ITS COVERAGE, AND ITS NOISE
    // LEAVES ABOUT SHADOW_FILL OF THE SMOOTH FIELD'S GAS. KEEP BOTH IN SYNC WITH fart_gas.fsh.
    static final float FIELD_COVERAGE_SCALE = 1.35F;
    private static final float SHADOW_FILL = 0.5F;

    // WALLS: LOOK THIS FAR PAST A PUFF'S RADIUS FOR THE NEAREST SURFACE ON EACH SIDE, SO THEY
    // STILL COVER THE PUFF AS IT MOVES BETWEEN TICKS.
    private static final double WALL_SEARCH_MARGIN = 0.5;
    private static final int WALL_SEARCH_BLOCKS = 3;

    // CLUSTERS: PUFFS WHOSE SURFACES ARE CLOSER THAN THIS BELONG TO THE SAME CLOUD. fart_gas.fsh
    // ONLY LOOKS FOR SHADOWING PUFFS IN THE RUNS UNDER ITS PIXEL, SO THIS MUST STAY ABOVE ITS
    // SHADOW_REACH (0.6) WITH ROOM FOR A TICK OF MOVEMENT.
    private static final double CLUSTER_GAP = 1.0;

    // MERGING: ONLY PUFFS THIS OLD MERGE, SO THE TURBULENT JET KEEPS ITS SMALL PUFFS.
    private static final int MERGE_MIN_AGE = 12;
    // OVERLAPPING PAIRS FARTHER FROM THE CAMERA MERGE FIRST: THEIR MERGE SCORE IS DIVIDED BY
    // 1 + (DISTANCE / THIS)^2, SO BY 2 AT 16 BLOCKS, 5 AT 32 AND 10 AT 48. DISTANT GAS COVERS FEW
    // PIXELS, SO IT LOSES LITTLE AS BIGGER PUFFS, AND THE LIMIT GOES TO THE CLOUDS UP CLOSE.
    private static final double MERGE_LOD_DISTANCE = 16.0;

    private final List<Puff> puffs = new ArrayList<>();
    private final List<Emitter> emitters = new ArrayList<>();
    private final RandomSource random = RandomSource.create();
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

    // TOWARD THE SUN OR MOON, FROM THE SKY THE RENDERER LAST DREW
    private final Vector3f lightDirection = new Vector3f(0.0F, 1.0F, 0.0F);
    private boolean hasDirectLight;

    // CLUSTERS: PUFF INDICES GROUPED BY CLUSTER, AND WHERE EACH CLUSTER STARTS IN THAT LIST
    private final int[] clusterParent = new int[MAX_PUFFS];
    private final int[] clusterOrder = new int[MAX_PUFFS];
    private final int[] clusterStart = new int[MAX_PUFFS + 1];
    private int clusterCount;

    // THE PUFF FIELD, COPIED OUT FOR THE LIGHT MARCHES SO THEIR INNER LOOP STAYS TIGHT
    private final double[] fieldX = new double[MAX_PUFFS];
    private final double[] fieldY = new double[MAX_PUFFS];
    private final double[] fieldZ = new double[MAX_PUFFS];
    private final float[] fieldInverseRadiusSquared = new float[MAX_PUFFS];
    private final float[] fieldDensity = new float[MAX_PUFFS];
    private int fieldCount;
    private @Nullable AABB fieldBounds;

    public void emit(Vec3 pos, float yaw, int seed) {
        double angle = Math.toRadians(yaw);
        Vec3 backward = new Vec3(Math.sin(angle), 0.0, -Math.cos(angle));
        // JUST OUTSIDE THE BACK OF THE PLAYER'S HITBOX (HALF WIDTH 0.3), AT HIP HEIGHT.
        Vec3 origin = pos.add(backward.x * 0.36, 0.7, backward.z * 0.36);
        this.emitters.add(new Emitter(origin, backward, PUFF_LIFETIME, PUFF_LIFETIME_SPREAD, seed));
    }

    /**
     * A FART THAT SPREADS OUT EVENLY IN EVERY DIRECTION FROM A POINT (E.G. A SPLASH POTION),
     * LINGERING ABOUT AS LONG AS THE SERVER'S POISON CLOUD (lifetimeTicks).
     */
    public void emitBurst(Vec3 pos, int lifetimeTicks, int seed) {
        // SHORT CLOUDS FADE OUT OVER LESS TIME, SO THEY DON'T OUTLAST THE POISON BY MUCH.
        int lifetimeSpread = Math.max(1, Math.min(PUFF_LIFETIME_SPREAD, lifetimeTicks / 2));
        int puffLifetime = Math.max(1, lifetimeTicks - lifetimeSpread / 3);
        this.emitters.add(new Emitter(pos, null, puffLifetime, lifetimeSpread, seed));
    }

    public List<Puff> puffs() {
        return this.puffs;
    }

    /** THE (NORMALISED) DIRECTION SHADOWS ARE CAST FROM. WITHOUT DIRECT LIGHT (E.G. THE END) THERE ARE NONE. */
    public void setLight(Vector3fc direction, boolean hasDirectLight) {
        this.lightDirection.set(direction);
        this.hasDirectLight = hasDirectLight;
    }

    public void clear() {
        this.puffs.clear();
        this.emitters.clear();
        this.clusterCount = 0;
    }

    public void tick(ClientLevel level) {
        long gameTime = level.getGameTime();
        this.tickEmitters(level);
        if (this.puffs.isEmpty()) {
            return;
        }

        this.updateClusters();
        this.applyPressure();
        this.applyEntityStirring(level);

        boolean hasSkyLight = level.dimensionType().hasSkyLight();
        double breezeX = FartBreeze.x(gameTime);
        double breezeZ = FartBreeze.z(gameTime);
        // TURBULENCE PHASE, SHARED BY EVERY PUFF THIS TICK (AND BY EVERY PLAYER).
        double turbulencePhase = gameTime * 0.045;

        for (int puffIndex = this.puffs.size() - 1; puffIndex >= 0; puffIndex--) {
            Puff puff = this.puffs.get(puffIndex);
            puff.storePrevious();
            puff.age++;

            if (puff.age >= puff.lifetime || puff.mass < 0.01F) {
                this.puffs.remove(puffIndex);
                continue;
            }

            // DRAG: LINEAR + QUADRATIC (TURBULENT) TERMS.
            double speed = Math.sqrt(puff.velocityX * puff.velocityX + puff.velocityY * puff.velocityY + puff.velocityZ * puff.velocityZ);
            double drag = LINEAR_DRAG / (1.0 + QUADRATIC_DRAG * speed);
            puff.velocityX *= drag;
            puff.velocityY *= drag;
            puff.velocityZ *= drag;

            // BUOYANCY: WARM GAS RISES, THEN SLOWLY SETTLES ONCE IT COOLS.
            puff.temperature *= COOLING;
            puff.velocityY += BUOYANCY * puff.temperature - SETTLING;

            // TURBULENCE: SMOOTH, DIVERGENCE-FREE-ISH SWIRL FROM LAYERED SINES.
            double swirlX = puff.x * 0.9 + puff.seed;
            double swirlY = puff.y * 0.9;
            double swirlZ = puff.z * 0.9 - puff.seed;
            double turbulence = TURBULENCE * (0.6 + Math.min(1.0, puff.age / 30.0));
            puff.velocityX += (Math.sin(swirlY * 1.7 + turbulencePhase) - Math.cos(swirlZ * 1.3 - turbulencePhase * 0.8)) * turbulence;
            puff.velocityY += (Math.sin(swirlZ * 1.5 + turbulencePhase * 1.1) - Math.cos(swirlX * 1.9 + turbulencePhase * 0.6)) * turbulence * 0.6;
            puff.velocityZ += (Math.sin(swirlX * 1.4 - turbulencePhase * 0.9) - Math.cos(swirlY * 1.6 + turbulencePhase * 1.2)) * turbulence;

            // AMBIENT BREEZE (ONLY OUTDOORS).
            double exposure = FartBreeze.exposure(hasSkyLight, puff.skyLight);
            puff.velocityX += breezeX * exposure;
            puff.velocityZ += breezeZ * exposure;

            this.moveWithCollisions(level, puff);

            // GROWTH: MOLECULAR/TURBULENT DIFFUSION + ENTRAINMENT FROM MOTION.
            float radius = puff.radius;
            radius = Mth.sqrt(radius * radius + DIFFUSION) + (float) (speed * ENTRAINMENT);
            puff.radius = Math.min(radius, MAX_RADIUS);

            // DISSIPATION: GAS SLOWLY MIXES BELOW VISIBILITY.
            puff.mass *= 0.997F;
            this.sampleEnvironment(level, puff, hasSkyLight, LIGHT_RESPONSE);
            this.findWalls(level, puff);
        }

        this.updateLightDepths();
    }

    private void tickEmitters(ClientLevel level) {
        if (this.emitters.isEmpty()) {
            return;
        }

        // A LOWERED LIMIT (SETTING CHANGED MID-CLOUD) IS REACHED BY MERGING AS PUFFS SPAWN.
        int budget = Mth.clamp(FartGasSettings.current().maxPuffs(), 1, MAX_PUFFS);
        for (int emitterIndex = this.emitters.size() - 1; emitterIndex >= 0; emitterIndex--) {
            Emitter emitter = this.emitters.get(emitterIndex);
            // A BURST RELEASES MORE GAS AT ONCE, SINCE IT SPREADS OUT IN EVERY DIRECTION.
            int count = emitter.direction == null && emitter.age < 4 ? 2 : 1;
            for (int spawned = 0; spawned < count; spawned++) {
                this.spawnPuff(level, emitter, budget);
            }

            emitter.age++;
            if (emitter.age >= EMIT_TICKS) {
                this.emitters.remove(emitterIndex);
            }
        }
    }

    private void spawnPuff(ClientLevel level, Emitter emitter, int budget) {
        // MERGE SETTLED PUFFS FIRST. WHEN NONE ARE SETTLED (SEVERAL FARTS AT ONCE, OR A BUDGET
        // SMALLER THAN ONE FART), MERGE YOUNG ONES RATHER THAN DELETE GAS OUTRIGHT.
        while (this.puffs.size() >= budget) {
            if (!this.mergeClosestPair(MERGE_MIN_AGE) && !this.mergeClosestPair(0)) {
                this.removeFaintestPuff();
            }
        }

        RandomSource random = emitter.random;

        // JET STRENGTH DECAYS OVER THE EMISSION.
        double strength = 1.0 - emitter.age / (double) EMIT_TICKS * 0.65;
        double speed = Mth.lerp(random.nextDouble(), JET_SPEED_MIN, JET_SPEED_MAX) * strength;

        Vec3 dir;
        if (emitter.direction == null) {
            // BURST: A RANDOM, MOSTLY SIDEWAYS DIRECTION SO THE GAS RINGS OUT ACROSS THE GROUND.
            double angle = random.nextDouble() * Math.PI * 2.0;
            dir = new Vec3(Math.cos(angle), 0.1 + random.nextGaussian() * 0.25, Math.sin(angle)).normalize();
        } else {
            dir = emitter.direction
                .add(random.nextGaussian() * JET_SPREAD * 0.5,
                    -0.12 + random.nextGaussian() * JET_SPREAD * 0.35,
                    random.nextGaussian() * JET_SPREAD * 0.5)
                .normalize();
        }

        Puff puff = new Puff();
        puff.x = emitter.origin.x + random.nextGaussian() * 0.03;
        puff.y = emitter.origin.y + random.nextGaussian() * 0.03;
        puff.z = emitter.origin.z + random.nextGaussian() * 0.03;
        puff.velocityX = dir.x * speed;
        puff.velocityY = dir.y * speed;
        puff.velocityZ = dir.z * speed;
        puff.radius = INITIAL_RADIUS * (0.8F + random.nextFloat() * 0.5F);
        puff.temperature = 1.0F;
        puff.mass = 0.85F + random.nextFloat() * 0.3F;
        puff.lifetime = emitter.puffLifetime + random.nextInt(emitter.lifetimeSpread);
        puff.seed = random.nextFloat() * 64.0F;
        // A NEW PUFF STARTS AT ITS SURROUNDINGS' LIGHT RATHER THAN EASING IN FROM DARKNESS.
        this.sampleEnvironment(level, puff, level.dimensionType().hasSkyLight(), 1.0F);
        puff.storePrevious();
        this.puffs.add(puff);
    }

    private void applyPressure() {
        int size = this.puffs.size();
        for (int indexA = 0; indexA < size; indexA++) {
            Puff puffA = this.puffs.get(indexA);
            for (int indexB = indexA + 1; indexB < size; indexB++) {
                Puff puffB = this.puffs.get(indexB);
                double offsetX = puffB.x - puffA.x;
                double offsetY = puffB.y - puffA.y;
                double offsetZ = puffB.z - puffA.z;
                double minDistance = (puffA.radius + puffB.radius) * 0.45;
                double distanceSquared = offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ;
                if (distanceSquared >= minDistance * minDistance) {
                    continue;
                }

                double distance = Math.sqrt(distanceSquared);
                if (distance < 1.0E-4) {
                    offsetX = this.random.nextGaussian();
                    offsetY = this.random.nextGaussian() * 0.3;
                    offsetZ = this.random.nextGaussian();
                    distance = Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
                }

                double push = (1.0 - distance / minDistance) * PRESSURE / distance;
                double massA = puffA.mass;
                double massB = puffB.mass;
                double total = massA + massB;
                puffA.velocityX -= offsetX * push * massB / total;
                puffA.velocityY -= offsetY * push * massB / total;
                puffA.velocityZ -= offsetZ * push * massB / total;
                puffB.velocityX += offsetX * push * massA / total;
                puffB.velocityY += offsetY * push * massA / total;
                puffB.velocityZ += offsetZ * push * massA / total;
            }
        }
    }

    /**
     * MERGES THE PAIR (BOTH AT LEAST minAge TICKS OLD) WITH THE MOST OVERLAP (SMALLEST
     * DISTANCE RELATIVE TO THEIR RADII) INTO ONE BIGGER PUFF, FAVOURING OVERLAPPING PAIRS FAR
     * FROM THE CAMERA (SEE MERGE_LOD_DISTANCE). PAIRS THAT DON'T OVERLAP ARE NEVER FAVOURED, SO
     * DISTANT CLOUDS DON'T MERGE INTO EACH OTHER. RETURNS FALSE IF NO PAIR IS OLD ENOUGH.
     */
    private boolean mergeClosestPair(int minAge) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        int size = this.puffs.size();
        int bestA = -1;
        int bestB = -1;
        double best = Double.MAX_VALUE;
        for (int indexA = 0; indexA < size; indexA++) {
            Puff puffA = this.puffs.get(indexA);
            if (puffA.age < minAge) {
                continue;
            }

            for (int indexB = indexA + 1; indexB < size; indexB++) {
                Puff puffB = this.puffs.get(indexB);
                if (puffB.age < minAge) {
                    continue;
                }

                double offsetX = puffB.x - puffA.x;
                double offsetY = puffB.y - puffA.y;
                double offsetZ = puffB.z - puffA.z;
                double radii = puffA.radius + puffB.radius;
                double score = (offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ) / (radii * radii);
                if (score < 1.0) {
                    double lod = Math.sqrt(camera.distanceToSqr(puffA.x, puffA.y, puffA.z)) / MERGE_LOD_DISTANCE;
                    score /= 1.0 + lod * lod;
                }

                if (score < best) {
                    best = score;
                    bestA = indexA;
                    bestB = indexB;
                }
            }
        }

        if (bestA < 0) {
            return false;
        }

        Puff puffB = this.puffs.remove(bestB);
        this.puffs.get(bestA).absorb(puffB);
        return true;
    }

    /** LAST RESORT WHEN NOTHING CAN MERGE (A BUDGET OF ONE): DROPS THE LEAST VISIBLE PUFF. */
    private void removeFaintestPuff() {
        int faintest = 0;
        for (int puffIndex = 1; puffIndex < this.puffs.size(); puffIndex++) {
            if (this.puffs.get(puffIndex).density(1.0F) < this.puffs.get(faintest).density(1.0F)) {
                faintest = puffIndex;
            }
        }
        this.puffs.remove(faintest);
    }

    /**
     * GROUPS PUFFS THAT (NEARLY) TOUCH INTO CLUSTERS, ONE PER CLOUD, THEN SPREADS THE CLUSTERS
     * OVER AT MOST {@link #MAX_RECTS} SCREEN RECTANGLES, MERGING THE CLOSEST CLUSTERS WHEN
     * THERE ARE MORE. ENTITY STIRRING ONLY LOOKS AROUND EACH CLUSTER, AND THE RENDERER ONLY
     * MARCHES INSIDE EACH RECTANGLE, SO THE SPACE BETWEEN SEPARATE CLOUDS COSTS NOTHING.
     */
    private void updateClusters() {
        int size = this.puffs.size();
        int[] parent = this.clusterParent;
        for (int puffIndex = 0; puffIndex < size; puffIndex++) {
            parent[puffIndex] = puffIndex;
        }

        for (int indexA = 0; indexA < size; indexA++) {
            Puff puffA = this.puffs.get(indexA);
            for (int indexB = indexA + 1; indexB < size; indexB++) {
                Puff puffB = this.puffs.get(indexB);
                double offsetX = puffB.x - puffA.x;
                double offsetY = puffB.y - puffA.y;
                double offsetZ = puffB.z - puffA.z;
                double reach = puffA.radius + puffB.radius + CLUSTER_GAP;
                if (offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ < reach * reach) {
                    int rootA = this.findRoot(indexA);
                    int rootB = this.findRoot(indexB);
                    // THE ROOT IS ALWAYS THE CLUSTER'S FIRST PUFF
                    parent[Math.max(rootA, rootB)] = Math.min(rootA, rootB);
                }
            }
        }

        // NUMBER THE CLUSTERS IN ORDER OF THEIR FIRST PUFF
        int count = 0;
        for (int puffIndex = 0; puffIndex < size; puffIndex++) {
            Puff puff = this.puffs.get(puffIndex);
            int root = this.findRoot(puffIndex);
            puff.cluster = root == puffIndex ? count++ : this.puffs.get(root).cluster;
        }

        // GROUP THE PUFF INDICES BY CLUSTER (COUNTING SORT). parent IS FREE TO REUSE NOW.
        this.clusterCount = count;
        Arrays.fill(this.clusterStart, 0, count + 1, 0);
        for (Puff puff : this.puffs) {
            this.clusterStart[puff.cluster + 1]++;
        }

        for (int cluster = 0; cluster < count; cluster++) {
            this.clusterStart[cluster + 1] += this.clusterStart[cluster];
            parent[cluster] = this.clusterStart[cluster];
        }

        for (int puffIndex = 0; puffIndex < size; puffIndex++) {
            this.clusterOrder[parent[this.puffs.get(puffIndex).cluster]++] = puffIndex;
        }

        this.assignRects();
    }

    private int findRoot(int puffIndex) {
        int[] parent = this.clusterParent;
        while (parent[puffIndex] != puffIndex) {
            parent[puffIndex] = parent[parent[puffIndex]];
            puffIndex = parent[puffIndex];
        }

        return puffIndex;
    }

    /** SPREADS THE CLUSTERS OVER THE SCREEN RECTANGLES, MERGING THE CLOSEST ONES WHILE THERE ARE TOO MANY. */
    private void assignRects() {
        int count = this.clusterCount;
        if (count <= MAX_RECTS) {
            for (Puff puff : this.puffs) {
                puff.rect = puff.cluster;
            }
            return;
        }

        AABB[] boxes = new AABB[count];
        int[] clusterGroup = new int[count];
        for (int cluster = 0; cluster < count; cluster++) {
            boxes[cluster] = this.clusterBounds(cluster);
            clusterGroup[cluster] = cluster;
        }

        // boxes[group] IS NULL ONCE THAT GROUP HAS BEEN MERGED INTO ANOTHER
        for (int groups = count; groups > MAX_RECTS; groups--) {
            int bestA = -1;
            int bestB = -1;
            double best = Double.MAX_VALUE;
            for (int groupA = 0; groupA < count; groupA++) {
                if (boxes[groupA] == null) {
                    continue;
                }

                for (int groupB = groupA + 1; groupB < count; groupB++) {
                    if (boxes[groupB] == null) {
                        continue;
                    }

                    double gap = gapSqr(boxes[groupA], boxes[groupB]);
                    if (gap < best) {
                        best = gap;
                        bestA = groupA;
                        bestB = groupB;
                    }
                }
            }

            boxes[bestA] = boxes[bestA].minmax(boxes[bestB]);
            boxes[bestB] = null;
            for (int cluster = 0; cluster < count; cluster++) {
                if (clusterGroup[cluster] == bestB) {
                    clusterGroup[cluster] = bestA;
                }
            }
        }

        // NUMBER THE SURVIVING GROUPS 0..MAX_RECTS-1
        int[] groupRect = new int[count];
        int rects = 0;
        for (int group = 0; group < count; group++) {
            if (boxes[group] != null) {
                groupRect[group] = rects++;
            }
        }

        for (Puff puff : this.puffs) {
            puff.rect = groupRect[clusterGroup[puff.cluster]];
        }
    }

    private AABB clusterBounds(int cluster) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int orderIndex = this.clusterStart[cluster]; orderIndex < this.clusterStart[cluster + 1]; orderIndex++) {
            Puff puff = this.puffs.get(this.clusterOrder[orderIndex]);
            minX = Math.min(minX, puff.x - puff.radius);
            minY = Math.min(minY, puff.y - puff.radius);
            minZ = Math.min(minZ, puff.z - puff.radius);
            maxX = Math.max(maxX, puff.x + puff.radius);
            maxY = Math.max(maxY, puff.y + puff.radius);
            maxZ = Math.max(maxZ, puff.z + puff.radius);
        }

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    // SQUARED DISTANCE BETWEEN TWO BOXES (0 IF THEY OVERLAP)
    private static double gapSqr(AABB boxA, AABB boxB) {
        double gapX = Math.max(0.0, Math.max(boxA.minX - boxB.maxX, boxB.minX - boxA.maxX));
        double gapY = Math.max(0.0, Math.max(boxA.minY - boxB.maxY, boxB.minY - boxA.maxY));
        double gapZ = Math.max(0.0, Math.max(boxA.minZ - boxB.maxZ, boxB.minZ - boxA.maxZ));
        return gapX * gapX + gapY * gapY + gapZ * gapZ;
    }

    private void applyEntityStirring(ClientLevel level) {
        // ONE SEARCH PER CLOUD, SO TWO CLOUDS FAR APART DON'T SCAN EVERY ENTITY BETWEEN THEM.
        for (int cluster = 0; cluster < this.clusterCount; cluster++) {
            int start = this.clusterStart[cluster];
            int end = this.clusterStart[cluster + 1];
            AABB bounds = this.clusterBounds(cluster).inflate(1.0);
            List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, bounds, Entity::isAlive);
            for (LivingEntity entity : entities) {
                double entityVelX = entity.getX() - entity.xo;
                double entityVelY = entity.getY() - entity.yo;
                double entityVelZ = entity.getZ() - entity.zo;
                // ONLY MOVING BODIES DRAG GAS ALONG; A STANDING ONE MUST NOT STALL THE JET.
                double entitySpeed = Math.sqrt(entityVelX * entityVelX + entityVelY * entityVelY + entityVelZ * entityVelZ);
                double dragAlong = Math.min(1.0, entitySpeed / 0.08);
                AABB box = entity.getBoundingBox();
                double centerX = (box.minX + box.maxX) * 0.5;
                double centerY = (box.minY + box.maxY) * 0.5;
                double centerZ = (box.minZ + box.maxZ) * 0.5;
                double reach = Math.max(box.getXsize(), box.getYsize()) * 0.5 + 0.35;

                for (int orderIndex = start; orderIndex < end; orderIndex++) {
                    Puff puff = this.puffs.get(this.clusterOrder[orderIndex]);
                    double offsetX = puff.x - centerX;
                    double offsetY = puff.y - centerY;
                    double offsetZ = puff.z - centerZ;
                    double influence = reach + puff.radius;
                    double distanceSquared = offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ;
                    if (distanceSquared >= influence * influence) {
                        continue;
                    }

                    double distance = Math.sqrt(distanceSquared) + 1.0E-4;
                    double weight = 1.0 - distance / influence;
                    weight *= weight;

                    // DRAG ALONG WITH A MOVING BODY + DISPLACE SIDEWAYS OUT OF ITS VOLUME (WAKE).
                    double horizontal = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ) + 1.0E-4;
                    double drag = weight * 0.3 * dragAlong;
                    puff.velocityX += (entityVelX * 0.7 - puff.velocityX) * drag + offsetX / horizontal * weight * 0.01;
                    puff.velocityY += (entityVelY * 0.5 - puff.velocityY) * drag * 0.6;
                    puff.velocityZ += (entityVelZ * 0.7 - puff.velocityZ) * drag + offsetZ / horizontal * weight * 0.01;
                }
            }
        }
    }

    private void moveWithCollisions(ClientLevel level, Puff puff) {
        // PROBE A LITTLE AHEAD OF THE CENTER SO GAS STOPS AT SURFACES RATHER THAN SINKING INTO THEM.
        double probe = Math.min(puff.radius * 0.45, 0.45);

        double nextX = puff.x + puff.velocityX;
        if (this.isSolid(level, nextX + Math.signum(puff.velocityX) * probe, puff.y, puff.z)) {
            double hit = puff.velocityX;
            puff.velocityX = -hit * 0.12;
            puff.velocityY += Math.abs(hit) * 0.08;
            puff.velocityZ += Math.abs(hit) * 0.25 * (puff.velocityZ >= 0.0 ? 1.0 : -1.0);
        } else {
            puff.x = nextX;
        }

        double nextY = puff.y + puff.velocityY;
        if (this.isSolid(level, puff.x, nextY + Math.signum(puff.velocityY) * probe, puff.z)) {
            // HITTING A FLOOR/CEILING SPREADS THE GAS SIDEWAYS LIKE A POOL.
            double hit = Math.abs(puff.velocityY);
            puff.velocityY = -puff.velocityY * 0.1;
            double spread = hit * 0.6;
            double horizontal = Math.sqrt(puff.velocityX * puff.velocityX + puff.velocityZ * puff.velocityZ);
            if (horizontal > 1.0E-4) {
                puff.velocityX += puff.velocityX / horizontal * spread;
                puff.velocityZ += puff.velocityZ / horizontal * spread;
            } else {
                double angle = puff.seed * 7.0;
                puff.velocityX += Math.cos(angle) * spread;
                puff.velocityZ += Math.sin(angle) * spread;
            }
        } else {
            puff.y = nextY;
        }

        double nextZ = puff.z + puff.velocityZ;
        if (this.isSolid(level, puff.x, puff.y, nextZ + Math.signum(puff.velocityZ) * probe)) {
            double hit = puff.velocityZ;
            puff.velocityZ = -hit * 0.12;
            puff.velocityY += Math.abs(hit) * 0.08;
            puff.velocityX += Math.abs(hit) * 0.25 * (puff.velocityX >= 0.0 ? 1.0 : -1.0);
        } else {
            puff.z = nextZ;
        }

        // ESCAPE IF A BLOCK APPEARED AROUND THE PUFF (E.G. PLACED BY A PLAYER).
        if (this.isSolid(level, puff.x, puff.y, puff.z)) {
            puff.velocityY += 0.03;
            puff.mass *= 0.9F;
        }
    }

    private boolean isSolid(ClientLevel level, double x, double y, double z) {
        this.mutablePos.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        BlockState state = level.getBlockState(this.mutablePos);
        if (state.isAir()) {
            return false;
        }

        // MOST SOLID BLOCKS ARE FULL CUBES, WHICH CONTAIN ANY POINT IN THEIR CELL.
        if (state.isCollisionShapeFullBlock(level, this.mutablePos)) {
            return true;
        }

        VoxelShape shape = state.getCollisionShape(level, this.mutablePos);
        if (shape.isEmpty()) {
            return false;
        }

        double localX = x - this.mutablePos.getX();
        double localY = y - this.mutablePos.getY();
        double localZ = z - this.mutablePos.getZ();

        // CHEAP REJECT (E.G. ABOVE A SLAB) BEFORE toAabbs() BUILDS A LIST OF BOXES.
        AABB bounds = shape.bounds();
        if (localX < bounds.minX || localX > bounds.maxX || localY < bounds.minY || localY > bounds.maxY || localZ < bounds.minZ || localZ > bounds.maxZ) {
            return false;
        }

        for (AABB box : shape.toAabbs()) {
            if (localX >= box.minX && localX <= box.maxX && localY >= box.minY && localY <= box.maxY && localZ >= box.minZ && localZ <= box.maxZ) {
                return true;
            }
        }

        return false;
    }

    /**
     * THE NEAREST SOLID SURFACE ON EACH SIDE OF THE PUFF ALONG THE THREE AXES. THE SHADER FADES
     * THE PUFF OUT BEYOND THEM, SO A PUFF PRESSED AGAINST A THIN WALL OR FLOOR DOESN'T SHOW
     * THROUGH ON THE OTHER SIDE.
     */
    private void findWalls(ClientLevel level, Puff puff) {
        double reach = puff.radius + WALL_SEARCH_MARGIN;
        puff.wallMinX = this.findWall(level, puff, Direction.Axis.X, -1, reach);
        puff.wallMaxX = this.findWall(level, puff, Direction.Axis.X, 1, reach);
        puff.wallMinY = this.findWall(level, puff, Direction.Axis.Y, -1, reach);
        puff.wallMaxY = this.findWall(level, puff, Direction.Axis.Y, 1, reach);
        puff.wallMinZ = this.findWall(level, puff, Direction.Axis.Z, -1, reach);
        puff.wallMaxZ = this.findWall(level, puff, Direction.Axis.Z, 1, reach);
    }

    /** COORDINATE OF THE NEAREST SURFACE FROM THE PUFF'S CENTER IN ONE DIRECTION, OR INFINITE IF NONE IS WITHIN reach. */
    private double findWall(ClientLevel level, Puff puff, Direction.Axis axis, int sign, double reach) {
        int blockX = Mth.floor(puff.x);
        int blockY = Mth.floor(puff.y);
        int blockZ = Mth.floor(puff.z);
        double center = axis.choose(puff.x, puff.y, puff.z);
        int start = axis.choose(blockX, blockY, blockZ);

        for (int step = 0; step <= WALL_SEARCH_BLOCKS; step++) {
            int cell = start + sign * step;
            // THE SIDE OF THIS BLOCK FACING THE PUFF
            double face = sign > 0 ? cell : cell + 1;
            if (step > 0 && Math.abs(face - center) > reach) {
                break;
            }

            this.mutablePos.set(
                axis == Direction.Axis.X ? cell : blockX,
                axis == Direction.Axis.Y ? cell : blockY,
                axis == Direction.Axis.Z ? cell : blockZ);
            BlockState state = level.getBlockState(this.mutablePos);
            if (state.isAir()) {
                continue;
            }

            if (state.isCollisionShapeFullBlock(level, this.mutablePos)) {
                // A PUFF INSIDE A BLOCK (E.G. ONE JUST PLACED ON IT) HAS NO ROOM AT ALL.
                return step == 0 ? center : face;
            }

            VoxelShape shape = state.getCollisionShape(level, this.mutablePos);
            if (shape.isEmpty()) {
                continue;
            }

            // ONLY BOXES THAT THE AXIS LINE THROUGH THE CENTER PASSES THROUGH, ON THIS SIDE OF IT.
            double localX = puff.x - this.mutablePos.getX();
            double localY = puff.y - this.mutablePos.getY();
            double localZ = puff.z - this.mutablePos.getZ();
            double local = center - cell;
            double nearest = sign > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
            for (AABB box : shape.toAabbs()) {
                if (!crossesLine(box, axis, localX, localY, localZ)) {
                    continue;
                }

                if (sign > 0 && box.min(axis) >= local) {
                    nearest = Math.min(nearest, box.min(axis));
                } else if (sign < 0 && box.max(axis) <= local) {
                    nearest = Math.max(nearest, box.max(axis));
                }
            }

            if (Double.isFinite(nearest)) {
                return cell + nearest;
            }
        }

        return sign > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
    }

    // TRUE IF THE LINE ALONG axis THROUGH THE (BLOCK-LOCAL) POINT PASSES THROUGH THE BOX
    private static boolean crossesLine(AABB box, Direction.Axis axis, double localX, double localY, double localZ) {
        return (axis == Direction.Axis.X || (localX >= box.minX && localX <= box.maxX))
            && (axis == Direction.Axis.Y || (localY >= box.minY && localY <= box.maxY))
            && (axis == Direction.Axis.Z || (localZ >= box.minZ && localZ <= box.maxZ));
    }

    /** response: HOW FAR (0-1) THE PUFF'S LIGHT MOVES TOWARD THE LIGHT AT ITS NEW POSITION. */
    private void sampleEnvironment(ClientLevel level, Puff puff, boolean hasSkyLight, float response) {
        this.mutablePos.set(Mth.floor(puff.x), Mth.floor(puff.y), Mth.floor(puff.z));

        // DIMENSIONS WITHOUT A SKY (NETHER) USE A FLAT AMBIENT TERM INSTEAD.
        float skyLight = hasSkyLight ? level.getBrightness(LightLayer.SKY, this.mutablePos) / 15.0F : 1.0F;
        float blockLight = level.getBrightness(LightLayer.BLOCK, this.mutablePos) / 15.0F;
        puff.skyLight += (skyLight - puff.skyLight) * response;
        puff.blockLight += (blockLight - puff.blockLight) * response;

        // GAS THAT DRIFTS INTO WATER OR LAVA BUBBLES AWAY QUICKLY.
        if (!level.getFluidState(this.mutablePos).isEmpty()) {
            puff.mass *= 0.86F;
            puff.velocityY += 0.01;
        }
    }

    /**
     * FOR EVERY PUFF, HOW MUCH GAS LIES BETWEEN ITS CENTER AND THE LIGHT, AND BETWEEN IT AND THE
     * OPEN SKY ABOVE (DENSITY x BLOCKS, BEFORE THE SHADER'S EXTINCTION). THE SHADER INTERPOLATES
     * THESE BETWEEN PUFFS, SO A WHOLE CLOUD SHADES ITSELF WITHOUT A LONG SHADOW MARCH PER PIXEL.
     */
    private void updateLightDepths() {
        int size = this.puffs.size();
        if (size == 0) {
            return;
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int puffIndex = 0; puffIndex < size; puffIndex++) {
            Puff puff = this.puffs.get(puffIndex);
            this.fieldX[puffIndex] = puff.x;
            this.fieldY[puffIndex] = puff.y;
            this.fieldZ[puffIndex] = puff.z;
            this.fieldInverseRadiusSquared[puffIndex] = 1.0F / (puff.radius * puff.radius);
            this.fieldDensity[puffIndex] = puff.density(1.0F);
            minX = Math.min(minX, puff.x - puff.radius);
            minY = Math.min(minY, puff.y - puff.radius);
            minZ = Math.min(minZ, puff.z - puff.radius);
            maxX = Math.max(maxX, puff.x + puff.radius);
            maxY = Math.max(maxY, puff.y + puff.radius);
            maxZ = Math.max(maxZ, puff.z + puff.radius);
        }

        this.fieldCount = size;
        this.fieldBounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);

        Vector3f light = this.lightDirection;
        for (Puff puff : this.puffs) {
            puff.skyDepth = this.opticalDepth(puff, 0.0, 1.0, 0.0);
            puff.sunDepth = this.hasDirectLight ? this.opticalDepth(puff, light.x, light.y, light.z) : 0.0F;
        }
    }

    private float opticalDepth(Puff puff, double dirX, double dirY, double dirZ) {
        AABB bounds = this.fieldBounds;
        float depth = 0.0F;
        double start = 0.0;
        double step = LIGHT_FIRST_STEP;
        for (int stepNumber = 0; stepNumber < LIGHT_STEPS; stepNumber++) {
            double distance = start + step * 0.5;
            double x = puff.x + dirX * distance;
            double y = puff.y + dirY * distance;
            double z = puff.z + dirZ * distance;
            // THE RAY STARTS INSIDE THE BOUNDS, SO ONCE IT LEAVES THEM THERE IS NO MORE GAS AHEAD.
            if (x < bounds.minX || x > bounds.maxX || y < bounds.minY || y > bounds.maxY || z < bounds.minZ || z > bounds.maxZ) {
                break;
            }

            depth += this.smoothDensity(x, y, z) * (float) step;
            start += step;
            step *= LIGHT_STEP_GROWTH;
        }

        return depth;
    }

    // THE SHADER'S METABALL FIELD WITHOUT ITS NOISE, TIMES HOW MUCH OF IT THE NOISE LEAVES
    private float smoothDensity(double x, double y, double z) {
        float weightSum = 0.0F;
        float densitySum = 0.0F;
        for (int puffIndex = 0; puffIndex < this.fieldCount; puffIndex++) {
            double offsetX = x - this.fieldX[puffIndex];
            double offsetY = y - this.fieldY[puffIndex];
            double offsetZ = z - this.fieldZ[puffIndex];
            float distanceSquared = (float) (offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ) * this.fieldInverseRadiusSquared[puffIndex];
            if (distanceSquared >= 1.0F) {
                continue;
            }

            float weight = 1.0F - distanceSquared;
            weight *= weight;
            weightSum += weight;
            densitySum += weight * this.fieldDensity[puffIndex];
        }

        if (weightSum <= 0.0F) {
            return 0.0F;
        }

        return Math.min(weightSum * FIELD_COVERAGE_SCALE, 1.0F) * densitySum / weightSum * SHADOW_FILL;
    }

    public static final class Puff {
        double x, y, z;
        double prevX, prevY, prevZ;
        double velocityX, velocityY, velocityZ;
        float radius, prevRadius;
        float mass, prevMass;
        float temperature;
        float skyLight, blockLight;
        float seed;
        int age;
        int lifetime;

        // NEAREST SOLID SURFACE ON EACH SIDE ALONG THE AXES (WORLD COORDINATES, INFINITE IF NONE IS CLOSE)
        double wallMinX = Double.NEGATIVE_INFINITY;
        double wallMinY = Double.NEGATIVE_INFINITY;
        double wallMinZ = Double.NEGATIVE_INFINITY;
        double wallMaxX = Double.POSITIVE_INFINITY;
        double wallMaxY = Double.POSITIVE_INFINITY;
        double wallMaxZ = Double.POSITIVE_INFINITY;

        // GAS BETWEEN THE CENTER AND THE LIGHT / THE SKY ABOVE (SEE updateLightDepths)
        float sunDepth;
        float skyDepth;

        // WHICH CLOUD THIS PUFF IS PART OF, AND WHICH SCREEN RECTANGLE DRAWS IT
        int cluster;
        int rect;

        void storePrevious() {
            this.prevX = this.x;
            this.prevY = this.y;
            this.prevZ = this.z;
            this.prevRadius = this.radius;
            this.prevMass = this.mass;
        }

        public double x(float partial) {
            return Mth.lerp(partial, this.prevX, this.x);
        }

        public double y(float partial) {
            return Mth.lerp(partial, this.prevY, this.y);
        }

        public double z(float partial) {
            return Mth.lerp(partial, this.prevZ, this.z);
        }

        public float radius(float partial) {
            return Mth.lerp(partial, this.prevRadius, this.radius);
        }

        /** VISIBLE DENSITY: MASS SPREAD OVER THE PUFF'S VOLUME, WITH FADE IN/OUT. */
        public float density(float partial) {
            float radius = this.radius(partial);
            float mass = Mth.lerp(partial, this.prevMass, this.mass);
            float age = this.age + partial;
            float fadeIn = Mth.clamp(age / 3.0F, 0.0F, 1.0F);
            float fadeOut = Mth.clamp((this.lifetime - age) / 60.0F, 0.0F, 1.0F);
            // SPREADING THE SAME MASS OVER A BIGGER PUFF THINS IT OUT. THE EXPONENT IS BELOW
            // THE PHYSICAL 3 BECAUSE THE VIEW RAY ALSO GETS LONGER THROUGH A BIGGER PUFF.
            return mass * dilution(radius) * fadeIn * fadeOut * fadeOut;
        }

        private static float dilution(float radius) {
            return (float) Math.pow(DILUTION_RADIUS / Math.max(radius, DILUTION_RADIUS), 0.85);
        }

        /**
         * MERGES ANOTHER PUFF INTO THIS ONE. VOLUME, MOMENTUM AND VOLUME-AVERAGED VISIBLE
         * DENSITY ARE CONSERVED, SO THE CLOUD KEEPS ROUGHLY THE SAME SHAPE AND OPACITY.
         */
        void absorb(Puff other) {
            float volumeA = this.radius * this.radius * this.radius;
            float volumeB = other.radius * other.radius * other.radius;
            float volumeWeightA = volumeA / (volumeA + volumeB);
            float volumeWeightB = 1.0F - volumeWeightA;
            float massWeightA = this.mass / (this.mass + other.mass);
            float massWeightB = 1.0F - massWeightA;
            if (massWeightB > massWeightA) {
                this.seed = other.seed;
            }

            float targetDensity = this.mass * dilution(this.radius) * volumeWeightA + other.mass * dilution(other.radius) * volumeWeightB;
            float prevTargetDensity = this.prevMass * dilution(this.prevRadius) * volumeWeightA + other.prevMass * dilution(other.prevRadius) * volumeWeightB;

            this.x = this.x * volumeWeightA + other.x * volumeWeightB;
            this.y = this.y * volumeWeightA + other.y * volumeWeightB;
            this.z = this.z * volumeWeightA + other.z * volumeWeightB;
            this.prevX = this.prevX * volumeWeightA + other.prevX * volumeWeightB;
            this.prevY = this.prevY * volumeWeightA + other.prevY * volumeWeightB;
            this.prevZ = this.prevZ * volumeWeightA + other.prevZ * volumeWeightB;
            this.velocityX = this.velocityX * massWeightA + other.velocityX * massWeightB;
            this.velocityY = this.velocityY * massWeightA + other.velocityY * massWeightB;
            this.velocityZ = this.velocityZ * massWeightA + other.velocityZ * massWeightB;

            this.radius = Math.min((float) Math.cbrt(volumeA + volumeB), MAX_RADIUS);
            float prevVolume = this.prevRadius * this.prevRadius * this.prevRadius
                + other.prevRadius * other.prevRadius * other.prevRadius;
            this.prevRadius = Math.min((float) Math.cbrt(prevVolume), MAX_RADIUS);
            this.mass = targetDensity / dilution(this.radius);
            this.prevMass = prevTargetDensity / dilution(this.prevRadius);

            this.temperature = this.temperature * massWeightA + other.temperature * massWeightB;
            this.skyLight = this.skyLight * volumeWeightA + other.skyLight * volumeWeightB;
            this.blockLight = this.blockLight * volumeWeightA + other.blockLight * volumeWeightB;
            this.sunDepth = this.sunDepth * volumeWeightA + other.sunDepth * volumeWeightB;
            this.skyDepth = this.skyDepth * volumeWeightA + other.skyDepth * volumeWeightB;

            // LIVE AS LONG AS THE LONGER-LIVED HALF. TAKE THE OLDER AGE, SO MERGING INTO A PUFF THAT
            // IS STILL FADING IN (WHEN NOTHING SETTLED IS LEFT TO MERGE) DOESN'T BLINK THE RESULT.
            int remaining = Math.max(this.lifetime - this.age, other.lifetime - other.age);
            this.age = Math.max(this.age, other.age);
            this.lifetime = this.age + remaining;
        }
    }

    private static final class Emitter {
        final Vec3 origin;
        // NULL FOR A BURST
        final @Nullable Vec3 direction;
        final int puffLifetime;
        final int lifetimeSpread;
        // SEEDED BY THE SERVER, SO EVERY PLAYER'S COPY OF THIS FART RELEASES THE SAME PUFFS
        final RandomSource random;
        int age;

        Emitter(Vec3 origin, @Nullable Vec3 direction, int puffLifetime, int lifetimeSpread, int seed) {
            this.origin = origin;
            this.direction = direction;
            this.puffLifetime = puffLifetime;
            this.lifetimeSpread = lifetimeSpread;
            this.random = RandomSource.create(seed);
        }
    }
}
