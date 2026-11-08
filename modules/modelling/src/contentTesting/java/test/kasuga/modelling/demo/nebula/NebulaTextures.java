package test.kasuga.modelling.demo.nebula;

import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.tbo.TBOBuffer;
import lib.kasuga.rendering.models.uml.backend.gpu.texture.GpuTexture2D;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Seamless noise/Worley atlases and a compact per-frame RGBA32F simulation TBO. */
final class NebulaTextures {
    static final float STARSHIP_HALF_X = 0.42f;
    static final float STARSHIP_HALF_Y = 0.23f;
    static final float STARSHIP_HALF_Z = 0.66f;
    static final float NACELLE_AXIS_X = 0.255f;
    static final float NACELLE_AXIS_Y = 0.115f;
    static final float NACELLE_NOZZLE_Z = -0.56f;
    static final int NOISE_SIZE = 64;
    static final int CELL_PERIOD = 8;
    static final int CELL_VOXELS = 32;
    static final int CELL_TILE = CELL_VOXELS + 2;
    static final int CELL_COLS = 8;
    static final int CELL_ROWS = 4;
    static final int CELL_ATLAS_WIDTH = CELL_COLS * CELL_TILE;
    static final int CELL_ATLAS_HEIGHT = CELL_ROWS * CELL_TILE;
    static final float CELL_MAX_DIST = 1.25f;
    static final int DATA_WIDTH = 64;
    static final int DATA_HEIGHT = 6;
    private static final int TOTAL_STARS = 48;
    private static final int MAX_VISIBLE_DUST = 32;
    private static final float[] STARS = buildStars();

    private ByteBuffer dataStorage;
    private FloatBuffer dataBuffer;
    private GpuTexture2D noiseTexture;
    private GpuTexture2D cellTexture;
    private TBOBuffer dataBufferObject;
    private int visibleStarCount;
    private int visibleDustCount;
    private final org.joml.Vector4f nacelleParticleSphere = new org.joml.Vector4f();
    private int visibleNacelleParticleCount = 64;
    private boolean lastDeleteVerified = true;

    int noiseTexture() { return noiseTexture.id(); }
    int cellTexture() { return cellTexture.id(); }
    TBOBuffer dataBuffer() { return dataBufferObject; }
    int visibleStarCount() { return visibleStarCount; }
    int visibleDustCount() { return visibleDustCount; }
    org.joml.Vector4f nacelleParticleSphere() { return nacelleParticleSphere; }
    int visibleNacelleParticleCount() { return visibleNacelleParticleCount; }
    boolean allocated() { return noiseTexture != null || cellTexture != null || dataBufferObject != null; }
    boolean lastDeleteVerified() { return lastDeleteVerified; }

    void ensureReady() {
        RenderSystem.assertOnRenderThread();
        if (noiseTexture != null && cellTexture != null && dataBufferObject != null
                && noiseTexture.isOpen() && cellTexture.isOpen() && !dataBufferObject.isClosed()) {
            return;
        }
        deleteNow();
        ByteBuffer noise = BufferUtils.createByteBuffer(NOISE_SIZE * NOISE_SIZE * 4);
        noise.put(buildNoiseLutRgba()).flip();
        ByteBuffer cell = BufferUtils.createByteBuffer(CELL_ATLAS_WIDTH * CELL_ATLAS_HEIGHT * 4);
        cell.put(buildWorleyAtlasRgba()).flip();
        noiseTexture = GpuTexture2D.rgba8(NOISE_SIZE, NOISE_SIZE, org.lwjgl.opengl.GL11.GL_REPEAT, noise);
        cellTexture = GpuTexture2D.rgba8(CELL_ATLAS_WIDTH, CELL_ATLAS_HEIGHT, GL12.GL_CLAMP_TO_EDGE, cell);
        int byteCount = DATA_WIDTH * DATA_HEIGHT * 4 * Float.BYTES;
        dataStorage = BufferUtils.createByteBuffer(byteCount).order(ByteOrder.nativeOrder());
        dataBuffer = dataStorage.asFloatBuffer();
        for (int i = 0; i < DATA_WIDTH * DATA_HEIGHT * 4; i++) dataBuffer.put(0f);
        dataBuffer.flip();
        dataStorage.position(0).limit(byteCount);
        dataBufferObject = new TBOBuffer(() -> 7, dataStorage, GL15.GL_DYNAMIC_DRAW);
    }

