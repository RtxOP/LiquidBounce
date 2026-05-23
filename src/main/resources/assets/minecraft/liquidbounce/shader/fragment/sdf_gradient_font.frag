#version 120

uniform sampler2D font_texture;
uniform vec4 textColor;
uniform vec2 atlasSize;
uniform float pxRange;
uniform float offset;
uniform vec2 strength;
uniform float speed;
uniform int maxColors;
uniform vec4 colors[9];

float screenPxRange() {
    vec2 unitRange = vec2(pxRange) / atlasSize;
    vec2 screenTexSize = vec2(1.0) / fwidth(gl_TexCoord[0].st);
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}

void main() {
    float distanceValue = texture2D(font_texture, gl_TexCoord[0].st).a;
    float alpha = clamp(screenPxRange() * (distanceValue - 0.5) + 0.5, 0.0, 1.0);

    vec2 pos = gl_FragCoord.xy * strength;
    float param = mod(pos.x + pos.y + offset * speed, 1.0);
    float segment = 1.0 / float(maxColors);
    float index = param / segment;
    float frac = mod(index, 1.0);
    float idx1 = mod(floor(index), float(maxColors));
    float idx2 = mod(idx1 + 1.0, float(maxColors));
    vec4 gradientColor = mix(colors[int(idx1)], colors[int(idx2)], frac);

    gl_FragColor = vec4(gradientColor.rgb, textColor.a * alpha);
}
