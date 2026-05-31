#version 120

uniform vec2 iResolution;
uniform float iTime;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);

    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));

    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;

    for (int i = 0; i < 4; i++) {
        value += amplitude * noise(p);
        p = mat2(1.62, 1.18, -1.18, 1.62) * p + 17.0;
        amplitude *= 0.5;
    }

    return value;
}

void main() {
    vec2 resolution = max(iResolution, vec2(1.0));
    vec2 uv = gl_FragCoord.xy / resolution;
    vec2 p = (gl_FragCoord.xy * 2.0 - resolution) / resolution.y;

    float t = iTime * 0.115;
    vec2 drift = vec2(t * 0.24, -t * 0.15);
    float warp = fbm(p * 0.95 + drift);
    float detailWarp = fbm(p * 1.75 + vec2(-t * 0.72, t * 0.48));

    float broadCloud = fbm(p * 0.72 + drift + warp * 0.34);
    float fineCloud = fbm(p * 1.85 - drift * 0.65 + detailWarp * 0.24);
    float cloud = smoothstep(0.24, 0.88, broadCloud * 0.72 + fineCloud * 0.42);

    float ribbonA = sin((p.x * 1.12 + p.y * 0.28 + warp * 1.95 + t * 0.72) * 3.14159);
    float ribbonB = sin((p.x * -0.88 + p.y * 0.48 + detailWarp * 1.55 - t * 0.58) * 3.14159);
    float aurora = smoothstep(0.36, 1.0, ribbonA * 0.5 + 0.5);
    aurora += smoothstep(0.42, 1.0, ribbonB * 0.5 + 0.5) * 0.58;

    float verticalMask = smoothstep(-1.05, 0.12, p.y) * (1.0 - smoothstep(0.36, 1.34, p.y));
    float centerGuard = smoothstep(0.08, 0.72, length(p * vec2(0.86, 1.18)));
    aurora *= verticalMask * (0.58 + centerGuard * 0.42);
    aurora *= 0.45 + fineCloud * 0.55;

    vec3 top = vec3(0.035, 0.086, 0.145);
    vec3 bottom = vec3(0.021, 0.045, 0.100);
    vec3 teal = vec3(0.045, 0.360, 0.410);
    vec3 cobalt = vec3(0.055, 0.245, 0.620);
    vec3 violet = vec3(0.300, 0.090, 0.470);
    vec3 rose = vec3(0.520, 0.145, 0.330);

    float skyMix = smoothstep(-0.92, 1.0, uv.y + warp * 0.08);
    vec3 color = mix(bottom, top, skyMix);

    float lowerGlow = 1.0 - smoothstep(-0.42, 0.78, p.y + warp * 0.15);
    color += mix(teal, cobalt, smoothstep(-0.5, 0.95, p.x + detailWarp * 0.25)) * lowerGlow * 0.105;

    vec3 cloudColor = mix(cobalt, teal, smoothstep(-0.25, 0.82, p.y + warp * 0.24));
    cloudColor = mix(cloudColor, violet, smoothstep(0.42, 0.95, detailWarp));
    color += cloudColor * cloud * 0.22;

    vec3 auroraColor = mix(violet, cobalt, smoothstep(-0.35, 0.85, p.x + warp * 0.34));
    auroraColor = mix(auroraColor, rose, smoothstep(0.68, 1.0, detailWarp));
    color += auroraColor * aurora * 0.36;

    float vignette = 1.0 - smoothstep(0.28, 1.55, length(p * vec2(0.78, 1.06)));
    color *= 0.68 + vignette * 0.42;
    color += vec3(0.010, 0.018, 0.028);

    float grain = hash(gl_FragCoord.xy + iTime * 23.0) - 0.5;
    color += grain / 255.0;

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
