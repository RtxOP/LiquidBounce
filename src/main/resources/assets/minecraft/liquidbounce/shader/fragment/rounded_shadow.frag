#version 120

uniform vec2 rectSize;
uniform vec2 shapeSize;
uniform vec4 radii;
uniform vec4 shadowColor;
uniform float spread;
uniform vec2 offset;

float roundedDistance(vec2 p, vec2 size, vec4 r) {
    vec2 rectP = p - offset;
    vec2 innerSize = shapeSize;
    float radius = r.x;

    if (rectP.x > innerSize.x * 0.5 && rectP.y < innerSize.y * 0.5) {
        radius = r.y;
    } else if (rectP.x > innerSize.x * 0.5 && rectP.y > innerSize.y * 0.5) {
        radius = r.z;
    } else if (rectP.x < innerSize.x * 0.5 && rectP.y > innerSize.y * 0.5) {
        radius = r.w;
    }

    vec2 halfSize = innerSize * 0.5;
    vec2 local = rectP - halfSize;
    vec2 inner = halfSize - vec2(radius);
    vec2 q = abs(local) - inner;

    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

void main() {
    vec2 p = gl_TexCoord[0].st * rectSize;
    float dist = roundedDistance(p, rectSize, radii);
    float edge = smoothstep(-spread * 0.35, 0.0, dist);
    float fade = 1.0 - smoothstep(0.0, spread, dist);
    float shadow = edge * fade * fade;

    vec4 color = shadowColor;
    color.a *= shadow;

    if (color.a <= 0.0) {
        discard;
    }

    gl_FragColor = color;
}