    void uploadFrameData(float time, Vector3f eye, Vector3f camForward, float[] wake,
                         float[] lightData, int lightCount, float[] boltData, int segmentCount,
                         NebulaFlight.Pose craft, NebulaFlight.WarpState warp) {
        ensureReady();
        dataBuffer.clear();
        // Row 0: 24 stern wake samples [0..23], 2 bolt point lights [24..25].
        dataBuffer.put(wake, 0, NebulaFlight.WAKE_POINTS * 4);
        for (int i = 0; i < 2; i++) {
            int k = i * 4;
            if (i < lightCount) {
                dataBuffer.put(lightData[k]).put(lightData[k + 1]).put(lightData[k + 2]).put(lightData[k + 3]);
            } else {
                dataBuffer.put(0f).put(0f).put(0f).put(0f);
            }
        }
        for (int pad = 26; pad < DATA_WIDTH; pad++) dataBuffer.put(0f).put(0f).put(0f).put(0f);

        // Row 1: lightning segment start.xyz + energy.
        int clampedSegments = Math.min(segmentCount, DATA_WIDTH);
        for (int i = 0; i < clampedSegments; i++) {
            int k = i * 8;
            dataBuffer.put(boltData[k]).put(boltData[k + 1]).put(boltData[k + 2]).put(boltData[k + 3]);
        }
        for (int i = clampedSegments; i < DATA_WIDTH; i++) dataBuffer.put(0f).put(0f).put(0f).put(0f);

        // Row 2: lightning segment end.xyz + width.
        for (int i = 0; i < clampedSegments; i++) {
            int k = i * 8;
            dataBuffer.put(boltData[k + 4]).put(boltData[k + 5]).put(boltData[k + 6]).put(boltData[k + 7]);
        }
        for (int i = clampedSegments; i < DATA_WIDTH; i++) dataBuffer.put(0f).put(0f).put(0f).put(0f);

        // Row 3 [0..31]: frustum-culled world stars; [32..63]: frustum-culled near-camera dust motes.
        int starCount = 0;
        for (int i = 0; i < TOTAL_STARS && starCount < 32; i++) {
            int k = i * 4;
            float dx = STARS[k] - eye.x, dy = STARS[k + 1] - eye.y, dz = STARS[k + 2] - eye.z;
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float along = dx * camForward.x + dy * camForward.y + dz * camForward.z;
            if (along <= 0f || along < .32f * dist - .9f) continue;
            float flicker = .25f + .08f * (float) Math.sin(time * .6f + STARS[k + 3]);
            dataBuffer.put(STARS[k]).put(STARS[k + 1]).put(STARS[k + 2]).put(flicker);
            starCount++;
        }
        visibleStarCount = starCount;
        for (int i = starCount; i < 32; i++) dataBuffer.put(0f).put(0f).put(0f).put(0f);

        int dustCount = 0;
        // World-anchored finer dust cells put more parallax between the close camera and the hull.
        float dustCell = 1.25f;
        float cx = (float) Math.floor(eye.x / dustCell);
        float cy = (float) Math.floor(eye.y / dustCell);
        float cz = (float) Math.floor(eye.z / dustCell);
        for (int z = -2; z <= 2; z++) {
            for (int y = -2; y <= 2; y++) {
                for (int x = -2; x <= 2; x++) {
                    float ix = cx + x, iy = cy + y, iz = cz + z;
                    float hx = ix + 9.3f, hy = iy + 9.3f, hz = iz + 9.3f;
                    float qx = fract(hx * .1031f), qy = fract(hy * .1030f), qz = fract(hz * .0973f);
                    float dot = qx * (qy + 33.33f) + qy * (qx + 33.33f) + qz * (qz + 33.33f);
                    qx += dot; qy += dot; qz += dot;
                    float ox = fract((qx + qy) * qz);
                    float oy = fract((qx + qz) * qy);
                    float oz = fract((qy + qz) * qx);
                    float px = (ix + ox) * dustCell, py = (iy + oy) * dustCell, pz = (iz + oz) * dustCell;
                    float rx = px - eye.x, ry = py - eye.y, rz = pz - eye.z;
                    float r2 = rx * rx + ry * ry + rz * rz;
                    if (r2 > 6.25f) continue;
                    float dist = (float) Math.sqrt(r2);
                    float along = rx * camForward.x + ry * camForward.y + rz * camForward.z;
                    if (along < .22f * dist - .65f) continue;
                    float fade = (1f - smoothstep(.9f, 2.5f, dist)) * .7f;
                    if (fade <= .001f || dustCount >= MAX_VISIBLE_DUST) continue;
                    dataBuffer.put(px).put(py).put(pz).put(fade);
                    dustCount++;
                }
            }
        }
        visibleDustCount = dustCount;
        for (int i = 32 + dustCount; i < DATA_WIDTH; i++) dataBuffer.put(0f).put(0f).put(0f).put(0f);

        // Row 4 [0..63]: Nacelle particle head.xyz + brightness
        // Row 5 [0..63]: Nacelle particle tail.xyz + radius
        float stretch = warp != null ? warp.stretch() : 1.0f;
        float charge = warp != null ? warp.charge() : 0.0f;
        float warpActive = warp != null ? warp.warpActive() : 0.0f;
        boolean inWarp = charge > 0.01f || warpActive > 0.01f || stretch > 1.01f;
        boolean highSpeedWarp = warpActive > 0.10f || stretch > 1.20f;
        int activeCount = inWarp ? 64 : 32;
        int perEngine = activeCount / 2;

        float maxReach = highSpeedWarp ? (2.2f + warpActive * 2.7f) : (0.75f + charge * 0.15f);
        float flowPhase = warp != null ? warp.warpDistance() * 0.35f + time * 1.6f : time * 1.6f;

        float sumX = 0f, sumY = 0f, sumZ = 0f;
        float[] heads = new float[64 * 4];
        float[] tails = new float[64 * 4];

        for (int i = 0; i < 64; i++) {
            if (i >= activeCount) {
                int k = i * 4;
                heads[k] = 0f; heads[k + 1] = 0f; heads[k + 2] = 0f; heads[k + 3] = 0f;
                tails[k] = 0f; tails[k + 1] = 0f; tails[k + 2] = 0f; tails[k + 3] = 0f;
                continue;
            }

            float engineSign = (i < perEngine) ? -1.0f : 1.0f;
            int localIdx = i % perEngine;
            float seedJitter = fract((float) (Math.sin(i * 12.9898 + 78.233) * 43758.5453));
            float speed = 0.85f + 0.30f * fract((float) (Math.sin((i + 13) * 37.1) * 23421.6));
            float u = fract((localIdx + seedJitter) / (float) perEngine + flowPhase * speed);

            float trailDist = u * maxReach;
            float sampleT = time - (trailDist / 2.6f);
            var histPose = NebulaFlight.sample(sampleT);

            float nozX = engineSign * NACELLE_AXIS_X * NebulaFlight.SHIP_SCALE;
            float nozY = NACELLE_AXIS_Y * NebulaFlight.SHIP_SCALE;
            float nozZ = NACELLE_NOZZLE_Z * NebulaFlight.SHIP_SCALE * stretch;

            var baseP = new Vector3f(histPose.position())
                    .fma(nozX, histPose.right())
                    .fma(nozY, histPose.up())
                    .fma(nozZ, histPose.forward());

            float swirl = u * (14.0f + 6.0f * engineSign) + i * 2.39996f + time * 4.5f;
            float spread = highSpeedWarp ? (0.010f + 0.075f * u) : (0.008f + 0.036f * u);
            var centerP = new Vector3f(baseP)
                    .fma((float) Math.cos(swirl) * spread, histPose.right())
                    .fma((float) Math.sin(swirl) * spread, histPose.up());

            float segLen = highSpeedWarp ? (0.025f + 0.045f * u) : (0.010f + 0.012f * u);
            var dir = new Vector3f(histPose.forward()).negate();

            var head = new Vector3f(centerP).fma(-0.5f * segLen, dir);
            var tail = new Vector3f(centerP).fma(0.5f * segLen, dir);

            float radius = highSpeedWarp ? (0.008f + 0.003f * u) : (0.006f + 0.002f * u);
            float envelope = smoothstep(0.0f, 0.06f, u) * (float) Math.pow(1.0f - u, 1.25);
            float sparkEnergy = fract((float) (Math.sin(i * 45.123 + 19.45) * 31415.926));
            float baseB = highSpeedWarp
                    ? (0.18f + 0.25f * warpActive + Math.max(0.0f, stretch - 1.0f) * 0.14f)
                    : (0.14f + charge * 0.18f);
            float brightness = baseB * (0.45f + 0.95f * sparkEnergy) * envelope;

            int k = i * 4;
            heads[k] = head.x; heads[k + 1] = head.y; heads[k + 2] = head.z; heads[k + 3] = brightness;
            tails[k] = tail.x; tails[k + 1] = tail.y; tails[k + 2] = tail.z; tails[k + 3] = radius;

            sumX += head.x + tail.x;
            sumY += head.y + tail.y;
            sumZ += head.z + tail.z;
        }

        float totalPoints = activeCount * 2.0f;
        float centerX = sumX / totalPoints;
        float centerY = sumY / totalPoints;
        float centerZ = sumZ / totalPoints;
        float maxR2 = 0.0f;
        for (int i = 0; i < activeCount; i++) {
            int k = i * 4;
            float hdx = heads[k] - centerX, hdy = heads[k + 1] - centerY, hdz = heads[k + 2] - centerZ;
            float tdx = tails[k] - centerX, tdy = tails[k + 1] - centerY, tdz = tails[k + 2] - centerZ;
            float r = tails[k + 3];
            float hDist = (float) Math.sqrt(hdx * hdx + hdy * hdy + hdz * hdz) + r;
            float tDist = (float) Math.sqrt(tdx * tdx + tdy * tdy + tdz * tdz) + r;
            maxR2 = Math.max(maxR2, Math.max(hDist * hDist, tDist * tDist));
        }
        float sphereR = (float) Math.sqrt(maxR2) + 0.06f;
        nacelleParticleSphere.set(centerX, centerY, centerZ, sphereR * sphereR);
        visibleNacelleParticleCount = activeCount;

        // Write row 4
        for (int i = 0; i < 64; i++) {
            int k = i * 4;
            dataBuffer.put(heads[k]).put(heads[k + 1]).put(heads[k + 2]).put(heads[k + 3]);
        }

        // Write row 5
        for (int i = 0; i < 64; i++) {
            int k = i * 4;
            dataBuffer.put(tails[k]).put(tails[k + 1]).put(tails[k + 2]).put(tails[k + 3]);
        }

        // The FloatBuffer is positioned at the end after the six rows are written.
        // Upload the complete fixed-width TBO, rather than using its remaining
        // elements (which would be zero and leave the sampler with stale data).
        dataStorage.position(0).limit(dataStorage.capacity());
        dataBufferObject.updateAll(dataStorage);
    }

