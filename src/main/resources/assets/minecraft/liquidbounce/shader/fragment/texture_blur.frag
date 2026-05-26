#version 120

uniform sampler2D texture;
uniform vec2 texelSize;
uniform vec2 direction;
uniform float radius;

void main() {
    vec2 uv = gl_TexCoord[0].st;
    vec4 color = vec4(0.0);
    float total = 0.0;

    for (int i = -12; i <= 12; i++) {
        float offset = float(i);
        float weight = max(0.0, radius + 1.0 - abs(offset));
        color += texture2D(texture, uv + direction * texelSize * offset) * weight;
        total += weight;
    }

    gl_FragColor = color / total;
}
