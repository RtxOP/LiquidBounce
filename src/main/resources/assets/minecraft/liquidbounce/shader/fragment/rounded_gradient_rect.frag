#version 120

uniform vec2 rectSize;
uniform vec4 radii;
uniform vec4 startColor;
uniform vec4 endColor;
uniform float vertical;

#define NOISE (0.5 / 255.0)

float roundedDistance(vec2 p, vec2 size, vec4 r) {
    float radius = r.x;

    if (p.x > size.x * 0.5 && p.y < size.y * 0.5) {
        radius = r.y;
    } else if (p.x > size.x * 0.5 && p.y > size.y * 0.5) {
        radius = r.z;
    } else if (p.x < size.x * 0.5 && p.y > size.y * 0.5) {
        radius = r.w;
    }

    vec2 halfSize = size * 0.5;
    vec2 local = p - halfSize;
    vec2 inner = halfSize - vec2(radius);
    vec2 q = abs(local) - inner;

    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

void main() {
    vec2 uv = gl_TexCoord[0].st;
    float dist = roundedDistance(uv * rectSize, rectSize, radii);
    float aa = max(fwidth(dist), 0.75);
    float alpha = 1.0 - smoothstep(-aa, aa, dist);
    vec4 color = mix(startColor, endColor, clamp(mix(uv.x, uv.y, vertical), 0.0, 1.0));
    color.rgb += mix(NOISE, -NOISE, fract(sin(dot(uv, vec2(12.9898, 78.233))) * 43758.5453));
    color.a *= alpha;

    if (color.a <= 0.0) {
        discard;
    }

    gl_FragColor = color;
}
