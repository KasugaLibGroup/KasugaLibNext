#version 150

uniform sampler2D NoiseSampler;
uniform sampler2D CellSampler;
uniform samplerBuffer DataSampler;

vec4 dataAt(int x, int y) { return texelFetch(DataSampler, y * 64 + x); }
uniform sampler2D CraftSurfaceSampler;
uniform sampler2D CraftNormalSampler;
uniform sampler2D CraftPbrSampler;
uniform sampler2D CraftDepthSampler;

uniform mat4 InverseProjection;
uniform mat4 InverseView;
uniform vec3 Eye;
uniform float Time;
uniform int SegmentCount;
uniform int LightCount;
uniform int StarCount;
uniform int DustCount;
uniform vec3 CraftPosition;
uniform mat4 CraftInverse;
uniform vec3 WakeBoundsMin;
uniform vec3 WakeBoundsMax;
uniform vec3 WakeSubMin0;
uniform vec3 WakeSubMax0;
uniform vec3 WakeSubMin1;
uniform vec3 WakeSubMax1;
uniform vec3 WakeSubMin2;
uniform vec3 WakeSubMax2;
uniform float InteractionStrength;
uniform float CraftVisible;
uniform float CoreStrength;
uniform float DetailStrength;
uniform float GlobalFlash;
uniform float ShipScale;
uniform int CraftDebug;
uniform float IndirectStrength;
uniform float SmokeStrength;
uniform vec3 CraftVelocity;

uniform vec3 CraftForward;
uniform vec3 CraftRight;
uniform vec3 CraftUp;
uniform float WarpCharge;
uniform float WarpActive;
uniform float WarpStretch;
uniform float WarpFlash;
uniform float WarpDistance;
uniform float WarpTunnelPhase;
uniform vec2 WarpShock;
uniform vec4 NacelleParticleSphere;
uniform int NacelleParticleCount;

const vec3 CORE = vec3(2.0, 3.0, 8.0);
const int MARCH_STEPS = 72;
in vec2 texCoord;
out vec4 fragColor;

// 16 KB L1-pinned hardware-bilinear 3D value noise using paired (z, z+1) RG channels.
float noise(vec3 p) {
    vec3 i = mod(floor(p), 64.0);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    vec2 uv = (i.xy + vec2(37.0, 17.0) * i.z + f.xy + .5) * (1.0 / 64.0);
    vec2 rg = textureLod(NoiseSampler, uv, 0.0).rg;
    return mix(rg.x, rg.y, f.z);
}

float fbm(vec3 p) {
    float value = .019, amplitude = .553;
    for (int i = 0; i < 3; i++) {
        value += noise(p) * amplitude;
        p = p.yzx * 2.04 + vec3(7.1, 3.7, 1.9);
        amplitude *= .48;
    }
    return value;
}

float fbmFar(vec3 p) {
    float v0 = noise(p);
    p = p.yzx * 2.04 + vec3(7.1, 3.7, 1.9);
    return .0827 + v0 * .553 + noise(p) * .26544;
}

#moj_import <kasuga_demo:nebula_warp_medium.glsl>

float baseDensity(vec3 p, float rayDist, float timeTwist, vec3 flow) {
    vec3 q = p * vec3(1.0 / 21.0, 1.0 / 12.0, 1.0 / 15.0);
    float qLen2 = dot(q, q);
    if (qLen2 >= 1.266) return 0.0;
    float qLen = sqrt(qLen2);
    float envelope = qLen < .225 ? 1.0 : 1.0 - smoothstep(.35, 1.0, qLen + (noise(p * .32) - .5) * .25);
    if (envelope <= .001) return 0.0;

    float pY = p.y;
    float corridorBand = (1.0 - smoothstep(.30, 1.05, pY)) * smoothstep(-2.4, -.95, pY);
    float a = .035 * pY + timeTwist;
    float a2 = a * a;
    float sa = a * (1.0 - a2 * .166667);
    float ca = 1.0 - a2 * (.5 - a2 * .041667);
    vec2 twXZ = mat2(ca, -sa, sa, ca) * p.xz;

    vec2 canyon = vec2(twXZ.x, pY) - vec2(2.0 + sin(twXZ.y * .18) * .8, 2.0 + cos(twXZ.y * .12) * .8);
    float radius = length(canyon * vec2(.9, 1.2));

    float warpMorph = clamp(WarpActive + (WarpStretch - 1.0) * 0.55, 0.0, 1.3);
    if (warpMorph > 0.001) {
        float latDist = length(p - CraftPosition - CraftForward * dot(p - CraftPosition, CraftForward));
        radius += smoothstep(2.6, 0.6, latDist) * (0.85 * warpMorph);
    }

    if (radius < 1.48 && corridorBand <= .001) return 0.0;

    float opening = 3.35 + .65 * sin(twXZ.y * .32) + (noise(vec3(twXZ.x, pY, twXZ.y) * .65) - .5) * 1.1;
    float rift = smoothstep(opening - .65, opening + 1.15, radius);
    if (rift <= .001 && corridorBand <= .001) return 0.0;

    if (warpMorph > 0.001) {
        vec3 toP = p - CraftPosition;
        float along = dot(toP, CraftForward);
        float axialScale = mix(1.0, 0.15, clamp(warpMorph, 0.0, 1.0));
        vec3 lat = toP - CraftForward * along;
        float latDist = length(lat);
        vec3 latDir = latDist > 1e-4 ? lat / latDist : CraftRight;
        float push = smoothstep(2.0, 0.2, latDist) * (0.35 * warpMorph);
        vec3 warpShift = CraftForward * (WarpDistance * 0.65);
        p = CraftPosition + (lat + latDir * push) + CraftForward * (along * axialScale) + warpShift;
    }

    float broad = fbm(p * .145 + flow);
    vec3 detailCoord = p * .46 + flow * .46 + broad * 3.5;
    float detail = rayDist < 13.5 ? fbm(detailCoord) : fbmFar(detailCoord);
    float threads = rayDist < 15.5 ? 1.0 - abs(noise(p * .85 + flow * .85 + broad * 3.0) * 2.0 - 1.0) : .5;
    float folds = max(0.0, broad * .6 + detail * .5 + threads * .12 - .60) * 8.6;
    float wall = .10 * (1.0 - smoothstep(5.0, 9.0, radius)) * smoothstep(.32, .66, detail);
    float corridorMist = 0.0;
    if (corridorBand > .001 && rift < .999) {
        float camClear = smoothstep(.55, 1.85, length(p - Eye));
        float strata = smoothstep(.24, .76, broad * .55 + detail * .45 + .14 * sin(p.z * .52 + broad * 4.2));
        float wisps = .42 + .58 * smoothstep(.22, .75, detail * .60 + threads * .40);
        corridorMist = (.95 + 2.35 * strata) * wisps * corridorBand * (1.0 - rift) * camClear;
    }
    return ((folds + wall) * (.003 + .997 * rift) + corridorMist) * envelope;
}