    void release() {
        if (RenderSystem.isOnRenderThread()) deleteNow();
        else RenderSystem.recordRenderCall(this::deleteNow);
    }

    private void deleteNow() {
        RenderSystem.assertOnRenderThread();
        boolean verified = true;
        if (noiseTexture != null) {
            int retired = noiseTexture.id();
            noiseTexture.close(); verified &= !org.lwjgl.opengl.GL11.glIsTexture(retired); noiseTexture = null;
        }
        if (cellTexture != null) {
            int retired = cellTexture.id();
            cellTexture.close(); verified &= !org.lwjgl.opengl.GL11.glIsTexture(retired); cellTexture = null;
        }
        if (dataBufferObject != null) {
            int retiredBuffer = dataBufferObject.getBufferId(), retiredTexture = dataBufferObject.getTextureId();
            try { dataBufferObject.close(); } catch (Exception failure) { verified = false; }
            verified &= dataBufferObject.isClosed() && !GL15.glIsBuffer(retiredBuffer)
                    && !org.lwjgl.opengl.GL11.glIsTexture(retiredTexture);
            dataBufferObject = null;
        }
        dataBuffer = null;
        dataStorage = null;
        lastDeleteVerified = verified;
    }

    static byte[] buildNoiseLutRgba() {
        int mask = NOISE_SIZE - 1;
        int[] base = new int[NOISE_SIZE * NOISE_SIZE];
        for (int y = 0; y < NOISE_SIZE; y++) {
            for (int x = 0; x < NOISE_SIZE; x++) {
                base[y * NOISE_SIZE + x] = mix32(x * 0x9E3779B9 ^ y * 0x85EBCA6B ^ 0x4E454255) & 255;
            }
        }
        byte[] rgba = new byte[NOISE_SIZE * NOISE_SIZE * 4];
        for (int y = 0; y < NOISE_SIZE; y++) {
            for (int x = 0; x < NOISE_SIZE; x++) {
                int idx = (y * NOISE_SIZE + x) * 4;
                rgba[idx] = (byte) base[y * NOISE_SIZE + x];
                rgba[idx + 1] = (byte) base[((y + 17) & mask) * NOISE_SIZE + ((x + 37) & mask)];
                rgba[idx + 2] = (byte) base[((y + 29) & mask) * NOISE_SIZE + ((x + 43) & mask)];
                rgba[idx + 3] = (byte) 255;
            }
        }
        return rgba;
    }

