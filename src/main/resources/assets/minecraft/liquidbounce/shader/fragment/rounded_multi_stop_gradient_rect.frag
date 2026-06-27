#version 120

uniform vec2 rectSize;
uniform vec4 radii;
uniform vec4 topLeft;
uniform vec4 topRight;
uniform vec4 bottomLeft;
uniform vec4 bottomRight;

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

    // Bilinear interpolation of 4 corner colors. The primitive emits uv
    // (0,0) at the top-left of the rect, (1,0) at the top-right, (0,1) at
    // the bottom-left, (1,1) at the bottom-right, so each vertex's weight
    // follows naturally from uv.x and uv.y.
    float tx = clamp(uv.x, 0.0, 1.0);
    float ty = clamp(uv.y, 0.0, 1.0);
    float wTL = (1.0 - tx) * (1.0 - ty);
    float wTR = tx * (1.0 - ty);
    float wBL = (1.0 - tx) * ty;
    float wBR = tx * ty;

    vec4 color = topLeft * wTL + topRight * wTR + bottomLeft * wBL + bottomRight * wBR;
    color.rgb += mix(NOISE, -NOISE, fract(sin(dot(uv, vec2(12.9898, 78.233))) * 43758.5453));
    color.a *= alpha;

    if (color.a <= 0.0) {
        discard;
    }

    gl_FragColor = color;
}
