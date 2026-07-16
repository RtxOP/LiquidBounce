## Repositories

- **barrybingo/CNaturalMouseMotion** — C++11 header-only algorithmic library. Features: deviation/arc creation, noise simulation, speed/flow acceleration-deceleration curves, overshoots, multiple movement "natures" (GrannyMotion, FastGamerMotion, AverageComputerUserMotion, RobotMotion).
- **JoonasVali/NaturalMouseMotion** — Java original of the above.
- **Xetera/ghost-cursor** / **bn-l/ghost-cursor-play** — Algorithmic humanization for web automation. Randomized Bézier control points, Fitts's Law-based speed scaling, configurable overshoot with distance-dependent thresholds, random target point selection within bounding boxes.
- **CloverLabsAI/human-cursor** — Fork adding distortion for natural imperfections and interpolation with variable timing.
- **DefinitelyN0tMe/Macronyx** — Cross-platform macro automation with Bézier mouse curves, configurable curvature/overshoot/speed profiles, per-event tracking, DPI-aware playback.
- **VoltCyclone/Hurra** — RP2350 firmware-level humanization. Uses LFSR-based jitter, fixed sine oscillators, LUT-based scaling, mode-bound parameter ranges, frame-based timing, distance-aware jitter scaling, velocity-based jitter suppression, overshoot simulation. (Documents identify six statistical signatures in this implementation.)

---

## Algorithmic Humanization Techniques

### Path Generation
- Cubic Bézier curves: `B(t) = (1-t)³P₀ + 3(1-t)²tP₁ + 3(1-t)t²P₂ + t³P₃`
- Randomized control points constrained to one side of the direct line to prevent unnatural S-curves
- Variable knot count and discretization steps

### Velocity Profiles
- Trapezoidal velocity model with exponential acceleration approach: `v(t) = v_max · (1 - e^(-t/τ))`
- Minimum-jerk model (5th-degree polynomial): `B(t) = P₀ + (P₁ - P₀)·(10τ³ - 15τ⁴ + 6τ⁵)`
- Logarithmic deceleration near target
- Distance-scaled velocity profiles
- Fitts's Law for movement time calculation: `MT = a + b·log₂(D/W + 1)`

### Noise & Jitter
- Gaussian noise
- Perlin noise
- Distance-scaled jitter: 0.8× for <20 px, 0.5× for 20–60 px, 0.25× for 60–110 px, 0.12× for >110 px
- Velocity-based jitter suppression: factor `0.3 + 0.7·(v/v_max)`
- Signal-dependent noise: amplitude `k·v(t)` where `k ~ U(0.08, 0.15)`
- Pink noise (1/f) via Voss-McCartney algorithm
- Chaotic perturbation via enhanced logistic map (mixed 70% pink + 30% chaotic)
- LFSR-based jitter (Hurra implementation)
- Sine oscillator tremor at fixed frequencies (Hurra implementation)
- LUT-based easing curves and jitter scaling (Hurra implementation)

### Biomechanical & Structural Coupling
- Biomechanical dx/dy coupling via rotation matrices with coupling strength parameter (0.3–0.7)
- Two-thirds power law for velocity-curvature coupling: `v = γ·κ^(-1/3)`

### Timing
- Frame-based onset delays (Hurra implementation)
- Ex-Gaussian reaction time sampling: `f(t) = (1/τ)·exp((σ²/2τ²) - (t-μ)/τ)·Φ((t-μ)/σ - σ/τ)`
- Per-session parameter randomization

### Overshoot & Corrections
- Stochastic overshoot with context-dependent probability based on approach velocity
- Overshoot magnitude 2–8 pixels, velocity-scaled
- Correction trajectory using separate Bézier curve
- Micro-corrections: Poisson(λ=3) with log-normal distributed magnitudes (mean ≈ 0.6 pixels, σ = 0.6 in log-space) and angular noise (SD ≈ 17°)