    static byte[] buildWorleyAtlasRgba() {
        float[] jitter = new float[CELL_PERIOD * CELL_PERIOD * CELL_PERIOD * 3];
        for (int z = 0; z < CELL_PERIOD; z++) {
            for (int y = 0; y < CELL_PERIOD; y++) {
                for (int x = 0; x < CELL_PERIOD; x++) {
                    int h = mix32(x * 73856093 ^ y * 19349663 ^ z * 83492791 ^ 0x27D4EB2F);
                    int k = ((z * CELL_PERIOD + y) * CELL_PERIOD + x) * 3;
                    jitter[k] = .06f + .88f * ((mix32(h ^ 0x11) >>> 8) / (float) (1 << 24));
                    jitter[k + 1] = .06f + .88f * ((mix32(h ^ 0x22) >>> 8) / (float) (1 << 24));
                    jitter[k + 2] = .06f + .88f * ((mix32(h ^ 0x33) >>> 8) / (float) (1 << 24));
                }
            }
        }

        byte[] voxels = new byte[CELL_VOXELS * CELL_VOXELS * CELL_VOXELS * 4];
        float scale = (float) CELL_PERIOD / CELL_VOXELS;
        for (int vz = 0; vz < CELL_VOXELS; vz++) {
            float pz = (vz + .5f) * scale;
            int cz = (int) Math.floor(pz);
            float fz = pz - cz;
            for (int vy = 0; vy < CELL_VOXELS; vy++) {
                float py = (vy + .5f) * scale;
                int cy = (int) Math.floor(py);
                float fy = py - cy;
                for (int vx = 0; vx < CELL_VOXELS; vx++) {
                    float px = (vx + .5f) * scale;
                    int cx = (int) Math.floor(px);
                    float fx = px - cx;
                    float nearest = 10f, bx = 0f, by = -1f, bz = 0f;
                    for (int dz = -1; dz <= 1; dz++) {
                        int wz = (cz + dz + CELL_PERIOD) % CELL_PERIOD;
                        for (int dy = -1; dy <= 1; dy++) {
                            int wy = (cy + dy + CELL_PERIOD) % CELL_PERIOD;
                            for (int dx = -1; dx <= 1; dx++) {
                                int wx = (cx + dx + CELL_PERIOD) % CELL_PERIOD;
                                int jk = ((wz * CELL_PERIOD + wy) * CELL_PERIOD + wx) * 3;
                                float ddx = dx + jitter[jk] - fx;
                                float ddy = dy + jitter[jk + 1] - fy;
                                float ddz = dz + jitter[jk + 2] - fz;
                                float d2 = ddx * ddx + ddy * ddy + ddz * ddz;
                                if (d2 < nearest) {
                                    nearest = d2;
                                    bx = ddx; by = ddy; bz = ddz;
                                }
                            }
                        }
                    }
                    float dist = (float) Math.sqrt(Math.max(nearest, 1e-6f));
                    float nx = 0f, ny = 1f, nz = 0f;
                    if (dist > 1e-3f) {
                        nx = -bx / dist; ny = -by / dist; nz = -bz / dist;
                    }
                    int vk = ((vz * CELL_VOXELS + vy) * CELL_VOXELS + vx) * 4;
                    voxels[vk] = toByte(nx * .5f + .5f);
                    voxels[vk + 1] = toByte(ny * .5f + .5f);
                    voxels[vk + 2] = toByte(nz * .5f + .5f);
                    voxels[vk + 3] = toByte(dist / CELL_MAX_DIST);
                }
            }
        }

        byte[] atlas = new byte[CELL_ATLAS_WIDTH * CELL_ATLAS_HEIGHT * 4];
        for (int slice = 0; slice < CELL_VOXELS; slice++) {
            int ox = (slice % CELL_COLS) * CELL_TILE;
            int oy = (slice / CELL_COLS) * CELL_TILE;
            for (int ty = 0; ty < CELL_TILE; ty++) {
                int vy = (ty - 1 + CELL_VOXELS) % CELL_VOXELS;
                for (int tx = 0; tx < CELL_TILE; tx++) {
                    int vx = (tx - 1 + CELL_VOXELS) % CELL_VOXELS;
                    int src = ((slice * CELL_VOXELS + vy) * CELL_VOXELS + vx) * 4;
                    int dst = ((oy + ty) * CELL_ATLAS_WIDTH + (ox + tx)) * 4;
                    atlas[dst] = voxels[src];
                    atlas[dst + 1] = voxels[src + 1];
                    atlas[dst + 2] = voxels[src + 2];
                    atlas[dst + 3] = voxels[src + 3];
                }
            }
        }
        return atlas;
    }

