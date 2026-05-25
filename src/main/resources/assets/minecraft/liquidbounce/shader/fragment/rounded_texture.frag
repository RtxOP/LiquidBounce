#version 120

uniform sampler2D textureSampler;
uniform vec2 rectSize;
uniform vec4 radii;
uniform vec4 tintColor;
uniform vec4 textureArea;

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
    vec2 textureUv = mix(textureArea.xy, textureArea.zw, uv);
    vec4 texColor = texture2D(textureSampler, textureUv) * tintColor;
    texColor.a *= alpha;

    if (texColor.a <= 0.0) {
        discard;
    }

    gl_FragColor = texColor;
}
