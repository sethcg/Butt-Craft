package com.github.sethcg.buttcraft.client.gas;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
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
 *   <li>SMOOTH PSEUDO-RANDOM TURBULENCE AND A SLOWLY VARYING BREEZE</li>
 *   <li>PRESSURE BETWEEN OVERLAPPING PUFFS SO THE CLOUD SPREADS OUT</li>
 *   <li>BLOCK COLLISIONS THAT DEFLECT AND SPREAD GAS ALONG SURFACES</li>
 *   <li>ENTITIES STIRRING THE GAS AS THEY MOVE THROUGH IT</li>
 *   <li>PER-PUFF SKY/BLOCK LIGHT SAMPLING FOR LIGHTING</li>
 *   <li>A PUFF BUDGET: WHEN FULL, THE MOST-OVERLAPPING SETTLED PUFFS MERGE INTO ONE</li>
 * </ul>
 */
public final class FartGasSimulation {

    /** HARD CAP, SIZED TO THE SHADER'S PUFF ARRAY. THE ACTIVE BUDGET MAY BE LOWER. */
    public static final int MAX_PUFFS = 64;

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
    private static final double BREEZE = 0.0011;
    private static final double PRESSURE = 0.008;

    // GROWTH / DISSIPATION
    private static final float DIFFUSION = 0.0065F;
    private static final float ENTRAINMENT = 0.16F;
    private static final float MAX_RADIUS = 1.9F;

    // MERGING: ONLY PUFFS THIS OLD MERGE, SO THE TURBULENT JET KEEPS ITS SMALL PUFFS.
    private static final int MERGE_MIN_AGE = 12;

    private final List<Puff> puffs = new ArrayList<>();
    private final List<Emitter> emitters = new ArrayList<>();
    private final RandomSource random = RandomSource.create();
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private long ticks;

    public void emit(double x, double y, double z, float yaw) {
        double angle = Math.toRadians(yaw);
        Vec3 backward = new Vec3(Math.sin(angle), 0.0, -Math.cos(angle));
        // JUST OUTSIDE THE BACK OF THE PLAYER'S HITBOX (HALF WIDTH 0.3), AT HIP HEIGHT.
        Vec3 origin = new Vec3(x, y + 0.7, z).add(backward.scale(0.36));
        this.emitters.add(new Emitter(origin, backward));
    }

    public boolean isEmpty() {
        return this.puffs.isEmpty() && this.emitters.isEmpty();
    }

    public List<Puff> puffs() {
        return this.puffs;
    }

    public void clear() {
        this.puffs.clear();
        this.emitters.clear();
    }