### Subdivision & Pipelining
- Adaptive subdivision: Poisson(30 + 0.3·D), clamped [15, 200]
- 12-stage pipeline: Session Initialization → Reaction Time Sampling → Movement Time Calculation → Adaptive Subdivision → Base Trajectory → Minimum-Jerk Blend → Signal-Dependent Noise → Tremor Generation → DX/DY Coupling → Two-Thirds Power Law → Stochastic Overshoot → Micro-Corrections

---

## Humanization Detection Methods

### Statistical Signature Analysis
Analysis of six known statistical signatures:
1. **Jitter distributions** — checking for deterministic spectral content, comb-like spectra, or white noise flat spectra
2. **Overshoot probabilities** — checking for fixed step-function probabilities vs. continuous velocity-dependent distributions
3. **Artificial timing patterns** — checking for discrete/quantized frame-based delays vs. continuous distributions
4. **Subdivision behavior** — checking for mode-bound fixed step ranges vs. context-adaptive distributions
5. **Reaction-time window distributions** — checking for tight non-overlapping per-mode ranges vs. wide overlapping distributions
6. **dx/dy correlation** — checking for independent axis processing vs. biomechanical coupling

### Spectral & Correlation Analysis
- Power spectral density analysis of jitter sequences (identifying LFSR comb spectra or sharp spectral peaks)
- Autocorrelation-based detection of periodic pseudo-random patterns
- dx/dy correlation structure analysis (identifying independent vs. coupled axes)

### Trajectory & Kinematic Analysis
- Trajectory curvature consistency across engagements
- Trajectory straightness ratio (distance/path length)
- Velocity profile analysis (bell-shaped vs. constant/instant)
- Angular velocity maximum and standard deviation
- Acceleration and jerk patterns
- Maximum deviation from straight line
- Sum of angles
- Endpoint precision and scatter distribution

### Temporal Analysis
- Reaction time analysis (target visibility to first movement)
- Time-to-lock analysis
- Fire timing relative to lock-on
- Inter-movement interval distributions

### Micro-Behavior Analysis
- Micro-correction frequency (<2px/tick)
- Delta magnitude entropy
- Click timing analysis

### Session-Level Statistical Profiling
- Headshot ratio stability across sessions
- Reaction time coefficient of variation
- Performance variance across sessions
- Engagement rate consistency

### Behavioral Biometrics Feature Extraction
- Mouse dynamics feature extraction (100+ dimensions): tangential velocity, acceleration, jerk, curvature, etc.
- Aggregate statistics (mean, std, max, min) computed across action windows

### Proactive / Honeytoken Detection
- Adversarial patch / honeytoken placement (AimTrap, PATCH) — embedded visual elements that trigger targeting to infer aimbot usage

---

## Mitigation Methods for Detection

### Signature Mitigation
- **Jitter distributions:** Replace LFSR with pink noise + chaotic perturbation to produce correct 1/f spectral envelope without comb-like harmonics
- **Overshoot:** Use continuous velocity-dependent probability instead of fixed per-mode probabilities; base probability multiplied by `(0.5 + v_approach/500)`
- **Timing:** Use ex-Gaussian reaction times instead of discrete frame-based delays
- **Subdivision:** Use distance-adaptive Poisson subdivision instead of mode-bound fixed ranges
- **RT windows:** Use per-session randomization of all base parameters from physiologically-plausible distributions
- **dx/dy correlation:** Use biomechanical coupling via rotation matrices to produce weak correlation (r ≈ -0.2 to +0.2)

### Spectral & Structural Mitigation
- Match human tremor 1/f spectrum in 4–12 Hz band
- Maintain non-periodic temporal structure via chaotic perturbation
- Implement two-thirds power law for correct local velocity-curvature relationship
- Use minimum-jerk blending for biologically plausible velocity profiles

### Parameter Randomization
- Per-session randomization of: RT Gaussian μ, RT exponential τ, jitter scale, overshoot probability, biomechanical coupling strength, SDN coefficient k, pink noise β
- Wide parameter ranges that prevent fixed statistical fingerprinting

---

## Bypass Methods

- **Statistical performance moderation:** Intentionally vary accuracy, reaction time, and engagement rate within population norms across matches to avoid consistent statistical profiling.