// Fast 2-tap coarse occlusion field for blurred shadow marches.
float coarseDensity(vec3 p, float timeTwist, vec3 flow) {
    vec3 q = p * vec3(1.0 / 21.0, 1.0 / 12.0, 1.0 / 15.0);
    float qLen2 = dot(q, q);
    if (qLen2 >= 1.20) return 0.0;
    float envelope = qLen2 < .12 ? 1.0 : 1.0 - smoothstep(.35, 1.05, sqrt(qLen2));

    float pY = p.y;
    float corridorBand = (1.0 - smoothstep(.30, 1.05, pY)) * smoothstep(-2.4, -.95, pY);
    float a = .035 * pY + timeTwist;
    float a2 = a * a;
    float sa = a * (1.0 - a2 * .166667), ca = 1.0 - a2 * (.5 - a2 * .041667);
    vec2 twXZ = mat2(ca, -sa, sa, ca) * p.xz;

    vec2 canyon = vec2(twXZ.x, pY) - vec2(2.0 + sin(twXZ.y * .18) * .8, 2.0 + cos(twXZ.y * .12) * .8);
    float radius = length(canyon * vec2(.9, 1.2));

    float warpMorph = clamp(WarpActive + (WarpStretch - 1.0) * 0.55, 0.0, 1.3);
    if (warpMorph > 0.001) {
        float latDist = length(p - CraftPosition - CraftForward * dot(p - CraftPosition, CraftForward));
        radius += smoothstep(2.6, 0.6, latDist) * (0.85 * warpMorph);
    }

    if (radius < 2.25 && corridorBand <= .001) return 0.0;
    float rift = smoothstep(2.70, 4.50, radius);
    if (rift <= .001 && corridorBand <= .001) return 0.0;

    if (warpMorph > 0.001) {
        float axialScale = mix(1.0, 0.15, clamp(warpMorph, 0.0, 1.0));
        vec3 toP = p - CraftPosition;
        float along = dot(toP, CraftForward);
        vec3 warpShift = CraftForward * (WarpDistance * 0.65);
        p = CraftPosition + (toP - CraftForward * along) + CraftForward * (along * axialScale) + warpShift;
    }

    float broad = .14 + .72 * noise(p * .145 + flow);
    float detail = .15 + .70 * noise(p * .46 + flow * .46 + broad * 3.5);
    float folds = max(0.0, broad * .6 + detail * .5 + .06 - .60) * 8.6;
    float corridorMist = corridorBand > .001 ? (1.1 + 1.8 * smoothstep(.28, .74, broad * .55 + detail * .45)) * .70 * corridorBand * (1.0 - rift) : 0.0;
    return (folds * (.003 + .997 * rift) + corridorMist) * envelope;
}

// Seamless trilinear lookup into 144 KB 32^3 Worley normal+distance atlas (1-texel wrap border per slice).
float cellDistance(vec3 p, out vec3 normal) {
    vec2 wxy = fract(p.xy * .125) * 32.0 + 1.0;
    float wz = fract(p.z * .125 - (.5 / 32.0)) * 32.0;
    float z0 = floor(wz);
    float zf = wz - z0;
    float z1 = z0 > 30.5 ? 0.0 : z0 + 1.0;
    vec2 t0 = vec2(mod(z0, 8.0), floor(z0 * .125)) * 34.0 + wxy;
    vec2 t1 = vec2(mod(z1, 8.0), floor(z1 * .125)) * 34.0 + wxy;
    vec2 invAtlas = vec2(1.0 / 272.0, 1.0 / 136.0);
    vec4 s = mix(textureLod(CellSampler, t0 * invAtlas, 0.0),
                 textureLod(CellSampler, t1 * invAtlas, 0.0), zf);
    vec3 n = s.xyz * 2.0 - 1.0;
    normal = n * inversesqrt(max(dot(n, n), .0001));
    return s.w * 1.25;
}

void testWakeGroup(vec3 p, int startIdx, int endIdx, inout float closestDist2,
                   inout vec3 bestDiff, inout vec3 bestSeg, inout float bestSegLen2, inout float bestAge) {
    vec4 a = dataAt(startIdx, 0);
    for (int i = startIdx; i < endIdx; i++) {
        vec4 b = dataAt(i + 1, 0);
        vec3 seg = b.xyz - a.xyz;
        float len2 = max(dot(seg, seg), .00001);
        float u = clamp(dot(p - a.xyz, seg) / len2, 0.0, 1.0);
        vec3 diff = p - (a.xyz + seg * u);
        float dist2 = dot(diff, diff);
        if (dist2 < closestDist2) {
            closestDist2 = dist2;
            bestDiff = diff;
            bestSeg = seg;
            bestSegLen2 = len2;
            bestAge = mix(a.w, b.w, u);
        }
        a = b;
    }
}

