#version 120

uniform vec4 color;
uniform float innerRadius;
uniform float outerRadius;
uniform float startAngle;
uniform float endAngle;

const float PI = 3.14159265358979323846;

float angleMask(float angle, float start, float end) {
    float a = mod(angle + PI * 2.0, PI * 2.0);
    float s = mod(start + PI * 2.0, PI * 2.0);
    float e = mod(end + PI * 2.0, PI * 2.0);

    if (abs(end - start) >= PI * 2.0 - 0.001) {
        return 1.0;
    }

    if (s <= e) {
        return step(s, a) * step(a, e);
    }

    return max(step(s, a), step(a, e));
}

void main() {
    vec2 p = gl_TexCoord[0].st * 2.0 - 1.0;
    float d = length(p);
    float aa = max(fwidth(d), 0.0025);
    float outerAlpha = 1.0 - smoothstep(outerRadius - aa, outerRadius + aa, d);
    float innerAlpha = smoothstep(innerRadius - aa, innerRadius + aa, d);
    float alpha = outerAlpha * innerAlpha;

    float angle = atan(p.y, p.x);
    alpha *= angleMask(angle, startAngle, endAngle);

    vec4 outColor = color;
    outColor.a *= alpha;

    if (outColor.a <= 0.0) {
        discard;
    }

    gl_FragColor = outColor;
}