    public void tick(ClientLevel level) {
        this.ticks++;
        this.tickEmitters(level);
        if (this.puffs.isEmpty()) {
            return;
        }

        this.applyPressure();
        this.applyEntityStirring(level);

        boolean hasSkyLight = level.dimensionType().hasSkyLight();
        double breezeAngle = this.ticks * 0.0007 + Math.sin(this.ticks * 0.00023) * 2.0;
        double breezeX = Math.cos(breezeAngle) * BREEZE;
        double breezeZ = Math.sin(breezeAngle) * BREEZE;

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
            double t = this.ticks * 0.045;
            double px = puff.x * 0.9 + puff.seed;
            double py = puff.y * 0.9;
            double pz = puff.z * 0.9 - puff.seed;
            double turbulence = TURBULENCE * (0.6 + Math.min(1.0, puff.age / 30.0));
            puff.vx += (Math.sin(py * 1.7 + t) - Math.cos(pz * 1.3 - t * 0.8)) * turbulence;
            puff.vy += (Math.sin(pz * 1.5 + t * 1.1) - Math.cos(px * 1.9 + t * 0.6)) * turbulence * 0.6;
            puff.vz += (Math.sin(px * 1.4 - t * 0.9) - Math.cos(py * 1.6 + t * 1.2)) * turbulence;

            // AMBIENT BREEZE (ONLY OUTDOORS).
            float outdoor = hasSkyLight ? puff.skyLight * puff.skyLight : 0.0F;
            puff.vx += breezeX * outdoor;
            puff.vz += breezeZ * outdoor;

            this.moveWithCollisions(level, puff);

            // GROWTH: MOLECULAR/TURBULENT DIFFUSION + ENTRAINMENT FROM MOTION.
            float r = puff.radius;
            r = Mth.sqrt(r * r + DIFFUSION) + (float) (speed * ENTRAINMENT);
            puff.radius = Math.min(r, MAX_RADIUS);

            // DISSIPATION: GAS SLOWLY MIXES BELOW VISIBILITY.
            puff.mass *= 0.997F;
            this.sampleEnvironment(level, puff, hasSkyLight);
        }
    }

    private void tickEmitters(ClientLevel level) {
        for (int i = this.emitters.size() - 1; i >= 0; i--) {
            Emitter emitter = this.emitters.get(i);
            int count = emitter.age < 4 ? 3 : 2;
            for (int n = 0; n < count; n++) {
                this.spawnPuff(level, emitter);
            }

            emitter.age++;
            if (emitter.age >= EMIT_TICKS) {
                this.emitters.remove(i);
            }
        }
    }

    private void spawnPuff(ClientLevel level, Emitter emitter) {
        // A LOWERED BUDGET (SETTING CHANGED MID-CLOUD) IS REACHED BY MERGING HERE TOO.
        int budget = Mth.clamp(FartGasSettings.current().puffBudget(), 1, MAX_PUFFS);
        while (this.puffs.size() >= budget) {
            if (!this.mergeClosestPair()) {
                this.puffs.remove(0);
            }
        }

        // JET STRENGTH DECAYS OVER THE EMISSION.
        double strength = 1.0 - emitter.age / (double) EMIT_TICKS * 0.65;
        double speed = Mth.lerp(this.random.nextDouble(), JET_SPEED_MIN, JET_SPEED_MAX) * strength;

        Vec3 dir = emitter.direction
            .add(this.random.nextGaussian() * JET_SPREAD * 0.5,
                -0.12 + this.random.nextGaussian() * JET_SPREAD * 0.35,
                this.random.nextGaussian() * JET_SPREAD * 0.5)
            .normalize();

        Puff puff = new Puff();
        puff.x = emitter.origin.x + this.random.nextGaussian() * 0.03;
        puff.y = emitter.origin.y + this.random.nextGaussian() * 0.03;
        puff.z = emitter.origin.z + this.random.nextGaussian() * 0.03;
        puff.vx = dir.x * speed;
        puff.vy = dir.y * speed;
        puff.vz = dir.z * speed;
        puff.radius = INITIAL_RADIUS * (0.8F + this.random.nextFloat() * 0.5F);
        puff.temperature = 1.0F;
        puff.mass = 0.85F + this.random.nextFloat() * 0.3F;
        puff.lifetime = 150 + this.random.nextInt(90);
        puff.seed = this.random.nextFloat() * 64.0F;
        this.sampleEnvironment(level, puff, level.dimensionType().hasSkyLight());
        puff.skyLight = puff.targetSkyLight;
        puff.blockLight = puff.targetBlockLight;
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

    private void applyEntityStirring(ClientLevel level) {
        AABB bounds = null;
        for (Puff puff : this.puffs) {
            AABB box = new AABB(puff.x - puff.radius, puff.y - puff.radius, puff.z - puff.radius,
                puff.x + puff.radius, puff.y + puff.radius, puff.z + puff.radius);
            bounds = bounds == null ? box : bounds.minmax(box);
        }

        if (bounds == null) {
            return;
        }

        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, bounds.inflate(1.0), Entity::isAlive);
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

            for (Puff puff : this.puffs) {
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

        VoxelShape shape = state.getCollisionShape(level, this.mutablePos);
        if (shape.isEmpty()) {
            return false;
        }

        double lx = x - this.mutablePos.getX();
        double ly = y - this.mutablePos.getY();
        double lz = z - this.mutablePos.getZ();
        for (AABB box : shape.toAabbs()) {
            if (lx >= box.minX && lx <= box.maxX && ly >= box.minY && ly <= box.maxY && lz >= box.minZ && lz <= box.maxZ) {
                return true;
            }
        }

        return false;
    }

    private void sampleEnvironment(ClientLevel level, Puff puff, boolean hasSkyLight) {
        this.mutablePos.set(Mth.floor(puff.x), Mth.floor(puff.y), Mth.floor(puff.z));

        // DIMENSIONS WITHOUT A SKY (NETHER) USE A FLAT AMBIENT TERM INSTEAD.
        puff.targetSkyLight = hasSkyLight ? level.getBrightness(LightLayer.SKY, this.mutablePos) / 15.0F : 1.0F;
        puff.targetBlockLight = level.getBrightness(LightLayer.BLOCK, this.mutablePos) / 15.0F;
        puff.skyLight += (puff.targetSkyLight - puff.skyLight) * 0.25F;
        puff.blockLight += (puff.targetBlockLight - puff.blockLight) * 0.25F;

        // GAS THAT DRIFTS INTO WATER OR LAVA BUBBLES AWAY QUICKLY.
        if (!level.getFluidState(this.mutablePos).isEmpty()) {
            puff.mass *= 0.86F;
            puff.vy += 0.01;
        }
    }

    public static final class Puff {
        double x, y, z;
        double prevX, prevY, prevZ;
        double vx, vy, vz;
        float radius, prevRadius;
        float mass, prevMass;
        float temperature;
        float skyLight, blockLight;
        float targetSkyLight, targetBlockLight;
        float seed;
        int age;
        int lifetime;

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
            this.targetSkyLight = this.targetSkyLight * wa + other.targetSkyLight * wb;
            this.targetBlockLight = this.targetBlockLight * wa + other.targetBlockLight * wb;

            // LIVE AS LONG AS THE LONGER-LIVED HALF.
            int remaining = Math.max(this.lifetime - this.age, other.lifetime - other.age);
            this.age = Math.min(this.age, other.age);
            this.lifetime = this.age + remaining;
        }

        public float skyLight() {
            return this.skyLight;
        }

        public float blockLight() {
            return this.blockLight;
        }

        public float seed() {
            return this.seed;
        }
    }

    private static final class Emitter {
        final Vec3 origin;
        final Vec3 direction;
        int age;

        Emitter(Vec3 origin, Vec3 direction) {
            this.origin = origin;
            this.direction = direction;
        }
    }
}
