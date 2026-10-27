package com.github.sethcg.buttcraft.client.gas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import com.github.sethcg.buttcraft.FartBreeze;

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
 *   <li>A PUFF BUDGET: WHEN FULL, THE MOST-OVERLAPPING SETTLED PUFFS MERGE INTO ONE</li>
 * </ul>
 *
 * <p>EVERY FART'S RANDOMNESS COMES FROM THE SEED THE SERVER SENT, AND THE TURBULENCE AND BREEZE
 * FOLLOW THE GAME TIME, SO EVERY PLAYER SEES MUCH THE SAME CLOUD.
 */
public final class FartGasSimulation {

    /** HARD CAP, SIZED TO THE SHADER'S PUFF ARRAY. THE ACTIVE BUDGET MAY BE LOWER. */
    public static final int MAX_PUFFS = 64;

    /** HOW MANY SCREEN RECTANGLES THE GAS IS DRAWN IN, SIZED TO THE SHADER'S RectEnds. */
    public static final int MAX_RECTS = 4;

    // EMISSION
    private static final int EMIT_TICKS = 7;
    private static final float INITIAL_RADIUS = 0.28F;
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
    private static final float DIFFUSION = 0.0065F;
    private static final float ENTRAINMENT = 0.16F;
    private static final float MAX_RADIUS = 1.9F;

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
    private static final float FIELD_COVERAGE_SCALE = 1.35F;
    private static final float SHADOW_FILL = 0.5F;

    // WALLS: LOOK THIS FAR PAST A PUFF'S RADIUS FOR THE NEAREST SURFACE ON EACH SIDE, SO THEY
    // STILL COVER THE PUFF AS IT MOVES BETWEEN TICKS.
    private static final double WALL_SEARCH_MARGIN = 0.5;
    private static final int WALL_SEARCH_BLOCKS = 3;

    // CLUSTERS: PUFFS WHOSE SURFACES ARE CLOSER THAN THIS BELONG TO THE SAME CLOUD.
    private static final double CLUSTER_GAP = 1.0;

    // MERGING: ONLY PUFFS THIS OLD MERGE, SO THE TURBULENT JET KEEPS ITS SMALL PUFFS.
    private static final int MERGE_MIN_AGE = 12;

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
    private final float[] fieldInverseRadius2 = new float[MAX_PUFFS];
    private final float[] fieldDensity = new float[MAX_PUFFS];
    private int fieldCount;
    private @Nullable AABB fieldBounds;

    public void emit(Vec3 pos, float yaw, int seed) {
        double angle = Math.toRadians(yaw);
        Vec3 backward = new Vec3(Math.sin(angle), 0.0, -Math.cos(angle));
        // JUST OUTSIDE THE BACK OF THE PLAYER'S HITBOX (HALF WIDTH 0.3), AT HIP HEIGHT.
        Vec3 origin = pos.add(backward.x * 0.36, 0.7, backward.z * 0.36);
        this.emitters.add(new Emitter(origin, backward, PUFF_LIFETIME, PUFF_LIFETIME_SPREAD, 1.0F, seed));
    }