void sampleWake(vec3 p, out vec3 warp, out vec3 disturbance, out vec3 wakeNormal) {
    warp = vec3(0.0);
    disturbance = vec3(0.0);
    wakeNormal = vec3(0.0, 1.0, 0.0);

    vec3 toHull = p - CraftPosition;
    vec3 local = mat3(CraftInverse) * toHull;
    float nearEyeFade = smoothstep(.55, 1.95, length(p - Eye));
    float hullDist2 = dot(toHull, toHull);
    if (hullDist2 < 5.76) {
        float hullDist = sqrt(hullDist2);
        float hullNear = (1.0 - smoothstep(.45, 2.25, hullDist)) * nearEyeFade;
        vec3 scaled = local / max(ShipScale, .01);
        float noseZ = scaled.z - .44;
        float flankR = length(scaled.xy * vec2(.85, 1.30));
        float bowShell = flankR * flankR + noseZ * .46 - .09;
        float openTop = 1.0 - .55 * smoothstep(.05, .45, scaled.y) * (1.0 - smoothstep(.08, .38, abs(scaled.x)));
        float bowBand = exp(-bowShell * bowShell / .032) * smoothstep(-1.45, .48, scaled.z) * openTop * hullNear;
        vec2 out2 = length(scaled.xy) > .001 ? normalize(scaled.xy) : vec2(1.0, 0.0);
        vec3 worldOut = transpose(mat3(CraftInverse)) * normalize(vec3(out2 * 1.25, .32));
        warp -= worldOut * (.36 * bowBand);
        disturbance.z = bowBand;
        wakeNormal = worldOut;
    }

    bool in0 = all(greaterThanEqual(p, WakeSubMin0)) && all(lessThanEqual(p, WakeSubMax0));
    bool in1 = all(greaterThanEqual(p, WakeSubMin1)) && all(lessThanEqual(p, WakeSubMax1));
    bool in2 = all(greaterThanEqual(p, WakeSubMin2)) && all(lessThanEqual(p, WakeSubMax2));
    if (!in0 && !in1 && !in2) return;

    float closestDist2 = 1e6, bestAge = 0.0, bestSegLen2 = 1.0;
    vec3 bestDiff = vec3(0.0, 1.0, 0.0), bestSeg = vec3(0.0, 0.0, -1.0);
    if (in0) testWakeGroup(p, 0, 8, closestDist2, bestDiff, bestSeg, bestSegLen2, bestAge);
    if (in1) testWakeGroup(p, 8, 16, closestDist2, bestDiff, bestSeg, bestSegLen2, bestAge);
    if (in2) testWakeGroup(p, 16, 23, closestDist2, bestDiff, bestSeg, bestSegLen2, bestAge);

    float wakeR = .15 + .165 * pow(max(bestAge, 0.0) + .05, .72);
    float maxReach = wakeR * 3.2;
    if (closestDist2 > maxReach * maxReach) return;

    float sternGate = (bestAge < 1.2 ? smoothstep(.24, -.08, local.z) : 1.0) * nearEyeFade;
    if (sternGate < .001) return;

    float closestDist = sqrt(max(closestDist2, .000001));
    vec3 flowAxis = -bestSeg * inversesqrt(bestSegLen2);
    vec3 sideAxis = cross(vec3(0.0, 1.0, 0.0), flowAxis);
    float sideLen2 = dot(sideAxis, sideAxis);
    sideAxis = sideLen2 > .0001 ? sideAxis * inversesqrt(sideLen2) : vec3(1.0, 0.0, 0.0);
    vec3 upAxis = cross(flowAxis, sideAxis);

    float sx = dot(bestDiff, sideAxis);
    float sy = dot(bestDiff, upAxis);

    vec3 eddyCoord = p * 1.06 - flowAxis * (Time * .46) + vec3(bestAge * .22, -Time * .16, bestAge * .14);
    float e0 = noise(eddyCoord);
    float e1 = noise(eddyCoord * 2.13 + vec3(4.7, 2.3, 8.1));
    vec3 eddy = vec3(e0 * .62 + e1 * .38, (1.0 - e0) * .54 + e1 * .46, e1 * .68 + e0 * .32) - .5;

    float spreadStage = smoothstep(.10, 9.0, bestAge);
    float nearBoost = 1.0 + .75 * exp(-bestAge * .30);
    float lifeFade = exp(-bestAge * .066) * (1.0 - smoothstep(13.5, 20.5, bestAge)) * sternGate;

    float coreSep = wakeR * .98;
    float vortexR = wakeR * .84;
    vec2 dR = vec2(sx - coreSep, sy + wakeR * .06);
    vec2 dL = vec2(sx + coreSep, sy + wakeR * .06);
    float invVortex2 = 1.0 / max(vortexR * vortexR, .001);
    float wR = exp(-dot(dR, dR) * invVortex2);
    float wL = exp(-dot(dL, dL) * invVortex2);
    vec2 swirl2D = vec2(-dR.y, dR.x) * wR + vec2(dL.y, -dL.x) * wL;
    vec3 swirl3D = (sideAxis * swirl2D.x + upAxis * swirl2D.y) * (1.55 * (.35 + .65 * spreadStage));

    float shedPhase = bestAge * 2.45 - Time * 1.70;
    float invR = 1.0 / max(wakeR, .05);
    float nx = sx * invR + sin(shedPhase + eddy.z * 3.2) * (.22 * spreadStage)
             + eddy.x * (.52 + .88 * spreadStage) + swirl2D.x * invR * .28;
    float ny = sy * invR + cos(shedPhase * .81 + eddy.x * 2.8) * (.18 * spreadStage)
             + eddy.y * (.44 + .75 * spreadStage) + swirl2D.y * invR * .28;
    float normR2 = nx * nx * .5625 + ny * ny;

    vec3 radialDir = bestDiff / max(closestDist, .001);
    vec3 lateralPush = sideAxis * sign(sx + .0001) * exp(-nx * nx * .55) * (wakeR * .95);
    float slipstream = exp(-normR2 * 1.25) * (.52 * wakeR);
    vec3 eddyWorld = (sideAxis * eddy.x + upAxis * eddy.y + flowAxis * eddy.z) * (wakeR * (.68 + .98 * spreadStage));
    warp -= (lateralPush + radialDir * (wakeR * .42 * exp(-normR2 * .8)) + swirl3D + flowAxis * slipstream - eddyWorld * .75) * lifeFade;

    float breakup = smoothstep(.14, .74, .54 + eddy.x * 1.15 + eddy.z * .65 - .28 * smoothstep(9.5, 20.0, bestAge));
    float troughX = exp(-nx * nx * .92);
    float troughY = smoothstep(-1.45, -.50, ny) * (1.0 - smoothstep(1.30, 2.25, ny));
    float cavity = troughX * troughY * min(1.0, lifeFade * 1.35) * (.80 + .20 * breakup);

    float crestHook = 1.44 - .30 * smoothstep(.10, .95, ny);
    vec2 wallDelta = vec2((abs(nx) - crestHook) / (.48 + .24 * spreadStage), (ny - .16) / (.82 + .28 * spreadStage));
    float openCenter = smoothstep(.26, .84 - .30 * spreadStage, abs(nx));
    float flankWall = exp(-dot(wallDelta, wallDelta) * 1.35) * openCenter;
    float curlWave = .52 + eddy.x * 1.12 + eddy.y * .82 + .32 * sin(shedPhase + sign(sx) * 1.45);
    float billow = flankWall * (.62 + .80 * (wR + wL)) * smoothstep(.12, .68, curlWave);
    float ridge = billow * lifeFade * nearBoost * (.36 + .64 * breakup);

    disturbance.x = max(disturbance.x, cavity);
    disturbance.y = max(disturbance.y, ridge);
    vec3 ridgeDir = normalize(sideAxis * sign(sx + .0001) * .65 + upAxis * (.48 + .28 * (wR + wL)) + (sideAxis * eddy.x + upAxis * eddy.y) * .95);
    wakeNormal = normalize(mix(wakeNormal, ridgeDir, clamp(ridge * 1.45, 0.0, 1.0)));
}

float shadowDensity(vec3 p, float timeTwist, vec3 flow) {
    if (InteractionStrength > .5
            && all(greaterThanEqual(p, WakeBoundsMin))
            && all(lessThanEqual(p, WakeBoundsMax))) {
        vec3 warp, disturbance, wakeNormal;
        sampleWake(p, warp, disturbance, wakeNormal);
        float d = coarseDensity(p + warp, timeTwist, flow);
        float ambient = smoothstep(.08, 1.15, d);
        d *= 1.0 - clamp(disturbance.x * .97, 0.0, .98);
        d = d * (1.0 + disturbance.y * 2.0 + disturbance.z * 1.4)
          + disturbance.y * (.32 + 1.85 * ambient) + disturbance.z * (.24 + 1.55 * ambient);
        return max(d, 0.0);
    }
    return max(coarseDensity(p, timeTwist, flow), 0.0);
}

