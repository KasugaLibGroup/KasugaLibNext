#version 150

uniform mat4 InverseProjection;
uniform mat4 InverseView;
uniform vec3 Eye;
uniform float Time;
uniform int SegmentCount;
uniform float BoltData[512];
uniform float LightData[16];
uniform vec3 CraftPosition;
uniform mat4 CraftInverse;
uniform float WakeData[96];
uniform float InteractionStrength;
uniform float CraftVisible;
uniform float CoreStrength;
uniform float DetailStrength;
uniform float GlobalFlash;
uniform float ShipScale;
uniform vec3 CraftVelocity;
const vec3 CORE = vec3(2.0,3.0,8.0);
in vec2 texCoord;
out vec4 fragColor;

float hash(vec3 p) {
    p = fract(p * .1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float noise(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i), hash(i + vec3(1,0,0)), f.x),
                   mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x),
                   mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float value = 0.0, amplitude = .54;
    for (int i = 0; i < 4; i++) {
        value += noise(p) * amplitude;
        p = p.yzx * 2.04 + vec3(7.1, 3.7, 1.9);
        amplitude *= .48;
    }
    return value;
}

float baseDensity(vec3 p) {
    vec3 q = p / vec3(21.0, 12.0, 15.0);
    float envelope = 1.0 - smoothstep(.35, 1.0, length(q) + (noise(p * .32) - .5) * .25);
    float camClear = smoothstep(.55, 1.85, length(p - Eye));
    float pY = p.y;
    float angle = .035 * p.y + .13 * sin(Time * .045);
    mat2 turn = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
    p.xz = turn * p.xz;
    vec3 flow = vec3(Time * .016, -Time * .023, Time * .009);
    float broad = fbm(p * .145 + flow);
    float detail = fbm(p * .46 - flow * .6 + broad * 3.5);
    float threads = 1.0 - abs(noise(p * .85 + broad * 3.0 - flow) * 2.0 - 1.0);
    vec2 canyon = p.xy - vec2(2.0 + sin(p.z*.18)*.8, 2.0 + cos(p.z*.12)*.8);
    float radius = length(canyon * vec2(.9,1.2));
    float opening = 3.35 + .65 * sin(p.z*.32) + (noise(p*.65)-.5)*1.1;
    float rift = smoothstep(opening-.65,opening+1.15,radius);
    float folds = max(0.0,broad*.6+detail*.5+threads*.12-.60)*8.6;
    // Broken cloud walls frame a world-anchored opening; dark foreground pillars reveal the distant core.
    float wall = .10 * (1.0-smoothstep(5.0,9.0,radius)) * smoothstep(.32,.66,detail);
    // Permeable cloud decks span the lower flight corridor while leaving the upper sightline toward CORE open.
    float corridorBand = (1.0 - smoothstep(.30, 1.05, pY)) * smoothstep(-2.4, -.95, pY);
    float strata = smoothstep(.24, .76, broad * .55 + detail * .45 + .14 * sin(p.z * .52 + broad * 4.2));
    float wisps = .42 + .58 * smoothstep(.22, .75, detail * .60 + threads * .40);
    float corridorMist = (.95 + 2.35 * strata) * wisps * corridorBand * (1.0 - rift) * camClear;
    return ((folds+wall)*(.003+.997*rift) + corridorMist) * envelope;
}

vec3 hash33(vec3 p) {
    p = fract(p*vec3(.1031,.1030,.0973));
    p += dot(p,p.yxz+33.33);
    return fract((p.xxy+p.yxx)*p.zyx);
}

float cellDistance(vec3 p, out vec3 normal) {
    vec3 cell = floor(p), f = fract(p), closest = vec3(0);
    float nearest = 10.0;
    for (int z=-1;z<=1;z++) for (int y=-1;y<=1;y++) for (int x=-1;x<=1;x++) {
        vec3 offset = vec3(x,y,z);
        vec3 delta = offset+hash33(cell+offset)-f;
        float distance = dot(delta,delta);
        if (distance<nearest) { nearest=distance;closest=delta; }
    }
    float distance = sqrt(max(nearest,.000001));
    normal = -closest/distance;
    return distance;
}

