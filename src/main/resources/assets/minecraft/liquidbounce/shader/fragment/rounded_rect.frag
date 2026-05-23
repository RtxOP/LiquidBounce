#version 120

uniform vec2 rectSize;
uniform vec4 radii;
uniform vec4 fillColor;
uniform vec4 borderColor;
uniform float borderWidth;
uniform vec4 sideMask;

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
    vec2 p = gl_TexCoord[0].st * rectSize;
    float dist = roundedDistance(p, rectSize, radii);
    float aa = max(fwidth(dist), 0.75);
    float shapeAlpha = 1.0 - smoothstep(-aa, aa, dist);

    float innerDist = dist + borderWidth;
    float borderAlpha = borderWidth > 0.0 ? smoothstep(-aa, aa, innerDist) : 0.0;

    float left = 1.0 - step(borderWidth, p.x);
    float top = 1.0 - step(borderWidth, p.y);
    float right = step(rectSize.x - borderWidth, p.x);
    float bottom = step(rectSize.y - borderWidth, p.y);
    float activeSide = max(max(left * sideMask.x, top * sideMask.y), max(right * sideMask.z, bottom * sideMask.w));

    borderAlpha *= activeSide;

    vec4 color = mix(fillColor, borderColor, borderAlpha);
    color.a *= shapeAlpha;

    if (color.a <= 0.0) {
        discard;
    }

    gl_FragColor = color;
}