float density(vec3 p, float rayDist, bool checkWake, float transmittance, float timeTwist, vec3 flow,
              out vec3 disturbance, out vec3 normal, out float smokeDensity) {
    vec3 warp = vec3(0.0), wakeNormal = vec3(0.0, 1.0, 0.0);
    disturbance = vec3(0.0);
    if (checkWake) sampleWake(p, warp, disturbance, wakeNormal);
    vec3 q = p + warp;
    float d = baseDensity(q, rayDist, timeTwist, flow);
    if (checkWake) {
        float cavity = disturbance.x, ridge = disturbance.y, bow = disturbance.z;
        vec3 hullLocal = (mat3(CraftInverse) * (p - CraftPosition)) / max(ShipScale, .01);
        vec3 hullNorm = hullLocal / vec3(.36, .28, .66);
        float hullClear = exp(-dot(hullNorm, hullNorm) * 1.5);
        float ambient = smoothstep(.08, 1.20, d);
        d *= 1.0 - clamp(cavity * .97 + hullClear * .95, 0.0, .98);
        d = d * (1.0 + ridge * 2.2 + bow * 1.5)
          + ridge * (.36 + 2.05 * ambient) + bow * (.26 + 1.70 * ambient);
    }
    normal = vec3(0.0, 1.0, 0.0);
    if (d > .035 && transmittance > .08 && DetailStrength > .5) {
        float detailFade = 1.0 - smoothstep(12.0, 32.0, rayDist);
        if (detailFade > .01) {
            float warpMorph = clamp(WarpActive + (WarpStretch - 1.0) * 0.55, 0.0, 1.3);
            if (warpMorph > 0.001) {
                float axialScale = mix(1.0, 0.15, clamp(warpMorph, 0.0, 1.0));
                vec3 toQ = q - CraftPosition;
                float along = dot(toQ, CraftForward);
                vec3 warpShift = CraftForward * (WarpDistance * 0.65);
                q = CraftPosition + (toQ - CraftForward * along) + CraftForward * (along * axialScale) + warpShift;
            }
            float cells = cellDistance(q * 1.45 + vec3(Time * .009, 0.0, Time * .004), normal);
            float fine = noise(q * 4.2 + vec3(0.0, Time * .035, 0.0));
            float erosion = smoothstep(.22, .78, cells) * (.16 + .12 * fine) * (1.0 + .65 * disturbance.y);
            float fineFade = (1.0 - smoothstep(2.8, 8.2, rayDist)) * step(.20, transmittance) * step(.12, d);
            if (fineFade > .01) {
                vec3 fineNormal;
                float fineCells = cellDistance(q * 3.4 + vec3(Time * .012, Time * .006, 0.0), fineNormal);
                d *= 1.0 - fineFade * .52 * smoothstep(.18, .76, fineCells);
                vec3 blend = mix(normal, fineNormal, fineFade * .60);
                normal = blend * inversesqrt(max(dot(blend, blend), .0001));
            }
            d = max(0.0, d - erosion * detailFade) * (1.0 + detailFade * .38 * (1.0 - cells));
        }
    }
    if (checkWake) {
        float macroWeight = clamp(disturbance.y * 1.05 + disturbance.z * 1.1, 0.0, .78);
        vec3 mixed = mix(normal, wakeNormal, macroWeight);
        normal = mixed * inversesqrt(max(dot(mixed, mixed), .0001));
    }
    smokeDensity = warpSmokeDensity(p);
    return max(d, 0.0) + smokeDensity;
}

vec2 rayBox(vec3 ro, vec3 invRd, vec3 bMin, vec3 bMax) {
    vec3 t0 = (bMin - ro) * invRd;
    vec3 t1 = (bMax - ro) * invRd;
    vec3 near3 = min(t0, t1), far3 = max(t0, t1);
    return vec2(max(near3.x, max(near3.y, near3.z)), min(far3.x, min(far3.y, far3.z)));
}

bool sampleCraft(vec2 uv, vec3 ro, vec3 rd, out float hit, out vec3 p,
                 out vec4 surface, out vec4 normalData, out vec4 pbr) {
    hit = 1e6;
    surface = vec4(0.0);
    normalData = vec4(0.0, 1.0, 0.0, 1.0);
    pbr = vec4(0.0, .8, 1.0, 0.0);
    if (CraftVisible < .5) return false;
    float depth = textureLod(CraftDepthSampler, uv, 0.0).r;
    if (depth >= .999999) return false;
    vec4 viewPoint = InverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    if (abs(viewPoint.w) < 1e-6) return false;
    viewPoint.xyz /= viewPoint.w;
    vec3 worldPoint = (InverseView * vec4(viewPoint.xyz, 1.0)).xyz;
    vec3 delta = worldPoint - ro;
    hit = dot(delta, rd);
    if (hit <= .001 || hit > 1e5) return false;
    p = worldPoint;
    surface = textureLod(CraftSurfaceSampler, uv, 0.0);
    normalData = textureLod(CraftNormalSampler, uv, 0.0);
    pbr = textureLod(CraftPbrSampler, uv, 0.0);
    if (dot(normalData.xyz, normalData.xyz) < .25) return false;
    normalData.xyz = normalize(normalData.xyz);
    return true;
}

#moj_import <kasuga_demo:nebula_hull_lighting.glsl>

