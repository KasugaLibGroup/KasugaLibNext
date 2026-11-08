// Advected, non-emissive participating matter in the flight frame; depth-tested by the volume ray.
float warpSmokeDensity(vec3 p) {
    float activity = clamp(WarpActive + smoothstep(1.15, 2.7, WarpStretch) * .40 + WarpShock.y * .25, 0.0, 1.3);
    if (SmokeStrength <= .001 || activity < .005) return 0.0;
    vec3 delta = p - CraftPosition;
    float along = dot(delta, CraftForward);
    vec2 across = vec2(dot(delta, CraftRight), dot(delta, CraftUp));
    float radius2 = dot(across, across);
    if (radius2 > 36.0 || abs(along) > 22.0) return 0.0;
    float radial = sqrt(radius2);
    // Keep the visible plume behind the stern. A small forward shoulder is left for
    // pressure wisps, while the main smoke volume follows the flight direction.
    float distanceBack = max(-along, 0.0);
    float trailing = 1.0 - smoothstep(-.85, 5.2, along);
    float tailFade = 1.0 - smoothstep(13.0, 30.0, distanceBack);
    float envelope = exp(-radius2 * .082) * trailing * (.72 + .28 * tailFade);
    float hullClearance = smoothstep(.50, 1.25, radial);
    vec3 q = vec3(across * .82, along * .26 + WarpDistance * .38);
    q.xy += vec2(sin(q.z * .70 + WarpTunnelPhase * .02), cos(q.z * .65 - WarpTunnelPhase * .025)) * .35;
    float broad = noise(q * .68 + vec3(0.0, Time * .018, 0.0));
    float curls = noise(q * 1.65 + broad * 2.4 + vec3(Time * .012, 0.0, 1.7));
    float filaments = smoothstep(.40, .76, broad * .64 + curls * .36);
    float clumps = filaments * filaments * (.44 + curls * .92);
    // A narrow, advected spine keeps the wake legible at distance. It converges
    // the two nozzle streams into one broken smoke body instead of two parallel
    // light bars, while the surrounding clumps preserve the volumetric breakup.
    vec2 wakeCenter = vec2(
            sin(distanceBack * .48 + WarpTunnelPhase * .018 + Time * .22) * (.10 + distanceBack * .045),
            cos(distanceBack * .41 - WarpTunnelPhase * .014 - Time * .18) * (.08 + distanceBack * .035));
    vec2 wakeDelta = across - wakeCenter;
    float wakeRadius = .20 + distanceBack * .040 + activity * .08;
    float spineNoise = .34 + .66 * smoothstep(.30, .78, broad * .56 + curls * .44);
    float spine = exp(-dot(wakeDelta, wakeDelta) / max(wakeRadius * wakeRadius, .001)) * spineNoise;
    float shoulderRadius = wakeRadius * (1.55 + .12 * sin(distanceBack * .72 + Time * .30));
    float shoulder = exp(-pow((length(wakeDelta) - shoulderRadius) / max(wakeRadius * .72, .035), 2.0))
            * (.28 + .72 * filaments) * (1.0 - smoothstep(12.0, 24.0, distanceBack));
    float shell = length(delta) - (.4 + WarpShock.x * 9.5);
    float pressure = exp(-shell * shell * 3.0) * WarpShock.y * (.12 + .22 * curls);
    return SmokeStrength * envelope * hullClearance
            * (clumps * activity * 3.35 + spine * activity * 2.25 + shoulder * activity * .72 + pressure * 1.25);
}
