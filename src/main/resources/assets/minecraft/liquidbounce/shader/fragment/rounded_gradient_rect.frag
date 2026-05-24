#version 120

uniform vec2 rectSize;
uniform vec4 radii;
uniform vec4 startColor;
uniform vec4 endColor;

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
    vec4 color = mix(startColor, endColor, clamp(uv.x, 0.0, 1.0));
    color.a *= alpha;

    if (color.a <= 0.0) {
        discard;
    }

    gl_FragColor = color;
}