vec4 volume(vec3 ro, vec3 rd, vec3 invRd, float nearT, float farT) {
    float span = farT - nearT;
    float jitter = .25 + .50 * fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(.06711056, .00583715))));
    vec2 wakeSpan = InteractionStrength > .5 ? rayBox(ro, invRd, WakeBoundsMin, WakeBoundsMax) : vec2(1e6, -1e6);
    bool rayHitsWake = wakeSpan.y >= max(wakeSpan.x, nearT);

    vec4 light0 = LightCount > 0 ? dataAt(24, 0) : vec4(0.0);
    vec4 light1 = LightCount > 1 ? dataAt(25, 0) : vec4(0.0);
    float timeTwist = .13 * sin(Time * .045);
    vec3 flow = vec3(Time * .016, -Time * .023, Time * .009) + CraftForward * WarpDistance;

    float transmittance = 1.0, visibility = 1.0, previousDensity = 0.0;
    int shadowTick = 0, emptyRun = 0;
    vec3 color = vec3(0.0);
    const float invSteps = 1.0 / float(MARCH_STEPS);
    for (int i = 0; i < MARCH_STEPS; i++) {
        float uMid = (float(i) + jitter) * invSteps;
        float t = nearT + span * (uMid * (.12 + .88 * uMid));
        bool checkWake = rayHitsWake && t >= wakeSpan.x && t <= wakeSpan.y;
        vec3 p = ro + rd * t;
        vec3 disturbance, normal;
        float smokeDensity;
        float d = density(p, t, checkWake, transmittance, timeTwist, flow, disturbance, normal, smokeDensity);
        if (d <= .002) {
            previousDensity = 0.0;
            if (!checkWake && emptyRun >= 1 && i + 1 < MARCH_STEPS) {
                float uNext = (float(i + 1) + jitter) * invSteps;
                float tNext = nearT + span * (uNext * (.12 + .88 * uNext));
                if (!rayHitsWake || tNext < wakeSpan.x || tNext > wakeSpan.y) {
                    emptyRun = 2;
                    i++;
                    continue;
                }
            }
            emptyRun = 1;
            continue;
        }
        if (emptyRun == 2) {
            emptyRun = -1;
            i -= 2;
            continue;
        }
        emptyRun = 0;

        float u0 = float(i) * invSteps, u1 = float(i + 1) * invSteps;
        float stepSize = span * (u1 * (.12 + .88 * u1) - u0 * (.12 + .88 * u0));
        vec3 toCore = CORE - p;
        float coreDist2 = max(dot(toCore, toCore), .0001);
        vec3 lightDirection = toCore * inversesqrt(coreDist2);
        if ((shadowTick++ & 3) == 0 || previousDensity <= .002) {
            float occlusion = shadowDensity(p + lightDirection * .62, timeTwist, flow) * 1.15
                            + coarseDensity(p + lightDirection * 3.4, timeTwist, flow) * 4.1;
            visibility = exp(-occlusion * 1.35);
        }
        float micro = DetailStrength > .5 ? .36 + .92 * max(dot(normal, lightDirection), 0.0) : 1.0;
        float rdDotL = max(dot(rd, lightDirection), 0.0);
        float rdDotL2 = rdDotL * rdDotL;
        float phase = .24 + 1.05 * (rdDotL2 * rdDotL2 * sqrt(rdDotL));
        float tint = smoothstep(-12.0, 14.0, p.x + p.z * .45);
        vec3 dust = mix(vec3(.16, .09, .07), vec3(.06, .11, .18), tint);
        vec3 emission = dust * .12 + vec3(.048, .072, .112) * visibility;
        float rimFill = .18 + .82 * max(dot(normal, vec3(-.520, .832, -.208)), 0.0);
        emission += vec3(.042, .068, .102) * rimFill * (.28 + .72 * visibility);
        float wakeScatter = 1.0 + disturbance.y * .75 + disturbance.z * 1.10;
        emission += mix(vec3(.65, .31, .15), vec3(.27, .66, 1.35), tint) * CoreStrength
                * visibility * visibility * phase * micro * wakeScatter * (78.0 / (8.0 + coreDist2));
        emission += vec3(.045, .085, .140) * (disturbance.y * .55 + disturbance.z * .95) * (.20 + .80 * visibility) * CoreStrength;
        // Warp smoke has its own dust-scattering response. It stays local to the
        // advected medium and inherits the same cloud shadow, so it reads as a
        // rolling trail instead of a screen-wide emissive band.
        float smokeLight = 1.0 - exp(-smokeDensity * .85);
        vec3 smokeTint = mix(vec3(.20, .16, .15), vec3(.14, .28, .38), tint);
        emission += smokeTint * smokeLight * (.46 + .66 * micro)
                * (.48 + .52 * visibility) * (.58 + .42 * CoreStrength);
        float wakeDust = clamp(disturbance.y * 2.0 + disturbance.z * 1.1, 0.0, 1.5);
        vec3 wakeDustTint = mix(vec3(.23, .18, .15), vec3(.16, .31, .42), tint);
        emission += wakeDustTint * wakeDust * (.18 + .34 * micro)
                * (.42 + .58 * visibility) * CoreStrength;
        emission += vec3(.58, .78, 1.0) * GlobalFlash * (.28 + .85 * visibility) * (.25 + .75 * micro * micro);
        if (LightCount > 0) {
            vec3 delta0 = p - light0.xyz;
            emission += vec3(.24, .44, .85) * light0.w * 4.0 / (1.0 + dot(delta0, delta0) * .8);
            if (LightCount > 1) {
                vec3 delta1 = p - light1.xyz;
                emission += vec3(.24, .44, .85) * light1.w * 4.0 / (1.0 + dot(delta1, delta1) * .8);
            }
        }
        // Dust has extinction and scattered light; it is not a self-emissive cyan tunnel.
        emission *= 1.0 - WarpActive * .20;
        emission += vec3(.014, .020, .028) * (d / (1.0 + d)) * IndirectStrength;
        float opacity = 1.0 - exp(-d * stepSize * .10);
        color += transmittance * opacity * emission;
        transmittance *= 1.0 - opacity;
        previousDensity = d;
        if (transmittance < .02) break;
    }
    return vec4(color, transmittance);
}

vec2 segmentRay(vec3 ro, vec3 rd, vec3 a, vec3 b) {
    vec3 v = b - a, w = ro - a;
    float vv = max(dot(v, v), .00001), rv = dot(rd, v);
    float rayT = max((rv * dot(v, w) - vv * dot(rd, w)) / max(vv - rv * rv, .00001), 0.0);
    float u = clamp((dot(v, w) + rv * rayT) / vv, 0.0, 1.0);
    vec3 onSegment = a + v * u;
    rayT = max(dot(onSegment - ro, rd), 0.0);
    return vec2(length(ro + rd * rayT - onSegment), rayT);
}

vec3 nearDust(vec3 ro, vec3 rd, float maxT) {
    if (DustCount <= 0) return vec3(0.0);
    vec3 color = vec3(0.0), streak = CraftVelocity * .028;
    for (int i = 0; i < 32; i++) {
        if (i >= DustCount) break;
        vec4 d = dataAt(32 + i, 3);
        vec3 rel = d.xyz - ro;
        float approxT = dot(rel, rd);
        if (approxT < .04 || approxT > maxT) continue;
        vec2 hit = segmentRay(ro, rd, d.xyz, d.xyz - streak);
        if (hit.y < .08 || hit.y > maxT) continue;
        float width = max(.006, hit.y * .0012);
        float d2 = hit.x * hit.x / (width * width);
        if (d2 < 8.0) color += vec3(.10, .14, .19) * (exp(-d2 * 1.5) + .10 * exp(-d2 * .35)) * d.w;
    }
    return color;
}

vec3 lightning(vec3 ro, vec3 rd, float maxT) {
    if (SegmentCount <= 0) return vec3(0.0);
    vec3 color = vec3(0.0);
    for (int i = 0; i < 64; i++) {
        if (i >= SegmentCount) break;
        vec4 s0 = dataAt(i, 1);
        vec4 s1 = dataAt(i, 2);
        vec3 a = s0.xyz, b = s1.xyz;
        float energy = s0.w, width = s1.w;
        vec3 v = b - a, w = ro - a;
        float vv = max(dot(v, v), .00001), rv = dot(rd, v);
        float rayT = max((rv * dot(v, w) - vv * dot(rd, w)) / max(vv - rv * rv, .00001), 0.0);
        float segmentT = clamp((dot(v, w) + rv * rayT) / vv, 0.0, 1.0);
        vec3 onSegment = a + v * segmentT;
        rayT = max(dot(onSegment - ro, rd), 0.0);
        if (rayT > maxT) continue;
        vec3 off = ro + rd * rayT - onSegment;
        float dist2 = dot(off, off);
        float distance = sqrt(dist2);
        float thickness = max(.018 * width, rayT * .0012);
        float core = 1.0 - smoothstep(thickness * .4, thickness * 1.4, distance);
        float halo = .004 * width / (dist2 + .07);
        color += (vec3(.85, .94, 1.0) * core * 3.5 + vec3(.19, .36, 1.0) * halo) * energy;
    }
    return color;
}

