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
//
// Weights are individual uniforms (w0..w12) rather than an array so we
// don't depend on glUniform*fv helpers — only glUniform1f, which is
// supported everywhere.

uniform sampler2D source;
uniform vec2 sourceSize;
uniform float blurRadiusPixels;
uniform int direction;          // 0 = along X, 1 = along Y
uniform float w0, w1, w2, w3, w4, w5, w6, w7, w8, w9, w10, w11, w12;

float weight(int i) {
    if (i == 0) return w0;
    if (i == 1) return w1;
    if (i == 2) return w2;
    if (i == 3) return w3;
    if (i == 4) return w4;
    if (i == 5) return w5;
    if (i == 6) return w6;
    if (i == 7) return w7;
    if (i == 8) return w8;
    if (i == 9) return w9;
    if (i == 10) return w10;
    if (i == 11) return w11;
    return w12;
}

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
        sum += texture2D(source, p + offset) * weight(i);
    }

    gl_FragColor = sum;
}
