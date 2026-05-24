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

float sideActivity(vec2 p, vec2 size, vec4 r) {
    float left = sideMask.x;
    float top = sideMask.y;
    float right = sideMask.z;
    float bottom = sideMask.w;

    if (p.x <= r.x && p.y <= r.x) {
        return max(left, top);
    }

    if (p.x >= size.x - r.y && p.y <= r.y) {
        return max(right, top);
    }

    if (p.x >= size.x - r.z && p.y >= size.y - r.z) {
        return max(right, bottom);
    }

    if (p.x <= r.w && p.y >= size.y - r.w) {
        return max(left, bottom);
    }

    float dLeft = p.x;
    float dTop = p.y;
    float dRight = size.x - p.x;
    float dBottom = size.y - p.y;
    if (dLeft <= dTop && dLeft <= dRight && dLeft <= dBottom) {
        return left;
    }

    if (dTop <= dRight && dTop <= dBottom) {
        return top;
    }

    if (dRight <= dBottom) {
        return right;
    }

    return bottom;
}

void main() {
    vec2 p = gl_TexCoord[0].st * rectSize;
    float dist = roundedDistance(p, rectSize, radii);
    float aa = max(fwidth(dist), 0.75);
    float shapeAlpha = 1.0 - smoothstep(-aa, aa, dist);

    float innerDist = dist + borderWidth;
    float borderAlpha = borderWidth > 0.0 ? smoothstep(-aa, aa, innerDist) * sideActivity(p, rectSize, radii) : 0.0;

    vec4 color = mix(fillColor, borderColor, borderAlpha);
    color.a *= shapeAlpha;

    if (color.a <= 0.0) {
        discard;
    }

    gl_FragColor = color;
}
