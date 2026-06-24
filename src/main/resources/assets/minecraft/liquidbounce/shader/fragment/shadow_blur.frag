/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
#version 120

// Separable 1D Gaussian blur — one axis per pass. Two passes (horizontal then
// vertical) on a pair of ping-pong framebuffers approximates the same
// 2D Gaussian that browsers apply for CSS box-shadow, at a fraction of the cost
// (13 lookups per axis per fragment instead of 13² for a 2D kernel).
//
// 13 pre-weighted taps with σ ≈ 2 over the kernel — symmetric, sum to 1.
// Knob: blurRadiusPixels is the radial reach of the kernel; each tap is spaced
// by blurRadiusPixels / 6 so the 13 taps span ±blurRadiusPixels.

uniform sampler2D source;
uniform vec2 sourceSize;
uniform float blurRadiusPixels;
uniform int direction;          // 0 = along X, 1 = along Y
uniform float weights[13];

void main() {
    vec2 p = gl_TexCoord[0].st;
    vec4 sum = vec4(0.0);

    for (int i = 0; i < 13; i++) {
        float t = float(i) - 6.0;                                // -6 .. +6
        float offsetPx = t * blurRadiusPixels / 6.0;             // -R .. +R
        vec2 offset = vec2(0.0);
        if (direction == 0) {
            offset.x = offsetPx / sourceSize.x;
        } else {
            offset.y = offsetPx / sourceSize.y;
        }
        sum += texture2D(source, p + offset) * weights[i];
    }

    gl_FragColor = sum;
}
