#version 120

uniform sampler2D font_texture;
uniform vec4 textColor;
uniform vec2 atlasSize;
uniform float pxRange;

float screenPxRange() {
    vec2 unitRange = vec2(pxRange) / atlasSize;
    vec2 screenTexSize = vec2(1.0) / fwidth(gl_TexCoord[0].st);
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}

void main() {
    float distanceValue = texture2D(font_texture, gl_TexCoord[0].st).a;
    float alpha = clamp(screenPxRange() * (distanceValue - 0.5) + 0.5, 0.0, 1.0);
    vec4 color = textColor;
    color.a *= alpha;

    if (color.a <= 0.0) {
        discard;
    }

    gl_FragColor = color;
}
