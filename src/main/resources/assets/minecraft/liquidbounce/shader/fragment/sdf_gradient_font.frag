#version 120

uniform sampler2D font_texture;
uniform vec4 textColor;
uniform float offset;
uniform vec2 strength;
uniform float speed;
uniform int maxColors;
uniform vec4 colors[9];

void main() {
    float distanceValue = texture2D(font_texture, gl_TexCoord[0].st).a;
    float width = max(fwidth(distanceValue), 0.001);
    float alpha = smoothstep(0.5 - width, 0.5 + width, distanceValue);

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