    /**
     * A FART THAT SPREADS OUT EVENLY IN EVERY DIRECTION FROM A POINT (E.G. A SPLASH POTION),
     * LINGERING ABOUT AS LONG AS THE SERVER'S POISON CLOUD (lifetimeTicks). size SCALES HOW
     * MUCH GAS THERE IS AND HOW FAR IT SPREADS (1 = A NORMAL FART).
     */
    public void emitBurst(Vec3 pos, int lifetimeTicks, float size, int seed) {
        // SHORT CLOUDS FADE OUT OVER LESS TIME, SO THEY DON'T OUTLAST THE POISON BY MUCH.
        int lifetimeSpread = Math.max(1, Math.min(PUFF_LIFETIME_SPREAD, lifetimeTicks / 2));
        int puffLifetime = Math.max(1, lifetimeTicks - lifetimeSpread / 3);
        this.emitters.add(new Emitter(pos, null, puffLifetime, lifetimeSpread, size, seed));
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
        double t = gameTime * 0.045;

        for (int i = this.puffs.size() - 1; i >= 0; i--) {
            Puff puff = this.puffs.get(i);
            puff.storePrevious();
            puff.age++;

            if (puff.age >= puff.lifetime || puff.mass < 0.01F) {
                this.puffs.remove(i);
                continue;
            }

            // DRAG: LINEAR + QUADRATIC (TURBULENT) TERMS.
            double speed = Math.sqrt(puff.vx * puff.vx + puff.vy * puff.vy + puff.vz * puff.vz);
            double drag = LINEAR_DRAG / (1.0 + QUADRATIC_DRAG * speed);
            puff.vx *= drag;
            puff.vy *= drag;
            puff.vz *= drag;

            // BUOYANCY: WARM GAS RISES, THEN SLOWLY SETTLES ONCE IT COOLS.
            puff.temperature *= COOLING;
            puff.vy += BUOYANCY * puff.temperature - SETTLING;

            // TURBULENCE: SMOOTH, DIVERGENCE-FREE-ISH SWIRL FROM LAYERED SINES.
            double px = puff.x * 0.9 + puff.seed;
            double py = puff.y * 0.9;
            double pz = puff.z * 0.9 - puff.seed;
            double turbulence = TURBULENCE * (0.6 + Math.min(1.0, puff.age / 30.0));
            puff.vx += (Math.sin(py * 1.7 + t) - Math.cos(pz * 1.3 - t * 0.8)) * turbulence;
            puff.vy += (Math.sin(pz * 1.5 + t * 1.1) - Math.cos(px * 1.9 + t * 0.6)) * turbulence * 0.6;
            puff.vz += (Math.sin(px * 1.4 - t * 0.9) - Math.cos(py * 1.6 + t * 1.2)) * turbulence;

            // AMBIENT BREEZE (ONLY OUTDOORS).
            double exposure = FartBreeze.exposure(hasSkyLight, puff.skyLight);
            puff.vx += breezeX * exposure;
            puff.vz += breezeZ * exposure;

            this.moveWithCollisions(level, puff);

            // GROWTH: MOLECULAR/TURBULENT DIFFUSION + ENTRAINMENT FROM MOTION.
            float r = puff.radius;
            r = Mth.sqrt(r * r + DIFFUSION) + (float) (speed * ENTRAINMENT);
            puff.radius = Math.min(r, MAX_RADIUS);

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

        // A LOWERED BUDGET (SETTING CHANGED MID-CLOUD) IS REACHED BY MERGING AS PUFFS SPAWN.
        int budget = Mth.clamp(FartGasSettings.current().puffBudget(), 1, MAX_PUFFS);
        for (int i = this.emitters.size() - 1; i >= 0; i--) {
            Emitter emitter = this.emitters.get(i);
            // A BURST RELEASES MORE GAS AT ONCE, SINCE IT SPREADS OUT IN EVERY DIRECTION.
            int count = emitter.direction == null
                ? (emitter.age < 4 ? 4 : 3)
                : (emitter.age < 4 ? 3 : 2);
            count = Math.max(1, Math.round(count * emitter.size));
            for (int n = 0; n < count; n++) {
                this.spawnPuff(level, emitter, budget);
            }

            emitter.age++;
            if (emitter.age >= EMIT_TICKS) {
                this.emitters.remove(i);
            }
        }
    }

    private void spawnPuff(ClientLevel level, Emitter emitter, int budget) {
        while (this.puffs.size() >= budget) {
            if (!this.mergeClosestPair()) {
                this.puffs.remove(0);
            }
        }

        RandomSource random = emitter.random;

        // JET STRENGTH DECAYS OVER THE EMISSION.
        double strength = 1.0 - emitter.age / (double) EMIT_TICKS * 0.65;
        double speed = Mth.lerp(random.nextDouble(), JET_SPEED_MIN, JET_SPEED_MAX) * strength * emitter.size;

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
        puff.vx = dir.x * speed;
        puff.vy = dir.y * speed;
        puff.vz = dir.z * speed;
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
        for (int i = 0; i < size; i++) {
            Puff a = this.puffs.get(i);
            for (int j = i + 1; j < size; j++) {
                Puff b = this.puffs.get(j);
                double dx = b.x - a.x;
                double dy = b.y - a.y;
                double dz = b.z - a.z;
                double minDistance = (a.radius + b.radius) * 0.45;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 >= minDistance * minDistance) {
                    continue;
                }

                double d = Math.sqrt(d2);
                if (d < 1.0E-4) {
                    dx = this.random.nextGaussian();
                    dy = this.random.nextGaussian() * 0.3;
                    dz = this.random.nextGaussian();
                    d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                }

                double push = (1.0 - d / minDistance) * PRESSURE / d;
                double ma = a.mass;
                double mb = b.mass;
                double total = ma + mb;
                a.vx -= dx * push * mb / total;
                a.vy -= dy * push * mb / total;
                a.vz -= dz * push * mb / total;
                b.vx += dx * push * ma / total;
                b.vy += dy * push * ma / total;
                b.vz += dz * push * ma / total;
            }
        }
    }