vec3 starlight(vec3 ro, vec3 rd, float maxT) {
    if (StarCount <= 0) return vec3(0.0);
    vec3 color = vec3(0.0);
    float warpStreakLen = clamp(WarpStretch - 1.0, 0.0, 2.0) * .12 + WarpActive * .20;
    for (int i = 0; i < 32; i++) {
        if (i >= StarCount) break;
        vec4 star = dataAt(i, 3);
        if (warpStreakLen > 0.02) {
            vec3 p0 = star.xyz;
            vec3 p1 = star.xyz - CraftForward * warpStreakLen;
            vec2 hit = segmentRay(ro, rd, p0, p1);
            if (hit.y > 0.05 && hit.y < maxT) {
                float radius = max(0.014, hit.y * 0.0006);
                float d2 = hit.x * hit.x / (radius * radius);
                if (d2 < 6.0) {
                    float core = exp(-d2 * 1.4);
                    float glow = 0.0015 / (hit.x * hit.x + 0.025);
                    color += mix(vec3(0.4, 0.7, 1.0), vec3(0.9, 0.95, 1.0), core) * (core * .20 + glow * .16) * star.w;
                }
            }
        } else {
            vec3 delta = star.xyz - ro;
            float rayT = dot(delta, rd);
            if (rayT <= 0.0 || rayT > maxT) continue;
            vec3 perp = delta - rd * rayT;
            float d2 = dot(perp, perp);
            if (d2 > .55) continue;
            float radius = max(.028, rayT * .0011);
            float r2 = radius * radius;
            float core = d2 < r2 * 8.0 ? exp(-d2 / r2) : 0.0;
            float glow = .0015 / (d2 + .03);
            color += vec3(.6, .74, 1.0) * (core + glow) * star.w;
        }
    }
    return color;
}

vec3 coreRadiance(vec3 ro, vec3 rd) {
    vec3 delta = CORE - ro;
    float along = dot(delta, rd);
    if (along <= 0.0) return vec3(0.0);
    vec3 perp = delta - rd * along;
    float d2 = dot(perp, perp);
    float halo = .15 * exp(-d2 / 4.0) + .035 / (d2 + .15);
    float disk = d2 < .5 ? 4.0 * exp(-d2 / .045) : 0.0;
    return (vec3(.22, .48, 1.0) * halo + vec3(.78, .90, 1.0) * disk) * CoreStrength;
}

vec3 filmic(vec3 x) {
    x = max(x, vec3(0.0));
    return clamp((x * (2.51 * x + .03)) / (x * (2.43 * x + .59) + .14), 0.0, 1.0);
}

vec3 warpParallaxStars(vec3 ro, vec3 rd, float maxT) {
    float warpEffect = clamp(WarpActive * 1.15 + smoothstep(1.45, 2.70, WarpStretch) * 0.60, 0.0, 1.3);
    if (warpEffect < 0.02) return vec3(0.0);

    float rdDotF = dot(rd, CraftForward);
    float poleFade = smoothstep(0.992, 0.935, abs(rdDotF));
    if (poleFade <= 0.001) return vec3(0.0);

    vec3 dOrig = ro - CraftPosition;
    float dDotF = dot(dOrig, CraftForward);
    vec3 dPerp = dOrig - CraftForward * dDotF;

    vec3 rdPerp = rd - CraftForward * rdDotF;
    float A = dot(rdPerp, rdPerp);
    if (A < 1e-5) return vec3(0.0);

    float B = dot(dPerp, rdPerp);
    float dPerp2 = dot(dPerp, dPerp);

    vec3 color = vec3(0.0);
    const vec3 radii = vec3(2.4, 5.6, 12.0);
    const vec3 speeds = vec3(1.5, 1.1, 0.70);
    const vec3 scales = vec3(1.1, 0.80, 0.55);

    for (int shell = 0; shell < 3; shell++) {
        float R = radii[shell];
        float C = dPerp2 - R * R;
        float disc = B * B - A * C;
        if (disc <= 0.0) continue;
        float t = (-B + sqrt(disc)) / A;
        if (t <= 0.05 || t > maxT) continue;

        vec3 hitP = ro + rd * t;
        vec3 toHit = hitP - CraftPosition;
        float zAlong = dot(toHit, CraftForward);

        float uX = dot(toHit, CraftRight);
        float uY = dot(toHit, CraftUp);
        float phi = atan(uY, uX);
        float phiNorm = (phi + 3.14159265) * 0.15915494;
        float nPhi = 14.0 + float(shell) * 6.0;

        float shellSpeed = speeds[shell];
        float zTravel = zAlong + WarpDistance * (shellSpeed * 1.8);
        float cellH = 3.2;

        vec2 grid = vec2(phiNorm * nPhi, zTravel / cellH);
        vec2 cellId = floor(grid);
        vec2 cellFrac = fract(grid);

        float h = fract(sin(dot(cellId + float(shell) * 17.3, vec2(12.9898, 78.233))) * 43758.5453);
        if (h > 0.12) continue;

        float starFracX = fract(h * 37.1);
        float cellW = (6.2831853 * R) / nPhi;
        float angDist = abs(cellFrac.x - starFracX) * cellW;
        float streakW = max(0.004, t * 0.0006);
        float lineCore = exp(-(angDist * angDist) / (streakW * streakW * 2.5));

        float zRel = cellFrac.y;
        float trailSpan = mix(0.04, 0.16, clamp(warpEffect, 0.0, 1.0));
        float zFade = smoothstep(0.0, 0.08, zRel) * (1.0 - smoothstep(0.08, trailSpan, zRel));

        float atten = (1.0 / (1.0 + t * 0.04)) * scales[shell];
        vec3 starCol = mix(vec3(0.20, 0.35, .55), vec3(0.55, 0.66, .78), clamp(h * 2.8, 0.0, 1.0));
        color += starCol * (lineCore * zFade * atten * warpEffect * poleFade * .22);
    }
    return color;
}

