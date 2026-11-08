// Low-cost diffuse irradiance estimate from three occluded hemisphere directions.
// This uses the analytic cloud field, not screen-space history or a claim of full multi-bounce/path-traced GI.
vec3 cloudIrradiance(vec3 p, vec3 normal, float timeTwist, vec3 flow) {
    if (IndirectStrength <= .001 || CoreStrength <= .001) return vec3(0.0);
    vec3 axis = abs(normal.y) < .9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 tangent = normalize(cross(normal, axis));
    vec3 bitangent = cross(normal, tangent);
    vec3 bounce = vec3(0.0);
    for (int i = 0; i < 3; i++) {
        float angle = float(i) * 2.0943951;
        vec3 direction = normal * .78 + (tangent * cos(angle) + bitangent * sin(angle)) * .6258;
        vec3 probe = p + direction * .72;
        float localCloud = max(coarseDensity(probe, timeTwist, flow), 0.0);
        float farCloud = max(coarseDensity(p + direction * 2.1, timeTwist, flow), 0.0);
        float escape = exp(-localCloud * .18 - farCloud * .22);
        float tint = smoothstep(-12.0, 14.0, probe.x + probe.z * .45);
        vec3 source = mix(vec3(.65, .31, .15), vec3(.27, .66, 1.35), tint) * CoreStrength;
        vec3 toLight = CORE - probe;
        float lightTransfer = exp(-max(coarseDensity(probe + normalize(toLight) * 1.2, timeTwist, flow), 0.0) * .22);
        float falloff = 78.0 / (8.0 + dot(toLight, toLight));
        bounce += source * (min(falloff, 1.5) * (.11 + .30 * escape) * lightTransfer);
    }
    return min(bounce * (1.0 / 3.0), vec3(.65));
}

vec3 craftSurface(vec3 p, vec4 surface, vec4 normalData, vec4 pbr) {
    vec3 paint = max(surface.rgb, vec3(0.0));
    vec3 normal = normalData.xyz;
    float metallic = clamp(pbr.x, 0.0, 1.0);
    float roughness = clamp(pbr.y, .30, .98);
    float materialOcclusion = clamp(pbr.z, 0.0, 1.0);
    vec3 emissive = paint * max(pbr.w, 0.0);

    vec3 toCore = CORE - p;
    vec3 incoming = normalize(toCore);
    vec3 viewDir = normalize(Eye - p);
    float ndl = max(dot(normal, incoming), 0.0);
    float ndv = max(dot(normal, viewDir), .001);
    vec3 halfSum = incoming + viewDir;
    vec3 halfV = halfSum * inversesqrt(max(dot(halfSum, halfSum), 1e-6));
    float ndh = max(dot(normal, halfV), 0.0);
    float vdh = max(dot(viewDir, halfV), 0.0);
    float alpha = max(roughness * roughness, .09);
    float alpha2 = alpha * alpha;
    float den = ndh * ndh * (alpha2 - 1.0) + 1.0;
    float distribution = alpha2 / max(3.14159265 * den * den, 1e-5);
    float k = (roughness + 1.0) * (roughness + 1.0) * .125;
    float geometry = ndv / (ndv * (1.0 - k) + k)
                   * ndl / max(ndl * (1.0 - k) + k, .001);
    vec3 f0 = mix(vec3(.04), paint, metallic);
    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - vdh, 5.0);
    vec3 specular = fresnel * (distribution * geometry / max(4.0 * ndv * ndl, .001));

    // Sample the cloud's actual source palette and shadow field, rather than an unrelated white key light.
    float timeTwist = .13 * sin(Time * .045);
    vec3 flow = vec3(Time * .016, -Time * .023, Time * .009) + CraftForward * WarpDistance;
    float occlusion = shadowDensity(p + normal * .07 + incoming * .32, timeTwist, flow) * .65
                    + coarseDensity(p + incoming * 1.5, timeTwist, flow) * 1.5;
    float visibility = exp(-max(occlusion, 0.0) * .9);
    float tint = smoothstep(-12.0, 14.0, p.x + p.z * .45);
    vec3 source = mix(vec3(.65, .31, .15), vec3(.27, .66, 1.35), tint) * CoreStrength;
    float sourceFalloff = 78.0 / (8.0 + dot(toCore, toCore));
    float skyFacing = .35 + .65 * max(dot(normal, vec3(-.520, .832, -.208)), 0.0);
    vec3 ambient = vec3(.050, .072, .105) * (.6 + .4 * skyFacing) * materialOcclusion;
    vec3 cloudBounce = source * ((.06 + .09 * visibility) * skyFacing);
    vec3 key = source * (visibility * (.36 + .12 * sourceFalloff));
    vec3 diffusePaint = paint * (1.0 - metallic);
    vec3 diffuseEnergy = (1.0 - fresnel) * diffusePaint;
    vec3 irradiance = cloudIrradiance(p, normal, timeTwist, flow) * IndirectStrength;
    vec3 color = diffuseEnergy * (ambient + cloudBounce + key * ndl + irradiance * materialOcclusion);
    color += key * specular * ndl * .35;
    color += irradiance * f0 * (.05 + .06 * (1.0 - roughness));
    color += emissive * (0.62 + 0.38 * visibility);

    for (int light = 0; light < 2; light++) {
        if (light >= LightCount) break;
        vec4 lp = texelFetch(DataSampler, 24 + light);
        vec3 delta = p - lp.xyz;
        color += paint * vec3(.35, .55, 1.0) * lp.w * 2.2 / (1.0 + dot(delta, delta));
    }
    color += paint * vec3(.6, .78, 1.0) * GlobalFlash * 0.42;
    // Lift the dark titanium planes with a restrained field fill. The boost is
    // applied after the GGX/GI terms so it improves silhouette readability without
    // turning the material into a broad white emitter.
    color += vec3(.008, .012, .020) * materialOcclusion;
    return color * 1.34;
}
