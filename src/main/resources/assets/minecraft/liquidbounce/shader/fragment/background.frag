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
    float amplitude = 0.52;

    for (int i = 0; i < 5; i++) {
        value += amplitude * noise(p);
        p = mat2(1.56, 1.04, -1.04, 1.56) * p + vec2(9.7, 4.3);
        amplitude *= 0.5;
    }

    return value;
}

float band(vec2 p, float shift, float width) {
    float wave = p.y + sin(p.x * 1.45 + shift) * 0.22 + sin(p.x * 3.1 - shift * 0.72) * 0.08;
    return 1.0 - smoothstep(0.0, width, abs(wave));
}

void main() {
    vec2 resolution = max(iResolution, vec2(1.0));
    vec2 uv = gl_FragCoord.xy / resolution;
    vec2 p = (gl_FragCoord.xy * 2.0 - resolution) / resolution.y;

    float t = iTime * 0.12;
    vec2 slowDrift = vec2(t * 0.34, -t * 0.18);
    float softNoise = fbm(p * 1.25 + slowDrift);
    float fineNoise = fbm(p * 3.4 - slowDrift.yx);

    vec3 topColor = vec3(0.035, 0.050, 0.090);
    vec3 bottomColor = vec3(0.015, 0.019, 0.033);
    vec3 base = mix(bottomColor, topColor, smoothstep(0.0, 1.0, uv.y));

    float centerGlow = smoothstep(1.45, 0.0, length(p - vec2(-0.34, 0.06)));
    float lowerGlow = smoothstep(1.25, 0.0, length((p - vec2(0.48, -0.42)) * vec2(1.15, 0.85)));
    base += vec3(0.055, 0.085, 0.165) * centerGlow * 0.52;
    base += vec3(0.075, 0.035, 0.145) * lowerGlow * 0.34;

    vec2 auroraP = mat2(0.92, -0.38, 0.38, 0.92) * (p + vec2(0.12, -0.05));
    auroraP.y += (softNoise - 0.5) * 0.52;

    float auroraA = band(auroraP + vec2(t * 0.18, 0.04), 1.1 + t, 0.34);
    float auroraB = band(auroraP * vec2(1.12, 0.82) + vec2(-0.25, 0.22), -0.7 - t * 0.8, 0.24);
    float auroraMask = smoothstep(-0.9, 0.35, p.y) * (1.0 - smoothstep(0.28, 1.15, p.y));
    float aurora = (auroraA * 0.62 + auroraB * 0.42) * auroraMask;
    aurora *= 0.45 + softNoise * 0.55;

    vec3 violet = vec3(0.37, 0.19, 0.72);
    vec3 blue = vec3(0.08, 0.24, 0.62);
    vec3 cyan = vec3(0.05, 0.48, 0.62);
    vec3 auroraColor = mix(violet, blue, smoothstep(-0.55, 0.75, auroraP.x + softNoise * 0.35));
    auroraColor = mix(auroraColor, cyan, smoothstep(0.55, 1.0, fineNoise) * 0.35);

    vec3 color = base + auroraColor * aurora * 0.48;

    float starField = pow(hash(floor(gl_FragCoord.xy * 0.42)), 48.0);
    starField *= smoothstep(0.28, 1.0, uv.y) * 0.35;
    color += vec3(0.62, 0.72, 1.0) * starField;

    float vignette = smoothstep(1.32, 0.28, length(p * vec2(0.78, 1.0)));
    color *= 0.58 + vignette * 0.62;

    float grain = hash(gl_FragCoord.xy + iTime * 19.0) - 0.5;
    color += grain / 220.0;

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
