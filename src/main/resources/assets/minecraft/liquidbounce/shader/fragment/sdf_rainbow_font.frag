#version 120

uniform sampler2D font_texture;
uniform vec4 textColor;
uniform vec2 atlasSize;
uniform float pxRange;
uniform float offset;
uniform vec2 strength;

float screenPxRange() {
    vec2 unitRange = vec2(pxRange) / atlasSize;
    vec2 screenTexSize = vec2(1.0) / fwidth(gl_TexCoord[0].st);
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}

void main() {
    float distanceValue = texture2D(font_texture, gl_TexCoord[0].st).a;
    float alpha = clamp(screenPxRange() * (distanceValue - 0.5) + 0.5, 0.0, 1.0);

    vec2 pos = gl_FragCoord.xy * strength;
    vec3 rainbowColor = clamp(
        abs(fract(vec3(mod((pos.x + pos.y + offset), 1.0))
        + vec3(1.0, 0.6666667, 0.3333333)) * 6.0 - vec3(3.0)) - vec3(1.0),
        0.0, 1.0
    );

    gl_FragColor = vec4(rainbowColor, textColor.a * alpha);
}
