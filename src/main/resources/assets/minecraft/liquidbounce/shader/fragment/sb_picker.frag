#version 120

uniform int mode;
uniform float hue;
uniform vec4 baseColor;
uniform vec2 rectSize;
uniform float cornerRadius;

vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

float roundedDistance(vec2 p, vec2 size, float r) {
    vec2 halfSize = size * 0.5;
    vec2 q = abs(p - halfSize) - (halfSize - vec2(r));
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec2 uv = clamp(gl_TexCoord[0].st, 0.0, 1.0);
    vec2 px = uv * rectSize;

    float dist = roundedDistance(px, rectSize, cornerRadius);
    float aa = max(fwidth(dist), 0.75);
    float shapeAlpha = 1.0 - smoothstep(-aa, aa, dist);
    if (shapeAlpha <= 0.0) {
        discard;
    }

    vec4 color;
    if (mode == 0) {
        // HSB saturation/value square. uv.x = saturation (0..1),
        // uv.y inverted gives value/brightness (1..0 top-to-bottom).
        float s = uv.x;
        float v = 1.0 - uv.y;
        color = vec4(hsv2rgb(vec3(hue, s, v)), 1.0);
    } else if (mode == 1) {
        // Vertical hue strip: rainbow gradient top-to-bottom.
        color = vec4(hsv2rgb(vec3(uv.y, 1.0, 1.0)), 1.0);
    } else if (mode == 2) {
        // Alpha gradient at the current color, used on top of a checker.
        color = vec4(baseColor.rgb, uv.x);
    } else if (mode == 3) {
        // 8x1 checker. Single horizontal row of alternating gray cells;
        // the alpha gradient is drawn on top to show transparency.
        vec2 cell = floor(uv * vec2(8.0, 1.0));
        float check = mod(cell.x, 2.0);
        color = vec4(mix(vec3(0.18), vec3(0.34), check), 1.0);
    } else {
        color = vec4(1.0, 0.0, 1.0, 1.0);
    }

    color.a *= shapeAlpha;
    gl_FragColor = color;
}
