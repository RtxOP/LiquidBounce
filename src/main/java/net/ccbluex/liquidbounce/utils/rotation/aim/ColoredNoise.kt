package net.ccbluex.liquidbounce.utils.rotation.aim

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Stage 4 — fitted colored noise (spec §11.2 note (4); sigma(v) refit from
 * Phase-3 step 2, JSON `fitted.noise.by_speed`):
 *  - position AR(1) per axis: n_t = phi*n_{t-1} + eps_t, eps ~ N(0, sigma_e),
 *    sigma_e(bin) = sigma(bin)*sqrt(1 - phi^2) with the floor-fit phi (global);
 *  - sigma looked up by the servo's current speed — the slow-only floor sigma
 *    is bin 0, fast bins come from the binned refit (the known-gap fix);
 *  - z = 2*(u1+u2+u3) - 3 with u ~ U(0,1): unit-variance Irwin-Hall(3),
 *    bounded ±3 (cheap, KS-insensitive at harness resolution);
 *  - gated by AimEngine: drawn on the motion path only — a hold freezes
 *    output AND noise (bit-exact dwells).
 *
 * Pure + seeded: the [Random] comes from `Input.seed` (§11.7.5 policy).
 */
internal object ColoredNoise {

    /** Mutable AR(1) memory; lives in AimEngine.State, cleared on reset. */
    class State {
        var yaw = 0f
        var pitch = 0f
    }

    /** Stationary sigma of the bin containing [speed] (first max_speed > speed). */
    fun sigmaFor(speed: Float, yaw: Boolean): Float {
        val edges = FittedAimParams.NOISE_BIN_MAX_DEG
        val sigmas = if (yaw) FittedAimParams.NOISE_BIN_SIGMA_YAW_DEG
        else FittedAimParams.NOISE_BIN_SIGMA_PITCH_DEG
        var i = 0
        while (i < edges.size - 1 && speed >= edges[i]) i++
        return sigmas[i]
    }

    /**
     * Advance the AR(1) state one tick at the given speed. The caller scales
     * the offsets by `Knobs.noiseScale` and adds them pre-quant (stage 6
     * snaps the result onto the grid). Draw order is fixed (yaw then pitch,
     * 3 uniforms each) so a seed reproduces byte-identical streams.
     */
    fun advance(state: State, rng: Random, speed: Float) {
        val innovY = sigmaFor(speed, yaw = true) *
            sqrt(1f - FittedAimParams.NOISE_PHI_YAW * FittedAimParams.NOISE_PHI_YAW)
        val innovP = sigmaFor(speed, yaw = false) *
            sqrt(1f - FittedAimParams.NOISE_PHI_PITCH * FittedAimParams.NOISE_PHI_PITCH)
        val zy = 2f * (rng.nextFloat() + rng.nextFloat() + rng.nextFloat()) - 3f
        val zp = 2f * (rng.nextFloat() + rng.nextFloat() + rng.nextFloat()) - 3f
        state.yaw = FittedAimParams.NOISE_PHI_YAW * state.yaw + innovY * zy
        state.pitch = FittedAimParams.NOISE_PHI_PITCH * state.pitch + innovP * zp
    }
}
