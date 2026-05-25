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

    float t = iTime * 0.18;
    vec2 drift = vec2(t * 0.32, -t * 0.18);
    float warp = fbm(p * 1.15 + drift);

    float ribbonA = sin((p.x * 1.35 + warp * 1.65 + t) * 3.14159);
    float ribbonB = sin((p.x * -1.05 + p.y * 0.55 + warp * 1.35 - t * 0.8) * 3.14159);
    float aurora = smoothstep(0.55, 1.0, ribbonA * 0.5 + 0.5);
    aurora += smoothstep(0.62, 1.0, ribbonB * 0.5 + 0.5) * 0.65;

    float verticalMask = smoothstep(-0.85, 0.25, p.y) * (1.0 - smoothstep(0.1, 1.25, p.y));
    aurora *= verticalMask;
    aurora *= 0.45 + fbm(p * 2.2 + vec2(-t, t * 0.7)) * 0.55;

    vec3 base = vec3(0.018, 0.023, 0.031);
    vec3 teal = vec3(0.02, 0.36, 0.40);
    vec3 violet = vec3(0.22, 0.12, 0.34);
    vec3 blue = vec3(0.04, 0.10, 0.18);

    float depth = 1.0 - smoothstep(0.0, 1.25, length(p));
    vec3 color = base + blue * depth * 0.42;
    color += mix(teal, violet, smoothstep(-0.2, 0.75, p.x + warp * 0.35)) * aurora * 0.55;

    float vignette = 1.0 - smoothstep(0.28, 1.45, length(p * vec2(0.82, 1.08)));
    color *= 0.54 + vignette * 0.62;

    float grain = hash(gl_FragCoord.xy + iTime * 23.0) - 0.5;
    color += grain / 255.0;

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