vec3 nacelleParticles(vec3 ro, vec3 rd, float maxT) {
    if (NacelleParticleCount <= 0) return vec3(0.0);
    // During the actual warp cruise the analytic plume below replaces these
    // discrete sprite-like sparks; keeping both layers makes the exhaust read as
    // detached blue rectangles.
    if (WarpActive > .15 || WarpStretch > 1.25) return vec3(0.0);
    vec3 sc = NacelleParticleSphere.xyz;
    vec3 dsc = ro - sc;
    float b = dot(dsc, rd);
    float c = dot(dsc, dsc) - NacelleParticleSphere.w;
    if (c > 0.0 && b > 0.0) return vec3(0.0);
    float disc = b * b - c;
    if (disc < 0.0) return vec3(0.0);
    float tNear = -b - sqrt(disc);
    if (tNear > maxT) return vec3(0.0);

    vec3 color = vec3(0.0);
    for (int i = 0; i < 64; i++) {
        if (i >= NacelleParticleCount) break;
        vec4 h = dataAt(i, 4);
        vec4 t = dataAt(i, 5);
        float brightness = h.w;
        if (brightness <= 0.002) continue;
        vec3 p0 = h.xyz;
        vec3 p1 = t.xyz;
        float radius = t.w;

        vec3 mid = (p0 + p1) * 0.5;
        float approxT = dot(mid - ro, rd);
        if (approxT < 0.0 || approxT > maxT) continue;
        vec3 probe = ro + rd * approxT;
        vec3 dMid = probe - mid;
        float maxR = radius * 3.2 + 0.12;
        if (dot(dMid, dMid) > maxR * maxR) continue;

        vec2 hit = segmentRay(ro, rd, p0, p1);
        if (hit.y > 0.02 && hit.y < maxT) {
            float w = max(radius, hit.y * 0.001);
            float d2 = hit.x * hit.x / (w * w);
            if (d2 < 6.5) {
                float core = exp(-d2 * 3.0) * 1.25;
                float halo = exp(-d2 * 1.2) * 0.07;
                // Keep a blue core; the old unbounded mix factor extrapolated overlapping sparks to white.
                vec3 pCol = mix(vec3(0.025, 0.12, 0.36), vec3(0.12, 0.38, 0.72), clamp(core * 0.6, 0.0, 1.0));
                color += pCol * ((core + halo) * brightness);
            }
        }
    }
    return color * .34 / (1.0 + max(color.r, max(color.g, color.b)) * 0.55);
}

// Localized two-nozzle plume plus a converging rear wake. The first part anchors
// the effect to the actual nacelles; the second part makes the long back trail a
// continuous, broken smoke body instead of two detached screen-space streaks.
vec3 warpEnginePlume(vec3 ro, vec3 rd, float maxT) {
    float drive = clamp(WarpActive + smoothstep(1.12, 2.70, WarpStretch) * .88
            + WarpCharge * .14, 0.0, 1.30);
    if (drive < .035) return vec3(0.0);

    float drive01 = smoothstep(.08, 1.0, drive);
    float reach = mix(.72, 1.72, drive01);
    vec3 color = vec3(0.0);
    for (int engine = 0; engine < 2; engine++) {
        float side = engine == 0 ? -1.0 : 1.0;
        vec3 nozzle = CraftPosition
                + CraftRight * (side * .255 * ShipScale)
                + CraftUp * (.115 * ShipScale)
                - CraftForward * (.56 * ShipScale);
        for (int puff = 0; puff < 12; puff++) {
            float u = (float(puff) + .5) / 12.0;
            float phase = Time * (.72 + .10 * drive) + u * 7.4 + side * 1.7;
            float radius = mix(.050, .165, u) * (.72 + .28 * drive);
            float travel = .05 + reach * u;
            vec3 center = nozzle - CraftForward * travel;
            // Let the two nozzle strands merge downstream. The near field keeps
            // the engine separation readable; the far field becomes one wake.
            float merge = smoothstep(.16, .74, u);
            center += CraftRight * (-side * .18 * ShipScale * merge);
            float curl = sin(phase * 1.17 + u * 8.0) * (.010 + .16 * u)
                    + sin(phase * .43 - u * 11.0) * (.006 + .08 * u);
            float lift = cos(phase * .91 - u * 6.0) * (.008 + .12 * u)
                    + cos(phase * .37 + u * 9.0) * (.005 + .06 * u);
            center += CraftRight * curl;
            center += CraftUp * lift;

            vec2 hit = segmentRay(ro, rd,
                    center + CraftForward * (radius * .72),
                    center - CraftForward * (radius * .72));
            if (hit.y < .02 || hit.y > maxT) continue;
            float d2 = hit.x * hit.x / max(radius * radius, 1e-5);
            float noiseField = noise(center * (2.6 + u * 1.4) + vec3(Time * .035, -Time * .022, 1.7));
            float breakup = .58 + .62 * smoothstep(.18, .84, noiseField);
            float life = smoothstep(.0, .05, u) * (1.0 - smoothstep(.88, 1.0, u));
            float blob = exp(-d2 * (1.18 + .45 * u)) * life * breakup;
            float body = exp(-d2 * .30) * life * (.11 + .15 * noiseField);
            float coolTrail = smoothstep(.18, .62, u);
            float nearFade = 1.0 - smoothstep(.40, .78, u);
            vec3 plumeColor = mix(vec3(.58, .23, .08), vec3(.12, .25, .31), coolTrail);
            color += plumeColor * (blob * nearFade * (.22 + .30 * drive) + body * (.08 + .08 * drive));
        }
    }

    // A low-saturation central smoke spine makes the rearward path survive in
    // dense nebula backgrounds. It is intentionally dimmer than the nozzle core.
    float wakeReach = mix(1.30, 5.35, drive01);
    for (int puff = 0; puff < 18; puff++) {
        float u = (float(puff) + .5) / 18.0;
        float travel = .32 + wakeReach * u;
        float phase = Time * .33 + u * 6.1 + WarpTunnelPhase * .009;
        float backRadius = mix(.08, .31, u) * (.78 + .22 * drive01);
        vec3 center = CraftPosition - CraftForward * travel;
        center += CraftRight * sin(phase * .83 + u * 4.0) * (.025 + .16 * u);
        center += CraftUp * cos(phase * .69 - u * 5.4) * (.018 + .11 * u);
        float turbulence = noise(center * (1.35 + u * .75) + vec3(Time * .025, -Time * .018, 4.1));
        float breakMask = smoothstep(.38, .80, turbulence);
        float gapMask = smoothstep(.28, .76,
                noise(center * (2.25 + u * .55) + vec3(-Time * .021, Time * .017, 8.3)));
        vec2 hit = segmentRay(ro, rd,
                center + CraftForward * (backRadius * .64),
                center - CraftForward * (backRadius * .64));
        if (hit.y < .02 || hit.y > maxT) continue;
        float d2 = hit.x * hit.x / max(backRadius * backRadius, 1e-5);
        float life = smoothstep(.0, .04, u) * (1.0 - smoothstep(.90, 1.0, u));
        float puffWeight = exp(-d2 * (.92 + .42 * u)) * life
                * (.05 + 1.10 * breakMask) * (.25 + .75 * gapMask);
        float rim = exp(-d2 * .28) * life * (.035 + .08 * turbulence) * gapMask;
        vec3 smokeColor = mix(vec3(.055, .09, .12), vec3(.12, .20, .24), smoothstep(.24, .80, turbulence));
        color += smokeColor * (puffWeight * (.075 + .07 * drive01) + rim * .035);
    }

    float peak = max(color.r, max(color.g, color.b));
    return color * (.70 / (1.0 + peak * .46));
}