    private static float[] buildStars() {
        float[] out = new float[TOTAL_STARS * 4];
        for (int i = 0; i < TOTAL_STARS; i++) {
            float seed = i * 7.91f;
            out[i * 4] = (glslHash(seed, 3f, 5f) * 2f - 1f) * 16f;
            out[i * 4 + 1] = (glslHash(2f, seed, 9f) * 2f - 1f) * 8f;
            out[i * 4 + 2] = (glslHash(8f, 1f, seed) * 2f - 1f) * 11f;
            out[i * 4 + 3] = seed;
        }
        return out;
    }

    private static float glslHash(float x, float y, float z) {
        float px = fract(x * .1031f), py = fract(y * .1031f), pz = fract(z * .1031f);
        float d = px * (py + 33.33f) + py * (pz + 33.33f) + pz * (px + 33.33f);
        px += d; py += d; pz += d;
        return fract((px + py) * pz);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float fract(float v) { return v - (float) Math.floor(v); }

    private static byte toByte(float v) {
        return (byte) Math.round(Math.clamp(v, 0f, 1f) * 255f);
    }

    private static int mix32(int x) {
        x ^= x >>> 16;
        x *= 0x7FEB352D;
        x ^= x >>> 15;
        x *= 0x846CA68B;
        x ^= x >>> 16;
        return x;
    }
}
