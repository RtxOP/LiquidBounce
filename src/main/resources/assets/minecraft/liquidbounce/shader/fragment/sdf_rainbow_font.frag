#version 120

uniform sampler2D font_texture;
uniform vec4 textColor;
uniform float offset;
uniform vec2 strength;

void main() {
    float distanceValue = texture2D(font_texture, gl_TexCoord[0].st).a;
    float width = max(fwidth(distanceValue), 0.001);
    float alpha = smoothstep(0.5 - width, 0.5 + width, distanceValue);

    vec2 pos = gl_FragCoord.xy * strength;
    vec3 rainbowColor = clamp(
        abs(fract(vec3(mod((pos.x + pos.y + offset), 1.0))
        + vec3(1.0, 0.6666667, 0.3333333)) * 6.0 - vec3(3.0)) - vec3(1.0),
        0.0, 1.0
    );

    gl_FragColor = vec4(rainbowColor, textColor.a * alpha);
}
