#version 120

uniform vec2 iResolution;
uniform float iTime;

float softCircle(vec2 p, vec2 center, vec2 scale, float radius) {
    return smoothstep(radius, 0.0, length((p - center) * scale));
}

void main() {
    vec2 resolution = max(iResolution, vec2(1.0));
    vec2 uv = gl_FragCoord.xy / resolution;
    vec2 p = (gl_FragCoord.xy * 2.0 - resolution) / resolution.y;

    vec3 bottom = vec3(0.028, 0.029, 0.032);
    vec3 top = vec3(0.060, 0.062, 0.068);
    vec3 color = mix(bottom, top, smoothstep(0.0, 1.0, uv.y));

    float drift = sin(iTime * 0.08) * 0.035;
    float upperGlow = softCircle(p, vec2(-0.38 + drift, 0.34), vec2(0.92, 1.18), 1.28);
    float lowerGlow = softCircle(p, vec2(0.48 - drift, -0.54), vec2(1.18, 0.82), 1.16);
    float centerLift = softCircle(p, vec2(0.02, -0.08), vec2(0.82, 1.0), 1.42);

    color += vec3(0.045, 0.055, 0.070) * upperGlow * 0.42;
    color += vec3(0.030, 0.052, 0.082) * lowerGlow * 0.30;
    color += vec3(0.040, 0.040, 0.044) * centerLift * 0.20;

    float vignette = smoothstep(1.35, 0.22, length(p * vec2(0.76, 1.0)));
    color *= 0.62 + vignette * 0.54;

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