    /**
     * MERGES THE SETTLED PAIR WITH THE MOST OVERLAP (SMALLEST DISTANCE RELATIVE TO
     * THEIR RADII) INTO ONE BIGGER PUFF. RETURNS FALSE IF NO PAIR IS OLD ENOUGH.
     */
    private boolean mergeClosestPair() {
        int size = this.puffs.size();
        int bestA = -1;
        int bestB = -1;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            Puff a = this.puffs.get(i);
            if (a.age < MERGE_MIN_AGE) {
                continue;
            }

            for (int j = i + 1; j < size; j++) {
                Puff b = this.puffs.get(j);
                if (b.age < MERGE_MIN_AGE) {
                    continue;
                }

                double dx = b.x - a.x;
                double dy = b.y - a.y;
                double dz = b.z - a.z;
                double radii = a.radius + b.radius;
                double score = (dx * dx + dy * dy + dz * dz) / (radii * radii);
                if (score < best) {
                    best = score;
                    bestA = i;
                    bestB = j;
                }
            }
        }

        if (bestA < 0) {
            return false;
        }

        Puff b = this.puffs.remove(bestB);
        this.puffs.get(bestA).absorb(b);
        return true;
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
        for (int i = 0; i < size; i++) {
            parent[i] = i;
        }

        for (int i = 0; i < size; i++) {
            Puff a = this.puffs.get(i);
            for (int j = i + 1; j < size; j++) {
                Puff b = this.puffs.get(j);
                double dx = b.x - a.x;
                double dy = b.y - a.y;
                double dz = b.z - a.z;
                double reach = a.radius + b.radius + CLUSTER_GAP;
                if (dx * dx + dy * dy + dz * dz < reach * reach) {
                    int rootA = this.findRoot(i);
                    int rootB = this.findRoot(j);
                    // THE ROOT IS ALWAYS THE CLUSTER'S FIRST PUFF
                    parent[Math.max(rootA, rootB)] = Math.min(rootA, rootB);
                }
            }
        }

        // NUMBER THE CLUSTERS IN ORDER OF THEIR FIRST PUFF
        int count = 0;
        for (int i = 0; i < size; i++) {
            Puff puff = this.puffs.get(i);
            int root = this.findRoot(i);
            puff.cluster = root == i ? count++ : this.puffs.get(root).cluster;
        }

        // GROUP THE PUFF INDICES BY CLUSTER (COUNTING SORT). parent IS FREE TO REUSE NOW.
        this.clusterCount = count;
        Arrays.fill(this.clusterStart, 0, count + 1, 0);
        for (int i = 0; i < size; i++) {
            this.clusterStart[this.puffs.get(i).cluster + 1]++;
        }

