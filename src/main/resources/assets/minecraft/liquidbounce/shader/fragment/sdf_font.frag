#version 120

uniform sampler2D font_texture;
uniform vec4 textColor;

void main() {
    float distanceValue = texture2D(font_texture, gl_TexCoord[0].st).a;
    float width = max(fwidth(distanceValue), 0.001);
    float alpha = smoothstep(0.5 - width, 0.5 + width, distanceValue);
    vec4 color = textColor;
    color.a *= alpha;

    if (color.a <= 0.0) {
        discard;
    }

    gl_FragColor = color;
}