vec3 warpFlashEffect(vec3 ro, vec3 rd, bool hitCraft) {
    if (WarpFlash < 0.01) return vec3(0.0);
    vec3 bowP = CraftPosition + CraftForward * (0.62 * max(WarpStretch, 1.0) * ShipScale);
    vec3 bowDir = normalize(bowP - ro);
    float cosAngle = dot(rd, bowDir);
    if (cosAngle < 0.0) return vec3(0.0);
    float angDist2 = 1.0 - cosAngle * cosAngle;
    float core = exp(-angDist2 * 650.0);
    vec3 ortho = rd - bowDir * cosAngle;
    float rx = dot(ortho, CraftRight);
    float ry = dot(ortho, CraftUp);
    float streak = exp(-ry * ry * 5000.0) * exp(-rx * rx * 220.0);
    vec3 col = mix(vec3(0.25, 0.65, 1.2), vec3(0.9, 0.96, 1.0), WarpFlash);
    float occ = hitCraft ? 0.15 : 1.0;
    return col * ((core * .38 * occ + streak * .045) * (WarpFlash * 0.25));
}

void main() {
    vec4 view = InverseProjection * vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);
    vec3 rd = normalize((InverseView * vec4(normalize(view.xyz / max(abs(view.w), .00001)), 0.0)).xyz);
    vec3 ro = Eye;

    // 1. Sample the standalone OBJ color/normal/depth buffers rendered with this exact optical view.
    vec3 craftPoint = vec3(0.0);
    vec4 craftMaterial = vec4(0.0);
    vec4 craftNormal = vec4(0.0, 1.0, 0.0, 0.0);
    vec4 craftPbr = vec4(0.0, .8, 1.0, 0.0);
    float hit = 1e6;
    bool hitCraft = sampleCraft(texCoord, ro, rd, hit, craftPoint, craftMaterial, craftNormal, craftPbr);
    vec3 craftCol = hitCraft ? craftSurface(craftPoint, craftMaterial, craftNormal, craftPbr) : vec3(0.0);
    // Opt-in GPU smoke probes: exact SDF silhouette and its isolated, display-mapped material.
    if (CraftDebug == 1) {
        fragColor = vec4(hitCraft ? vec3(1.0) : vec3(0.0), 1.0);
        return;
    }
    if (CraftDebug == 2) {
        fragColor = vec4(pow(filmic(craftCol * .72), vec3(1.0 / 2.2)), 1.0);
        return;
    }

    // 2. Pre-warp undisturbed ray effects: particles and lightning on UNBENT rd!
    vec3 nacelleCol = nacelleParticles(ro, rd, hit);
    vec3 boltCol = lightning(ro, rd, hit);

    // 3. In-place warp space ray modification (Alcubierre metric lens + aberration)
    float metricActive = clamp(WarpActive + smoothstep(1.15, 2.65, WarpStretch) * 0.65 + pow(WarpCharge, 3.5) * 0.08, 0.0, 1.35);
    // A ship hit was measured on the optical ray; its foreground fog must follow that same ray.
    if (metricActive > 0.005 && !hitCraft) {
        vec3 dCraft = ro - CraftPosition;
        float tClosest = max(-dot(dCraft, rd), 0.0);
        vec3 pClosest = ro + rd * tClosest - CraftPosition;
        vec3 bubbleScale = vec3(0.95, 0.65, 1.30 * max(WarpStretch, 1.0)) * ShipScale;
        vec3 pMetric = vec3(dot(pClosest, CraftRight) / bubbleScale.x,
                            dot(pClosest, CraftUp) / bubbleScale.y,
                            dot(pClosest, CraftForward) / bubbleScale.z);
        float bubbleMetric = length(pMetric);
        float outerMask = smoothstep(0.65, 1.55, bubbleMetric);

        // Gravitational lens deflection at the bubble boundary (soft, non-caustic)
        if (bubbleMetric > 0.50 && bubbleMetric < 3.2) {
            float lensWave = exp(-pow((bubbleMetric - 1.15) / 0.78, 2.0));
            vec3 lensCenterDir = normalize(CraftPosition - (ro + rd * tClosest));
            rd = normalize(rd + lensCenterDir * (lensWave * metricActive * 0.065));
        }

        // Relativistic aberration outside the bubble
        float cosTheta = clamp(dot(rd, CraftForward), -0.9999, 0.9999);
        float beta = clamp(0.11 * WarpActive + 0.08 * smoothstep(1.35, 2.75, WarpStretch), 0.0, 0.14);
        if (beta > 0.001) {
            float cosPrime = clamp((cosTheta - beta) / max(1.0 - beta * cosTheta, 0.05), -0.9999, 0.9999);
            float sinPrime = sqrt(max(1.0 - cosPrime * cosPrime, 0.0));
            vec3 ortho = rd - CraftForward * cosTheta;
            float oLen = length(ortho);
            vec3 orthoDir = oLen > 1e-5 ? ortho / oLen : CraftRight;
            vec3 aberratedRd = normalize(CraftForward * cosPrime + orthoDir * sinPrime);
            rd = normalize(mix(rd, aberratedRd, outerMask));
        }
    }

    vec3 signs = mix(vec3(-1.0), vec3(1.0), greaterThanEqual(rd, vec3(0.0)));
    vec3 invRd = signs / max(abs(rd), vec3(1e-6));

    vec3 background = vec3(.0015, .002, .004) + coreRadiance(ro, rd);

    float b = dot(ro, rd), c = dot(ro, ro) - 484.0;
    float discriminant = b * b - c;
    vec3 color = background;
    vec4 cloud = vec4(0.0, 0.0, 0.0, 1.0);
    if (discriminant > 0.0) {
        float root = sqrt(discriminant);
        float sphereNear = max(0.0, -b - root), sphereFar = min(-b + root, hit);
        vec3 invEllip = vec3(1.0 / 23.65, 1.0 / 13.55, 1.0 / 16.90);
        vec3 roE = ro * invEllip, rdE = rd * invEllip;
        float aE = max(dot(rdE, rdE), 1e-6), bE = dot(roE, rdE), cE = dot(roE, roE) - 1.0;
        float discE = bE * bE - aE * cE;
        if (discE > 0.0) {
            float rootE = sqrt(discE);
            float nearT = max(sphereNear, (-bE - rootE) / aE);
            float farT = min(sphereFar, (-bE + rootE) / aE);
            if (farT > nearT) cloud = volume(ro, rd, invRd, nearT, farT);
        }
        // The volume march stops at the hull, so cloud.rgb is foreground dust.
        // During warp that foreground layer is dense enough to wash out the ship;
        // keep a readable veil over the hull while preserving the full medium elsewhere.
        float hullDustScale = hitCraft && (WarpActive > .05 || WarpStretch > 1.05) ? .34 : 1.0;
        color = cloud.rgb * hullDustScale + cloud.a * (hitCraft ? craftCol : background);
        color += boltCol + starlight(ro, rd, hit) * max(cloud.a, .08);
        color += warpParallaxStars(ro, rd, hit) * (hitCraft ? 0.0 : max(cloud.a, 0.14));
        color += nearDust(ro, rd, hit);
    } else if (hitCraft) {
        color = craftCol;
    } else {
        color += boltCol;
    }

    // Nacelle wake particles, localized engine plume and warp flash
    color += nacelleCol;
    color += warpEnginePlume(ro, rd, hit);
    color += warpFlashEffect(ro, rd, hitCraft);

    fragColor = vec4(pow(filmic(color * .72), vec3(1.0 / 2.2)), 1.0);
}