        for (int c = 0; c < count; c++) {
            this.clusterStart[c + 1] += this.clusterStart[c];
            parent[c] = this.clusterStart[c];
        }

        for (int i = 0; i < size; i++) {
            this.clusterOrder[parent[this.puffs.get(i).cluster]++] = i;
        }

        this.assignRects();
    }

    private int findRoot(int i) {
        int[] parent = this.clusterParent;
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }

        return i;
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
        int[] group = new int[count];
        for (int c = 0; c < count; c++) {
            boxes[c] = this.clusterBounds(c);
            group[c] = c;
        }

        // boxes[g] IS NULL ONCE GROUP g HAS BEEN MERGED INTO ANOTHER
        for (int groups = count; groups > MAX_RECTS; groups--) {
            int bestA = -1;
            int bestB = -1;
            double best = Double.MAX_VALUE;
            for (int a = 0; a < count; a++) {
                if (boxes[a] == null) {
                    continue;
                }

                for (int b = a + 1; b < count; b++) {
                    if (boxes[b] == null) {
                        continue;
                    }

                    double gap = gapSqr(boxes[a], boxes[b]);
                    if (gap < best) {
                        best = gap;
                        bestA = a;
                        bestB = b;
                    }
                }
            }

            boxes[bestA] = boxes[bestA].minmax(boxes[bestB]);
            boxes[bestB] = null;
            for (int c = 0; c < count; c++) {
                if (group[c] == bestB) {
                    group[c] = bestA;
                }
            }
        }

        // NUMBER THE SURVIVING GROUPS 0..MAX_RECTS-1
        int[] rect = new int[count];
        int rects = 0;
        for (int g = 0; g < count; g++) {
            if (boxes[g] != null) {
                rect[g] = rects++;
            }
        }

        for (Puff puff : this.puffs) {
            puff.rect = rect[group[puff.cluster]];
        }
    }

    private AABB clusterBounds(int cluster) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int k = this.clusterStart[cluster]; k < this.clusterStart[cluster + 1]; k++) {
            Puff puff = this.puffs.get(this.clusterOrder[k]);
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
    private static double gapSqr(AABB a, AABB b) {
        double dx = Math.max(0.0, Math.max(a.minX - b.maxX, b.minX - a.maxX));
        double dy = Math.max(0.0, Math.max(a.minY - b.maxY, b.minY - a.maxY));
        double dz = Math.max(0.0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    private void applyEntityStirring(ClientLevel level) {
        // ONE SEARCH PER CLOUD, SO TWO CLOUDS FAR APART DON'T SCAN EVERY ENTITY BETWEEN THEM.
        for (int c = 0; c < this.clusterCount; c++) {
            int start = this.clusterStart[c];
            int end = this.clusterStart[c + 1];
            AABB bounds = this.clusterBounds(c).inflate(1.0);
            List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, bounds, Entity::isAlive);
            for (LivingEntity entity : entities) {
                double evx = entity.getX() - entity.xo;
                double evy = entity.getY() - entity.yo;
                double evz = entity.getZ() - entity.zo;
                // ONLY MOVING BODIES DRAG GAS ALONG; A STANDING ONE MUST NOT STALL THE JET.
                double entitySpeed = Math.sqrt(evx * evx + evy * evy + evz * evz);
                double dragAlong = Math.min(1.0, entitySpeed / 0.08);
                AABB box = entity.getBoundingBox();
                double cx = (box.minX + box.maxX) * 0.5;
                double cy = (box.minY + box.maxY) * 0.5;
                double cz = (box.minZ + box.maxZ) * 0.5;
                double reach = Math.max(box.getXsize(), box.getYsize()) * 0.5 + 0.35;

                for (int k = start; k < end; k++) {
                    Puff puff = this.puffs.get(this.clusterOrder[k]);
                    double dx = puff.x - cx;
                    double dy = puff.y - cy;
                    double dz = puff.z - cz;
                    double influence = reach + puff.radius;
                    double d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 >= influence * influence) {
                        continue;
                    }

                    double d = Math.sqrt(d2) + 1.0E-4;
                    double w = 1.0 - d / influence;
                    w *= w;

                    // DRAG ALONG WITH A MOVING BODY + DISPLACE SIDEWAYS OUT OF ITS VOLUME (WAKE).
                    double horizontal = Math.sqrt(dx * dx + dz * dz) + 1.0E-4;
                    double drag = w * 0.3 * dragAlong;
                    puff.vx += (evx * 0.7 - puff.vx) * drag + dx / horizontal * w * 0.01;
                    puff.vy += (evy * 0.5 - puff.vy) * drag * 0.6;
                    puff.vz += (evz * 0.7 - puff.vz) * drag + dz / horizontal * w * 0.01;
                }
            }
        }
    }

    private void moveWithCollisions(ClientLevel level, Puff puff) {
        // PROBE A LITTLE AHEAD OF THE CENTER SO GAS STOPS AT SURFACES RATHER THAN SINKING INTO THEM.
        double probe = Math.min(puff.radius * 0.45, 0.45);

        double nx = puff.x + puff.vx;
        if (this.isSolid(level, nx + Math.signum(puff.vx) * probe, puff.y, puff.z)) {
            double hit = puff.vx;
            puff.vx = -hit * 0.12;
            puff.vy += Math.abs(hit) * 0.08;
            puff.vz += Math.abs(hit) * 0.25 * (puff.vz >= 0.0 ? 1.0 : -1.0);
        } else {
            puff.x = nx;
        }

        double ny = puff.y + puff.vy;
        if (this.isSolid(level, puff.x, ny + Math.signum(puff.vy) * probe, puff.z)) {
            // HITTING A FLOOR/CEILING SPREADS THE GAS SIDEWAYS LIKE A POOL.
            double hit = Math.abs(puff.vy);
            puff.vy = -puff.vy * 0.1;
            double spread = hit * 0.6;
            double horizontal = Math.sqrt(puff.vx * puff.vx + puff.vz * puff.vz);
            if (horizontal > 1.0E-4) {
                puff.vx += puff.vx / horizontal * spread;
                puff.vz += puff.vz / horizontal * spread;
            } else {
                double a = puff.seed * 7.0;
                puff.vx += Math.cos(a) * spread;
                puff.vz += Math.sin(a) * spread;
            }
        } else {
            puff.y = ny;
        }

        double nz = puff.z + puff.vz;
        if (this.isSolid(level, puff.x, puff.y, nz + Math.signum(puff.vz) * probe)) {
            double hit = puff.vz;
            puff.vz = -hit * 0.12;
            puff.vy += Math.abs(hit) * 0.08;
            puff.vx += Math.abs(hit) * 0.25 * (puff.vx >= 0.0 ? 1.0 : -1.0);
        } else {
            puff.z = nz;
        }

        // ESCAPE IF A BLOCK APPEARED AROUND THE PUFF (E.G. PLACED BY A PLAYER).
        if (this.isSolid(level, puff.x, puff.y, puff.z)) {
            puff.vy += 0.03;
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

        double lx = x - this.mutablePos.getX();
        double ly = y - this.mutablePos.getY();
        double lz = z - this.mutablePos.getZ();

        // CHEAP REJECT (E.G. ABOVE A SLAB) BEFORE toAabbs() BUILDS A LIST OF BOXES.
        AABB bounds = shape.bounds();
        if (lx < bounds.minX || lx > bounds.maxX || ly < bounds.minY || ly > bounds.maxY || lz < bounds.minZ || lz > bounds.maxZ) {
            return false;
        }

        for (AABB box : shape.toAabbs()) {
            if (lx >= box.minX && lx <= box.maxX && ly >= box.minY && ly <= box.maxY && lz >= box.minZ && lz <= box.maxZ) {
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
        int bx = Mth.floor(puff.x);
        int by = Mth.floor(puff.y);
        int bz = Mth.floor(puff.z);
        double center = axis.choose(puff.x, puff.y, puff.z);
        int start = axis.choose(bx, by, bz);

        for (int k = 0; k <= WALL_SEARCH_BLOCKS; k++) {
            int cell = start + sign * k;
            // THE SIDE OF THIS BLOCK FACING THE PUFF
            double face = sign > 0 ? cell : cell + 1;
            if (k > 0 && Math.abs(face - center) > reach) {
                break;
            }

            this.mutablePos.set(
                axis == Direction.Axis.X ? cell : bx,
                axis == Direction.Axis.Y ? cell : by,
                axis == Direction.Axis.Z ? cell : bz);
            BlockState state = level.getBlockState(this.mutablePos);
            if (state.isAir()) {
                continue;
            }

            if (state.isCollisionShapeFullBlock(level, this.mutablePos)) {
                // A PUFF INSIDE A BLOCK (E.G. ONE JUST PLACED ON IT) HAS NO ROOM AT ALL.
                return k == 0 ? center : face;
            }

            VoxelShape shape = state.getCollisionShape(level, this.mutablePos);
            if (shape.isEmpty()) {
                continue;
            }

            // ONLY BOXES THAT THE AXIS LINE THROUGH THE CENTER PASSES THROUGH, ON THIS SIDE OF IT.
            double lx = puff.x - this.mutablePos.getX();
            double ly = puff.y - this.mutablePos.getY();
            double lz = puff.z - this.mutablePos.getZ();
            double local = center - cell;
            double nearest = sign > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
            for (AABB box : shape.toAabbs()) {
                if (!crossesLine(box, axis, lx, ly, lz)) {
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
    private static boolean crossesLine(AABB box, Direction.Axis axis, double lx, double ly, double lz) {
        return (axis == Direction.Axis.X || (lx >= box.minX && lx <= box.maxX))
            && (axis == Direction.Axis.Y || (ly >= box.minY && ly <= box.maxY))
            && (axis == Direction.Axis.Z || (lz >= box.minZ && lz <= box.maxZ));
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
            puff.vy += 0.01;
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
        for (int i = 0; i < size; i++) {
            Puff puff = this.puffs.get(i);
            this.fieldX[i] = puff.x;
            this.fieldY[i] = puff.y;
            this.fieldZ[i] = puff.z;
            this.fieldInverseRadius2[i] = 1.0F / (puff.radius * puff.radius);
            this.fieldDensity[i] = puff.density(1.0F);
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

    private float opticalDepth(Puff puff, double dx, double dy, double dz) {
        AABB bounds = this.fieldBounds;
        float depth = 0.0F;
        double start = 0.0;
        double step = LIGHT_FIRST_STEP;
        for (int s = 0; s < LIGHT_STEPS; s++) {
            double t = start + step * 0.5;
            double x = puff.x + dx * t;
            double y = puff.y + dy * t;
            double z = puff.z + dz * t;
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
        for (int i = 0; i < this.fieldCount; i++) {
            double dx = x - this.fieldX[i];
            double dy = y - this.fieldY[i];
            double dz = z - this.fieldZ[i];
            float d2 = (float) (dx * dx + dy * dy + dz * dz) * this.fieldInverseRadius2[i];
            if (d2 >= 1.0F) {
                continue;
            }

            float w = 1.0F - d2;
            w *= w;
            weightSum += w;
            densitySum += w * this.fieldDensity[i];
        }

        if (weightSum <= 0.0F) {
            return 0.0F;
        }

        return Math.min(weightSum * FIELD_COVERAGE_SCALE, 1.0F) * densitySum / weightSum * SHADOW_FILL;
    }

    public static final class Puff {
        double x, y, z;
        double prevX, prevY, prevZ;
        double vx, vy, vz;
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
            float r = this.radius(partial);
            float mass = Mth.lerp(partial, this.prevMass, this.mass);
            float age = this.age + partial;
            float fadeIn = Mth.clamp(age / 3.0F, 0.0F, 1.0F);
            float fadeOut = Mth.clamp((this.lifetime - age) / 60.0F, 0.0F, 1.0F);
            // SPREADING THE SAME MASS OVER A BIGGER PUFF THINS IT OUT. THE EXPONENT IS BELOW
            // THE PHYSICAL 3 BECAUSE THE VIEW RAY ALSO GETS LONGER THROUGH A BIGGER PUFF.
            return mass * dilution(r) * fadeIn * fadeOut * fadeOut;
        }

        private static float dilution(float radius) {
            return (float) Math.pow(INITIAL_RADIUS * 2.0F / Math.max(radius, INITIAL_RADIUS * 2.0F), 0.85);
        }

        /**
         * MERGES ANOTHER PUFF INTO THIS ONE. VOLUME, MOMENTUM AND VOLUME-AVERAGED VISIBLE
         * DENSITY ARE CONSERVED, SO THE CLOUD KEEPS ROUGHLY THE SAME SHAPE AND OPACITY.
         */
        void absorb(Puff other) {
            float va = this.radius * this.radius * this.radius;
            float vb = other.radius * other.radius * other.radius;
            float wa = va / (va + vb);
            float wb = 1.0F - wa;
            float massWeightA = this.mass / (this.mass + other.mass);
            float massWeightB = 1.0F - massWeightA;
            if (massWeightB > massWeightA) {
                this.seed = other.seed;
            }

            float targetDensity = this.mass * dilution(this.radius) * wa + other.mass * dilution(other.radius) * wb;
            float prevTargetDensity = this.prevMass * dilution(this.prevRadius) * wa + other.prevMass * dilution(other.prevRadius) * wb;

            this.x = this.x * wa + other.x * wb;
            this.y = this.y * wa + other.y * wb;
            this.z = this.z * wa + other.z * wb;
            this.prevX = this.prevX * wa + other.prevX * wb;
            this.prevY = this.prevY * wa + other.prevY * wb;
            this.prevZ = this.prevZ * wa + other.prevZ * wb;
            this.vx = this.vx * massWeightA + other.vx * massWeightB;
            this.vy = this.vy * massWeightA + other.vy * massWeightB;
            this.vz = this.vz * massWeightA + other.vz * massWeightB;

            this.radius = Math.min((float) Math.cbrt(va + vb), MAX_RADIUS);
            float prevVolume = this.prevRadius * this.prevRadius * this.prevRadius
                + other.prevRadius * other.prevRadius * other.prevRadius;
            this.prevRadius = Math.min((float) Math.cbrt(prevVolume), MAX_RADIUS);
            this.mass = targetDensity / dilution(this.radius);
            this.prevMass = prevTargetDensity / dilution(this.prevRadius);

            this.temperature = this.temperature * massWeightA + other.temperature * massWeightB;
            this.skyLight = this.skyLight * wa + other.skyLight * wb;
            this.blockLight = this.blockLight * wa + other.blockLight * wb;
            this.sunDepth = this.sunDepth * wa + other.sunDepth * wb;
            this.skyDepth = this.skyDepth * wa + other.skyDepth * wb;

            // LIVE AS LONG AS THE LONGER-LIVED HALF.
            int remaining = Math.max(this.lifetime - this.age, other.lifetime - other.age);
            this.age = Math.min(this.age, other.age);
            this.lifetime = this.age + remaining;
        }
    }

    private static final class Emitter {
        final Vec3 origin;
        // NULL FOR A BURST
        final @Nullable Vec3 direction;
        final int puffLifetime;
        final int lifetimeSpread;
        final float size;
        // SEEDED BY THE SERVER, SO EVERY PLAYER'S COPY OF THIS FART RELEASES THE SAME PUFFS
        final RandomSource random;
        int age;

        Emitter(Vec3 origin, @Nullable Vec3 direction, int puffLifetime, int lifetimeSpread, float size, int seed) {
            this.origin = origin;
            this.direction = direction;
            this.puffLifetime = puffLifetime;
            this.lifetimeSpread = lifetimeSpread;
            this.size = size;
            this.random = RandomSource.create(seed);
        }
    }
}