float eddyNoise(vec3 p) {
    return noise(p) * .62 + noise(p * 2.13 + vec3(4.7, 2.3, 8.1)) * .38;
}

void sampleWake(vec3 p, out vec3 warp, out vec3 disturbance, out vec3 wakeNormal) {
    warp = vec3(0.0);
    disturbance = vec3(0.0);
    wakeNormal = vec3(0.0, 1.0, 0.0);
    if (InteractionStrength < .5) return;

    vec3 local = mat3(CraftInverse) * (p - CraftPosition);
    float nearEyeFade = smoothstep(.55, 1.95, length(p - Eye));
    float hullDist = length(p - CraftPosition);
    if (hullDist < 2.4) {
        float hullNear = (1.0 - smoothstep(.45, 2.25, hullDist)) * nearEyeFade;
        vec3 scaled = local / max(ShipScale, .01);
        // Tight swept-back V-collar parting clouds from the nose shoulders into the side wakes.
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

    if (abs(p.x) > 6.4 || abs(p.y) > 4.5 || abs(p.z) > 14.2) return;
    float closestDist2 = 1e6, bestAge = 0.0, bestSegLen2 = 1.0;
    vec3 bestDiff = vec3(0.0, 1.0, 0.0), bestSeg = vec3(0.0, 0.0, -1.0);
    for (int i = 0; i < 23; i++) {
        int k = i * 4;
        vec3 a = vec3(WakeData[k], WakeData[k+1], WakeData[k+2]);
        vec3 b = vec3(WakeData[k+4], WakeData[k+5], WakeData[k+6]);
        vec3 seg = b - a;
        float len2 = max(dot(seg, seg), .00001);
        float u = clamp(dot(p - a, seg) / len2, 0.0, 1.0);
        vec3 diff = p - (a + seg * u);
        float dist2 = dot(diff, diff);
        if (dist2 < closestDist2) {
            closestDist2 = dist2;
            bestDiff = diff;
            bestSeg = seg;
            bestSegLen2 = len2;
            bestAge = mix(WakeData[k+3], WakeData[k+7], u);
        }
    }

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

    // Multi-scale Kelvin-Helmholtz eddies roll outward and grow coarser downstream.
    vec3 eddyCoord = p * 1.06 - flowAxis * (Time * .46) + vec3(bestAge * .22, -Time * .16, bestAge * .14);
    vec3 eddy = vec3(
        eddyNoise(eddyCoord) - .5,
        eddyNoise(eddyCoord * 1.52 + vec3(5.2, 1.7, 8.4)) - .5,
        noise(eddyCoord * 2.70 + vec3(2.9, 6.1, 3.7)) - .5
    );
    float spreadStage = smoothstep(.10, 9.0, bestAge);
    float nearBoost = 1.0 + .75 * exp(-bestAge * .30);
    float lifeFade = exp(-bestAge * .066) * (1.0 - smoothstep(13.5, 20.5, bestAge)) * sternGate;

    // Counter-rotating wingtip vortex pair: sweeps center cloud outward and curls flanks upward & inward.
    float coreSep = wakeR * .98;
    float vortexR = wakeR * .84;
    vec2 dR = vec2(sx - coreSep, sy + wakeR * .06);
    vec2 dL = vec2(sx + coreSep, sy + wakeR * .06);
    float wR = exp(-dot(dR, dR) / max(vortexR * vortexR, .001));
    float wL = exp(-dot(dL, dL) / max(vortexR * vortexR, .001));
    vec2 swirl2D = vec2(-dR.y, dR.x) * wR + vec2(dL.y, -dL.x) * wL;
    vec3 swirl3D = (sideAxis * swirl2D.x + upAxis * swirl2D.y) * (1.55 * (.35 + .65 * spreadStage));

    float shedPhase = bestAge * 2.45 - Time * 1.70;
    float invR = 1.0 / max(wakeR, .05);
    float nx = sx * invR + sin(shedPhase + eddy.z * 3.2) * (.22 * spreadStage)
             + eddy.x * (.52 + .88 * spreadStage) + swirl2D.x * invR * .28;
    float ny = sy * invR + cos(shedPhase * .81 + eddy.x * 2.8) * (.18 * spreadStage)
             + eddy.y * (.44 + .75 * spreadStage) + swirl2D.y * invR * .28;
    float normR = length(vec2(nx * .75, ny));

    vec3 radialDir = bestDiff / max(closestDist, .001);
    vec3 lateralPush = sideAxis * sign(sx + .0001) * exp(-nx * nx * .55) * (wakeR * .95);
    float slipstream = exp(-normR * normR * 1.25) * (.52 * wakeR);
    vec3 eddyWorld = (sideAxis * eddy.x + upAxis * eddy.y + flowAxis * eddy.z) * (wakeR * (.68 + .98 * spreadStage));
    warp -= (lateralPush + radialDir * (wakeR * .42 * exp(-normR * normR * .8)) + swirl3D + flowAxis * slipstream - eddyWorld * .75) * lifeFade;

    float breakup = smoothstep(.14, .74, .54 + eddy.x * 1.15 + eddy.z * .65 - .28 * smoothstep(9.5, 20.0, bestAge));
    // Open-top parted furrow cuts cleanly through the cloud deck without a roof hiding the channel.
    float troughX = exp(-nx * nx * .92);
    float troughY = smoothstep(-1.45, -.50, ny) * (1.0 - smoothstep(1.30, 2.25, ny));
    float cavity = troughX * troughY * min(1.0, lifeFade * 1.35) * (.80 + .20 * breakup);

    // Left and right C-curling vortex ridges hook inward at their upper crests and fray into billowing puffs.
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

float shadowDensity(vec3 p) {
    vec3 warp, disturbance, wakeNormal;
    sampleWake(p, warp, disturbance, wakeNormal);
    float d = baseDensity(p + warp);
    if (InteractionStrength > .5) {
        float ambient = smoothstep(.08, 1.15, d);
        d *= 1.0 - clamp(disturbance.x * .97, 0.0, .98);
        d = d * (1.0 + disturbance.y * 2.0 + disturbance.z * 1.4)
          + disturbance.y * (.32 + 1.85 * ambient) + disturbance.z * (.24 + 1.55 * ambient);
    }
    return max(d, 0.0);
}

float density(vec3 p, out vec3 disturbance, out vec3 normal) {
    vec3 warp, wakeNormal;
    sampleWake(p, warp, disturbance, wakeNormal);
    vec3 q = p + warp;
    float d = baseDensity(q);
    if (InteractionStrength > .5) {
        float cavity = disturbance.x, ridge = disturbance.y, bow = disturbance.z;
        vec3 hullLocal = (mat3(CraftInverse) * (p - CraftPosition)) / max(ShipScale, .01);
        vec3 hullNorm = hullLocal / vec3(.36, .28, .66);
        float hullClear = exp(-dot(hullNorm, hullNorm) * 1.5);
        float ambient = smoothstep(.08, 1.20, d);
        d *= 1.0 - clamp(cavity * .97 + hullClear * .95, 0.0, .98);
        d = d * (1.0 + ridge * 2.2 + bow * 1.5)
          + ridge * (.36 + 2.05 * ambient) + bow * (.26 + 1.70 * ambient);
    }
    normal = vec3(0,1,0);
    if (d>.012 && DetailStrength>.5) {
        float cells = cellDistance(q*1.45+vec3(Time*.009,0,Time*.004),normal);
        float fine = noise(q*4.2+vec3(0,Time*.035,0));
        float erosion = smoothstep(.22,.78,cells)*(.16+.12*fine)*(1.0 + .65 * disturbance.y);
        float detailFade=1.0-smoothstep(12.0,34.0,length(p-Eye));
        float fineFade=1.0-smoothstep(3.0,12.0,length(p-Eye));
        if (fineFade>.01) {
            vec3 fineNormal;
            float fineCells=cellDistance(q*3.4+vec3(Time*.012,Time*.006,0),fineNormal);
            d *= 1.0-fineFade*.52*smoothstep(.18,.76,fineCells);
            vec3 blend=mix(normal,fineNormal,fineFade*.60);
            normal=blend/max(length(blend),.00001);
        }
        d=max(0.0,d-erosion*detailFade)*(1.0+detailFade*.38*(1.0-cells));
    }
    if (InteractionStrength > .5) {
        float macroWeight = clamp(disturbance.y * 1.05 + disturbance.z * 1.1, 0.0, .78);
        normal = normalize(mix(normal, wakeNormal, macroWeight));
    }
    return max(d,0.0);
}

float craftHit(vec3 ro, vec3 rd) {
    if (CraftVisible < .5) return 1e6;
    vec3 origin = mat3(CraftInverse) * (ro - CraftPosition);
    vec3 direction = mat3(CraftInverse) * rd;
    vec3 signs = mix(vec3(-1), vec3(1), greaterThanEqual(direction, vec3(0)));
    vec3 safeDirection = signs * max(abs(direction), vec3(.000001));
    vec3 low = (-vec3(.16,.09,.45)*ShipScale - origin) / safeDirection;
    vec3 high = (vec3(.16,.09,.45)*ShipScale - origin) / safeDirection;
    vec3 entries = min(low,high), exits = max(low,high);
    float nearT = max(entries.x,max(entries.y,entries.z));
    float farT = min(exits.x,min(exits.y,exits.z));
    return farT > max(nearT,0.0) ? max(nearT,0.0) : 1e6;
}

vec3 craftSurface(vec3 p) {
    vec3 local = mat3(CraftInverse) * (p - CraftPosition)/max(ShipScale,.01);
    vec3 halfSize = vec3(.16,.09,.45);
    vec3 face = abs(abs(local) - halfSize);
    vec3 n;
    float edge;
    if (face.x < face.y && face.x < face.z) {
        n = vec3(sign(local.x),0,0);
        edge = min(halfSize.y-abs(local.y),halfSize.z-abs(local.z));
    } else if (face.y < face.z) {
        n = vec3(0,sign(local.y),0);
        edge = min(halfSize.x-abs(local.x),halfSize.z-abs(local.z));
    } else {
        n = vec3(0,0,sign(local.z));
        edge = min(halfSize.x-abs(local.x),halfSize.y-abs(local.y));
    }
    vec3 normal = transpose(mat3(CraftInverse)) * n;
    vec3 paint = n.y > .5 ? vec3(.70,.73,.77) : vec3(.30,.35,.43);
    paint = mix(paint,vec3(.68,.78,.86), (1.0-smoothstep(.008,.021,edge))*.8);
    vec3 incoming = normalize(CORE-p);
    vec3 color = paint * (.45 + 1.1 * max(dot(normal,incoming),0.0));
    color += vec3(.12,.18,.24)*(1.0-smoothstep(.006,.018,edge));
    color += paint*vec3(.10,.25,.45)*pow(1.0-max(dot(normal,normalize(Eye-p)),0.0),3.0);
    // A single inset exhaust rectangle leaves the silhouette a simple cuboid.
    if (n.z < -.5 && abs(local.x) < .115 && abs(local.y) < .048) color += vec3(.08,.44,.85) * 1.8;
    for (int light = 0; light < 4; light++) {
        int k = light * 4;
        vec3 delta = p - vec3(LightData[k],LightData[k+1],LightData[k+2]);
        color += paint * vec3(.35,.55,1.0) * LightData[k+3] * 4.0 / (1.0+dot(delta,delta));
    }
    color += paint*vec3(.6,.78,1.0)*GlobalFlash*.85;
    return color;
}

vec4 volume(vec3 ro,vec3 rd,float nearT,float farT) {
    float span=farT-nearT;
    float jitter=fract(52.9829189*fract(dot(gl_FragCoord.xy,vec2(.06711056,.00583715))));
    float transmittance=1.0,visibility=1.0,previousDensity=0.0;
    vec3 color=vec3(0);
    for (int i=0;i<160;i++) {
        float u0=float(i)/160.0,u1=float(i+1)/160.0;
        float stepSize=span*(u1*u1-u0*u0);
        float uMid=(float(i)+jitter)/160.0;
        float t=nearT+span*(uMid*uMid);
        vec3 p=ro+rd*t;
        vec3 disturbance,normal;
        float d=density(p,disturbance,normal);
        if (d>.002) {
            vec3 toCore=CORE-p;
            float coreDistance=max(length(toCore),.01);
            vec3 lightDirection=toCore/coreDistance;
            // Coarse shadow visibility changes slowly; near tap includes wake ridges/trough so curls cast 3D self-shadow.
            if (i%4==0 || previousDensity<=.002) {
                float occlusion=shadowDensity(p+lightDirection*.55)*.95
                               +baseDensity(p+lightDirection*2.4)*2.4
                               +baseDensity(p+lightDirection*6.0)*2.2;
                visibility=exp(-occlusion*1.35);
            }
            float micro=DetailStrength>.5 ? .36+.92*max(dot(normal,lightDirection),0.0) : 1.0;
            float phase=.24+1.05*pow(max(dot(rd,lightDirection),0.0),4.5);
            float tint=smoothstep(-12.0,14.0,p.x+p.z*.45);
            vec3 dust=mix(vec3(.16,.09,.07),vec3(.06,.11,.18),tint);
            vec3 emission=dust*.12+vec3(.048,.072,.112)*visibility;
            float rimFill=.18+.82*max(dot(normal,normalize(vec3(-.5,.8,-.2))),0.0);
            emission += vec3(.042,.068,.102)*rimFill*(.28+.72*visibility);
            float wakeScatter = 1.0 + disturbance.y * .75 + disturbance.z * 1.10;
            emission += mix(vec3(.65,.31,.15),vec3(.27,.66,1.35),tint)*CoreStrength
                    *visibility*visibility*phase*micro*wakeScatter*(78.0/(8.0+coreDistance*coreDistance));
            emission += vec3(.045,.085,.140)*(disturbance.y*.55+disturbance.z*.95)*(.20+.80*visibility)*CoreStrength;
            // Sky-filling lightning illumination reaches distant and shadowed gas, rather than tinting the screen.
            emission += vec3(.58,.78,1.0)*GlobalFlash*(.28+.85*visibility)*(.25+.75*micro*micro);
            for (int light=0;light<4;light++) {
                int k=light*4;
                vec3 delta=p-vec3(LightData[k],LightData[k+1],LightData[k+2]);
                emission += vec3(.24,.44,.85)*LightData[k+3]*4.0/(1.0+dot(delta,delta)*.8);
            }
            float opacity=1.0-exp(-d*stepSize*.10);
            color += transmittance*opacity*emission;
            transmittance *= 1.0-opacity;
        }
        previousDensity=d;
        if (transmittance<.02) break;
    }
    return vec4(color,transmittance);
}

vec2 segmentRay(vec3 ro,vec3 rd,vec3 a,vec3 b) {
    vec3 v=b-a,w=ro-a;
    float vv=max(dot(v,v),.00001),rv=dot(rd,v);
    float rayT=max((rv*dot(v,w)-vv*dot(rd,w))/max(vv-rv*rv,.00001),0.0);
    float u=clamp((dot(v,w)+rv*rayT)/vv,0.0,1.0);
    vec3 onSegment=a+v*u;
    rayT=max(dot(onSegment-ro,rd),0.0);
    return vec2(length(ro+rd*rayT-onSegment),rayT);
}

vec3 nearDust(vec3 ro,vec3 rd,float maxT) {
    vec3 cell=floor(ro/1.8),color=vec3(0);
    for (int z=-1;z<=1;z++) for (int y=-1;y<=1;y++) for (int x=-1;x<=1;x++) {
        vec3 index=cell+vec3(x,y,z);
        vec3 p=(index+hash33(index+9.3))*1.8;
        vec2 hit=segmentRay(ro,rd,p,p-CraftVelocity*.18);
        if (hit.y<.08 || hit.y>maxT) continue;
        float width=max(.003,hit.y*.0008);
        float fade=1.0-smoothstep(1.3,4.8,length(p-ro));
        color += vec3(.18,.32,.5)*exp(-hit.x*hit.x/(width*width))*fade*.8;
    }
    return color;
}

vec3 lightning(vec3 ro, vec3 rd, float maxT) {
    vec3 color = vec3(0);
    for (int i = 0; i < 64; i++) {
        if (i >= SegmentCount) break;
        int k = i * 8;
        vec3 a = vec3(BoltData[k], BoltData[k+1], BoltData[k+2]);
        vec3 b = vec3(BoltData[k+4], BoltData[k+5], BoltData[k+6]);
        float energy = BoltData[k+3], width = BoltData[k+7];
        vec3 v = b - a, w = ro - a;
        float vv = max(dot(v,v), .00001), rv = dot(rd,v);
        float rayT = max((rv * dot(v,w) - vv * dot(rd,w)) / max(vv - rv * rv, .00001), 0.0);
        float segmentT = clamp((dot(v,w) + rv * rayT) / vv, 0.0, 1.0);
        vec3 onSegment = a + v * segmentT;
        rayT = max(dot(onSegment - ro, rd), 0.0);
        if (rayT > maxT) continue;
        float distance = length(ro + rd * rayT - onSegment);
        float thickness = max(.018 * width, rayT * .0012);
        float core = 1.0 - smoothstep(thickness * .4, thickness * 1.4, distance);
        float halo = .004 * width / (distance * distance + .07);
        color += (vec3(.85, .94, 1.0) * core * 3.5 + vec3(.19, .36, 1.0) * halo) * energy;
    }
    return color;
}

vec3 starlight(vec3 ro, vec3 rd, float maxT) {
    vec3 color = vec3(0);
    for (int i = 0; i < 48; i++) {
        float seed = float(i) * 7.91;
        vec3 star = (vec3(hash(vec3(seed, 3, 5)), hash(vec3(2, seed, 9)),
                         hash(vec3(8, 1, seed))) * 2.0 - 1.0) * vec3(16, 8, 11);
        vec3 delta = star - ro;
        float rayT = max(dot(delta, rd), 0.0);
        if (rayT > maxT) continue;
        float distance = length(delta - rd * rayT);
        float radius = max(.028, rayT * .0011);
        float core = exp(-distance * distance / (radius * radius));
        float glow = .0015 / (distance * distance + .03);
        color += vec3(.6,.74,1.0)*(core+glow)*(.25+.08*sin(Time*.6+seed));
    }
    return color;
}

vec3 coreRadiance(vec3 ro, vec3 rd) {
    vec3 delta = CORE-ro;
    float along = dot(delta,rd);
    if (along <= 0.0) return vec3(0);
    float d = length(delta-rd*along);
    float halo = .15*exp(-d*d/4.0)+.035/(d*d+.15);
    float disk = 4.0*exp(-d*d/.045);
    return (vec3(.22,.48,1.0)*halo + vec3(.78,.90,1.0)*disk)*CoreStrength;
}

vec3 filmic(vec3 x) {
    x = max(x,vec3(0));
    return clamp((x*(2.51*x+.03))/(x*(2.43*x+.59)+.14),0.0,1.0);
}

void main() {
    vec4 view = InverseProjection * vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);
    vec3 rd = normalize((InverseView * vec4(normalize(view.xyz / max(abs(view.w), .00001)), 0)).xyz);
    vec3 ro = Eye;
    vec3 background = vec3(.0015,.002,.004)+coreRadiance(ro,rd);
    float hit = craftHit(ro,rd);
    bool hitCraft = hit < 1e5;
    float b = dot(ro, rd), c = dot(ro, ro) - 22.0 * 22.0;
    float discriminant = b * b - c;
    vec3 color = background;
    if (discriminant > 0.0) {
        float root = sqrt(discriminant);
        float nearT = max(0.0, -b - root), farT = min(-b + root,hit);
        if (farT > nearT) {
            vec4 cloud = volume(ro,rd,nearT,farT);
            color = cloud.rgb + cloud.a * (hitCraft ? craftSurface(ro+rd*hit) : background);
            color += lightning(ro,rd,hit)+starlight(ro,rd,hit)*max(cloud.a,.08);
            color += nearDust(ro,rd,hit);
        }
    }
    fragColor = vec4(pow(filmic(color*.72),vec3(1.0/2.2)),1.0);
}
