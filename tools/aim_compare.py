#!/usr/bin/env python3
"""Compare manual, Humanize-off, and Humanize-on aim recordings.

The tool validates schema-1/schema-2 JSON Lines recordings, builds complete
non-overlapping attack windows, reports primary kinematic distributions, and
audits Humanize phase episodes. It is descriptive tooling, not a replica of an
external anti-cheat model.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import random
import statistics
import sys
import tempfile
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable, Sequence


ATTACK_WINDOW_BEFORE = 20
ATTACK_WINDOW_AFTER = 5
PRE_ATTACK_TICKS = 5
SUPPORTED_SCHEMAS = {1, 2}
MIN_WINDOWS_PER_CONDITION = 30
MIN_WINDOWS_PER_MATCHED_BIN = 15
MIN_TRACKING_SAMPLES_PER_MATCHED_BIN = 50
MIN_OVERSHOOT_EPISODES = 20
DEFAULT_BOOTSTRAP_SAMPLES = 500
DEFAULT_SEED = 0
ANALYZER_VERSION = "2.0"

PRIMARY_FEATURES = (
    "peak_speed",
    "rms_acceleration",
    "rms_jerk",
    "reversal_count",
    "yaw_pitch_correlation",
)

MOTION_BINS = (
    ("<1", 0.0, 1.0),
    ("1-<3", 1.0, 3.0),
    ("3-<6", 3.0, 6.0),
    (">=6", 6.0, math.inf),
)


@dataclass
class Recording:
    label: str
    path: Path
    metadata: dict[str, Any]
    ticks: dict[int, dict[str, Any]]
    attacks: list[dict[str, Any]]
    warnings: list[str]


def wrap_angle(value: float) -> float:
    return (value + 180.0) % 360.0 - 180.0


def angle_delta(current: float, previous: float) -> float:
    return wrap_angle(current - previous)


def finite(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def nullable_bool(value: Any) -> bool | None:
    return value if isinstance(value, bool) else None


def append_warning(warnings: list[str], warning: str) -> None:
    if warning not in warnings:
        warnings.append(warning)


def validate_schema_two_tick(
    tick: dict[str, Any], line_number: int, warnings: list[str]
) -> None:
    alpha = tick.get("humanize_alpha")
    if alpha is not None:
        parsed = finite(alpha)
        if parsed is None or not 0.0 <= parsed <= 1.0:
            append_warning(
                warnings,
                f"line {line_number}: humanize_alpha is not a finite value in [0, 1]",
            )

    for field in ("humanize_target_stable", "humanize_overshoot_armed"):
        value = tick.get(field)
        if value is not None and not isinstance(value, bool):
            append_warning(warnings, f"line {line_number}: {field} is not boolean or null")


def load_recording(path: Path, label: str) -> Recording:
    metadata: dict[str, Any] = {}
    ticks: dict[int, dict[str, Any]] = {}
    attacks: list[dict[str, Any]] = []
    warnings: list[str] = []

    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exception:
                append_warning(
                    warnings,
                    f"line {line_number}: invalid JSON ({exception.msg})",
                )
                continue

            if not isinstance(record, dict):
                append_warning(warnings, f"line {line_number}: record is not an object")
                continue

            record_type = record.get("type")
            if record_type == "session":
                if metadata:
                    append_warning(warnings, f"line {line_number}: duplicate session record")
                else:
                    metadata = record
            elif record_type == "tick":
                tick_number = record.get("tick")
                if not isinstance(tick_number, int):
                    append_warning(
                        warnings,
                        f"line {line_number}: tick record has no integer tick",
                    )
                    continue
                if tick_number in ticks:
                    append_warning(
                        warnings,
                        f"line {line_number}: duplicate tick {tick_number}; using the last record",
                    )
                record["_line"] = line_number
                ticks[tick_number] = record
            elif record_type == "attack":
                attacks.append(record)

    if not metadata:
        append_warning(warnings, "missing session metadata")
    else:
        schema = metadata.get("schema")
        if schema not in SUPPORTED_SCHEMAS:
            append_warning(
                warnings,
                f"unsupported schema {schema!r}; expected one of {sorted(SUPPORTED_SCHEMAS)}",
            )
        elif schema == 2:
            for tick in ticks.values():
                validate_schema_two_tick(tick, tick.get("_line", -1), warnings)

    if not ticks:
        append_warning(warnings, "contains no tick records")

    attacks.sort(key=lambda item: item.get("tick", -1))
    return Recording(label, path, metadata, ticks, attacks, warnings)


def attack_target_windows(recording: Recording) -> dict[int, int]:
    result: dict[int, tuple[int, int]] = {}

    for attack in recording.attacks:
        attack_tick = attack.get("tick")
        target_id = attack.get("target_id")
        if not isinstance(attack_tick, int) or not isinstance(target_id, int):
            continue
        for tick in range(
            attack_tick - ATTACK_WINDOW_BEFORE,
            attack_tick + ATTACK_WINDOW_AFTER + 1,
        ):
            if tick not in recording.ticks:
                continue
            distance = abs(tick - attack_tick)
            previous = result.get(tick)
            if previous is None or distance < previous[0]:
                result[tick] = (distance, target_id)

    return {tick: value[1] for tick, value in result.items()}


def targets_by_id(tick: dict[str, Any]) -> dict[int, dict[str, Any]]:
    targets = tick.get("targets")
    if not isinstance(targets, list):
        return {}
    return {
        target["id"]: target
        for target in targets
        if isinstance(target, dict) and isinstance(target.get("id"), int)
    }


def choose_reference_target(
    tick: dict[str, Any], attack_target_id: int | None
) -> tuple[int | None, dict[str, Any] | None]:
    targets = targets_by_id(tick)
    for target_id in (attack_target_id, tick.get("kill_aura_target_id")):
        if isinstance(target_id, int) and target_id in targets:
            return target_id, targets[target_id]

    if not targets:
        return None, None

    target = min(
        targets.values(),
        key=lambda item: finite(item.get("angular_distance")) or math.inf,
    )
    return target["id"], target


def exclusion_row(
    recording: Recording,
    row_type: str,
    reason: str,
    tick: int | None = None,
    attack_tick: int | None = None,
) -> dict[str, Any]:
    return {
        "label": recording.label,
        "session": recording.path.name,
        "row_type": row_type,
        "tick": tick,
        "attack_tick": attack_tick,
        "reason": reason,
    }


def derive_samples(recording: Recording) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    samples: list[dict[str, Any]] = []
    exclusions: list[dict[str, Any]] = []
    attack_targets = attack_target_windows(recording)
    gcd = finite(recording.metadata.get("gcd")) or 0.0
    schema = recording.metadata.get("schema")
    previous: dict[str, Any] | None = None

    for tick_number in sorted(recording.ticks):
        tick = recording.ticks[tick_number]
        hold_reason = None
        if tick.get("no_rotate_hold"):
            hold_reason = "no_rotate"
        elif tick.get("short_stop_hold"):
            hold_reason = "short_stop"

        if hold_reason:
            exclusions.append(
                exclusion_row(recording, "tick", hold_reason, tick=tick_number)
            )
            previous = None
            continue

        server_yaw = finite(tick.get("server_yaw"))
        server_pitch = finite(tick.get("server_pitch"))
        if server_yaw is None or server_pitch is None:
            exclusions.append(
                exclusion_row(
                    recording,
                    "tick",
                    "missing_server_rotation",
                    tick=tick_number,
                )
            )
            previous = None
            continue

        target_id, target = choose_reference_target(tick, attack_targets.get(tick_number))
        reference_yaw = finite(target.get("reference_yaw")) if target else None
        reference_pitch = finite(target.get("reference_pitch")) if target else None
        requested_yaw = finite(tick.get("requested_yaw"))
        requested_pitch = finite(tick.get("requested_pitch"))

        reference_error_yaw = (
            wrap_angle(reference_yaw - server_yaw) if reference_yaw is not None else None
        )
        reference_error_pitch = (
            reference_pitch - server_pitch if reference_pitch is not None else None
        )
        reference_error = (
            math.hypot(reference_error_yaw, reference_error_pitch)
            if reference_error_yaw is not None and reference_error_pitch is not None
            else None
        )

        exact_error_yaw = (
            wrap_angle(requested_yaw - server_yaw) if requested_yaw is not None else None
        )
        exact_error_pitch = (
            requested_pitch - server_pitch if requested_pitch is not None else None
        )
        exact_error = (
            math.hypot(exact_error_yaw, exact_error_pitch)
            if exact_error_yaw is not None and exact_error_pitch is not None
            else None
        )

        sample: dict[str, Any] = {
            "label": recording.label,
            "session": recording.path.name,
            "schema": schema,
            "tick": tick_number,
            "gcd": gcd,
            "source": tick.get("source"),
            "rotation_active": bool(tick.get("rotation_active")),
            "humanize": nullable_bool(tick.get("humanize")),
            "phase": tick.get("phase") if isinstance(tick.get("phase"), str) else None,
            "has_overshoot": bool(tick.get("has_overshoot")),
            "humanize_alpha": finite(tick.get("humanize_alpha")),
            "humanize_target_stable": nullable_bool(tick.get("humanize_target_stable")),
            "humanize_overshoot_armed": nullable_bool(tick.get("humanize_overshoot_armed")),
            "server_yaw": server_yaw,
            "server_pitch": server_pitch,
            "requested_yaw": requested_yaw,
            "requested_pitch": requested_pitch,
            "target_id": target_id,
            "reference_yaw": reference_yaw,
            "reference_pitch": reference_pitch,
            "reference_error_yaw": reference_error_yaw,
            "reference_error_pitch": reference_error_pitch,
            "reference_error": reference_error,
            "exact_error_yaw": exact_error_yaw,
            "exact_error_pitch": exact_error_pitch,
            "exact_error": exact_error,
            "player_horizontal_speed": math.hypot(
                finite(tick.get("player_motion_x")) or 0.0,
                finite(tick.get("player_motion_z")) or 0.0,
            ),
            "player_speed_amplifier": tick.get("player_speed_amplifier", -1),
            "target_horizontal_speed": (
                math.hypot(
                    finite(target.get("motion_x")) or 0.0,
                    finite(target.get("motion_z")) or 0.0,
                )
                if target
                else None
            ),
            "target_speed_amplifier": target.get("speed_amplifier", -1) if target else None,
        }

        consecutive = previous is not None and tick_number - previous["tick"] == 1
        if consecutive:
            yaw_velocity = angle_delta(server_yaw, previous["server_yaw"])
            pitch_velocity = server_pitch - previous["server_pitch"]
            sample["yaw_velocity"] = yaw_velocity
            sample["pitch_velocity"] = pitch_velocity
            sample["angular_speed"] = math.hypot(yaw_velocity, pitch_velocity)

            if (
                requested_yaw is not None
                and requested_pitch is not None
                and previous.get("requested_yaw") is not None
                and previous.get("requested_pitch") is not None
            ):
                requested_yaw_velocity = angle_delta(
                    requested_yaw, previous["requested_yaw"]
                )
                requested_pitch_velocity = requested_pitch - previous["requested_pitch"]
                sample["requested_target_yaw_velocity"] = requested_yaw_velocity
                sample["requested_target_pitch_velocity"] = requested_pitch_velocity
                sample["requested_target_angular_speed"] = math.hypot(
                    requested_yaw_velocity, requested_pitch_velocity
                )

            if target_id is not None and target_id == previous.get("target_id"):
                if reference_yaw is not None and previous.get("reference_yaw") is not None:
                    target_yaw_velocity = angle_delta(
                        reference_yaw, previous["reference_yaw"]
                    )
                    target_pitch_velocity = reference_pitch - previous["reference_pitch"]
                    sample["reference_target_angular_speed"] = math.hypot(
                        target_yaw_velocity, target_pitch_velocity
                    )

            if previous.get("yaw_velocity") is not None:
                yaw_acceleration = yaw_velocity - previous["yaw_velocity"]
                pitch_acceleration = pitch_velocity - previous["pitch_velocity"]
                sample["yaw_acceleration"] = yaw_acceleration
                sample["pitch_acceleration"] = pitch_acceleration
                sample["angular_acceleration"] = math.hypot(
                    yaw_acceleration, pitch_acceleration
                )

                if previous.get("yaw_acceleration") is not None:
                    yaw_jerk = yaw_acceleration - previous["yaw_acceleration"]
                    pitch_jerk = pitch_acceleration - previous["pitch_acceleration"]
                    sample["yaw_jerk"] = yaw_jerk
                    sample["pitch_jerk"] = pitch_jerk
                    sample["angular_jerk"] = math.hypot(yaw_jerk, pitch_jerk)

        samples.append(sample)
        previous = sample

    return samples, exclusions


def values(rows: Iterable[dict[str, Any]], key: str) -> list[float]:
    result = []
    for row in rows:
        value = finite(row.get(key))
        if value is not None:
            result.append(value)
    return result


def rms(items: Sequence[float]) -> float | None:
    return math.sqrt(sum(item * item for item in items) / len(items)) if items else None


def correlation(left: Sequence[float], right: Sequence[float]) -> float | None:
    pairs = [(x, y) for x, y in zip(left, right) if math.isfinite(x) and math.isfinite(y)]
    if len(pairs) < 3:
        return None
    xs, ys = zip(*pairs)
    mean_x = statistics.fmean(xs)
    mean_y = statistics.fmean(ys)
    numerator = sum((x - mean_x) * (y - mean_y) for x, y in pairs)
    denominator = math.sqrt(
        sum((x - mean_x) ** 2 for x in xs)
        * sum((y - mean_y) ** 2 for y in ys)
    )
    return numerator / denominator if denominator else None


def quantile(items: Sequence[float], probability: float) -> float | None:
    if not items:
        return None
    ordered = sorted(items)
    position = (len(ordered) - 1) * probability
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def ks_statistic(left: Sequence[float], right: Sequence[float]) -> float | None:
    if not left or not right:
        return None
    left_sorted = sorted(left)
    right_sorted = sorted(right)
    left_index = right_index = 0
    maximum = 0.0
    while left_index < len(left_sorted) or right_index < len(right_sorted):
        candidates = []
        if left_index < len(left_sorted):
            candidates.append(left_sorted[left_index])
        if right_index < len(right_sorted):
            candidates.append(right_sorted[right_index])
        point = min(candidates)
        while left_index < len(left_sorted) and left_sorted[left_index] <= point:
            left_index += 1
        while right_index < len(right_sorted) and right_sorted[right_index] <= point:
            right_index += 1
        maximum = max(
            maximum,
            abs(left_index / len(left_sorted) - right_index / len(right_sorted)),
        )
    return maximum


def motion_bin(speed: float | None) -> str:
    if speed is None or not math.isfinite(speed):
        return "unknown"
    for name, lower, upper in MOTION_BINS:
        if lower <= speed < upper:
            return name
    return "unknown"


def speed_effect_context(samples: Sequence[dict[str, Any]]) -> str:
    amplifiers = {
        sample.get("player_speed_amplifier", -1)
        for sample in samples
        if isinstance(sample.get("player_speed_amplifier", -1), int)
    }
    if amplifiers == {-1}:
        return "none"
    if amplifiers == {0}:
        return "speed_i"
    if amplifiers == {1}:
        return "speed_ii"
    return "mixed_or_other"


def reversal_count(samples: Sequence[dict[str, Any]], gcd: float) -> int:
    result = 0
    velocities = [
        (sample.get("yaw_velocity"), sample.get("pitch_velocity"))
        for sample in samples
    ]
    for previous, current in zip(velocities, velocities[1:]):
        if None in previous or None in current:
            continue
        previous_speed = math.hypot(previous[0], previous[1])
        current_speed = math.hypot(current[0], current[1])
        if previous_speed > gcd and current_speed > gcd:
            if previous[0] * current[0] + previous[1] * current[1] < 0:
                result += 1
    return result


def extract_window_features(
    recording: Recording,
    attack: dict[str, Any],
    window: Sequence[dict[str, Any]],
) -> dict[str, Any]:
    speeds = values(window, "angular_speed")
    accelerations = values(window, "angular_acceleration")
    jerks = values(window, "angular_jerk")
    yaw_velocities = values(window, "yaw_velocity")
    pitch_velocities = values(window, "pitch_velocity")
    target_speeds = values(window, "requested_target_angular_speed")
    exact_errors = values(window, "exact_error")
    gcd = max(values(window, "gcd"), default=0.0)
    attack_tick = int(attack["tick"])
    attack_sample = next(sample for sample in window if sample["tick"] == attack_tick)
    pre_attack = [
        sample
        for sample in window
        if attack_tick - PRE_ATTACK_TICKS <= sample["tick"] < attack_tick
    ]
    mean_target_speed = statistics.fmean(target_speeds) if target_speeds else None
    phases = Counter(sample.get("phase") for sample in window if sample.get("phase"))

    return {
        "label": recording.label,
        "session": recording.path.name,
        "schema": recording.metadata.get("schema"),
        "attack_tick": attack_tick,
        "attack_target_id": attack.get("target_id"),
        "window_start": window[0]["tick"],
        "window_end": window[-1]["tick"],
        "duration_ticks": len(window),
        "peak_speed": max(speeds) if speeds else None,
        "rms_acceleration": rms(accelerations),
        "rms_jerk": rms(jerks),
        "reversal_count": reversal_count(window, gcd),
        "yaw_pitch_correlation": correlation(yaw_velocities, pitch_velocities),
        "mean_requested_target_speed": mean_target_speed,
        "target_motion_bin": motion_bin(mean_target_speed),
        "speed_effect_context": speed_effect_context(window),
        "attack_exact_error": attack_sample.get("exact_error"),
        "window_exact_error_median": quantile(exact_errors, 0.5),
        "window_exact_error_p90": quantile(exact_errors, 0.9),
        "pre_attack_exact_error_median": quantile(values(pre_attack, "exact_error"), 0.5),
        "pre_attack_exact_error_p90": quantile(values(pre_attack, "exact_error"), 0.9),
        "pre_attack_speed_median": quantile(values(pre_attack, "angular_speed"), 0.5),
        "pre_attack_acceleration_median": quantile(
            values(pre_attack, "angular_acceleration"), 0.5
        ),
        "pre_attack_jerk_median": quantile(values(pre_attack, "angular_jerk"), 0.5),
        "contains_overshoot": any(sample.get("has_overshoot") for sample in window),
        "humanize_alpha_median": quantile(values(window, "humanize_alpha"), 0.5),
        "stable_fraction": (
            sum(sample.get("humanize_target_stable") is True for sample in window)
            / len([sample for sample in window if sample.get("humanize_target_stable") is not None])
            if any(sample.get("humanize_target_stable") is not None for sample in window)
            else None
        ),
        "phase_counts": json.dumps(dict(phases), sort_keys=True),
    }


def validate_window_condition(
    recording: Recording, window: Sequence[dict[str, Any]]
) -> str | None:
    if recording.label == "manual":
        if any(sample.get("rotation_active") for sample in window):
            return "manual_rotation_active"
        return None

    if any(not sample.get("rotation_active") for sample in window):
        return "rotation_inactive"

    expected_humanize = recording.label == "on"
    if any(sample.get("humanize") is not expected_humanize for sample in window):
        return "wrong_humanize_state"
    return None


def build_attack_windows(
    recording: Recording,
    samples: Sequence[dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    by_tick = {sample["tick"]: sample for sample in samples}
    aligned: list[dict[str, Any]] = []
    features: list[dict[str, Any]] = []
    exclusions: list[dict[str, Any]] = []
    last_accepted_end: int | None = None

    for attack in recording.attacks:
        attack_tick = attack.get("tick")
        if not isinstance(attack_tick, int):
            exclusions.append(
                exclusion_row(recording, "attack_window", "invalid_attack_tick")
            )
            continue

        start = attack_tick - ATTACK_WINDOW_BEFORE
        end = attack_tick + ATTACK_WINDOW_AFTER
        missing = [tick for tick in range(start, end + 1) if tick not in by_tick]
        if missing:
            exclusions.append(
                exclusion_row(
                    recording,
                    "attack_window",
                    f"incomplete_or_excluded_ticks:{len(missing)}",
                    attack_tick=attack_tick,
                )
            )
            continue

        window = [by_tick[tick] for tick in range(start, end + 1)]
        condition_error = validate_window_condition(recording, window)
        if condition_error:
            exclusions.append(
                exclusion_row(
                    recording,
                    "attack_window",
                    condition_error,
                    attack_tick=attack_tick,
                )
            )
            continue

        if last_accepted_end is not None and start <= last_accepted_end:
            exclusions.append(
                exclusion_row(
                    recording,
                    "attack_window",
                    "overlaps_previous_accepted_window",
                    attack_tick=attack_tick,
                )
            )
            continue

        last_accepted_end = end
        features.append(extract_window_features(recording, attack, window))
        for sample in window:
            aligned.append(
                {
                    "label": recording.label,
                    "session": recording.path.name,
                    "attack_tick": attack_tick,
                    "attack_target_id": attack.get("target_id"),
                    "offset": sample["tick"] - attack_tick,
                    "sample_target_id": sample.get("target_id"),
                    "angular_speed": sample.get("angular_speed"),
                    "angular_acceleration": sample.get("angular_acceleration"),
                    "angular_jerk": sample.get("angular_jerk"),
                    "exact_error": sample.get("exact_error"),
                    "requested_target_angular_speed": sample.get(
                        "requested_target_angular_speed"
                    ),
                    "phase": sample.get("phase"),
                    "has_overshoot": sample.get("has_overshoot"),
                    "humanize_alpha": sample.get("humanize_alpha"),
                    "humanize_target_stable": sample.get("humanize_target_stable"),
                    "humanize_overshoot_armed": sample.get("humanize_overshoot_armed"),
                    "player_speed_amplifier": sample.get("player_speed_amplifier"),
                }
            )

    return aligned, features, exclusions


def summarize_episode(
    recording: Recording,
    movement_id: int,
    samples: Sequence[dict[str, Any]],
    outcome: str,
) -> dict[str, Any]:
    initial_error = finite(samples[0].get("exact_error"))
    gcd = max(values(samples, "gcd"), default=0.0)
    correction_samples = [sample for sample in samples if sample.get("phase") == "CORRECTION"]
    overshoot_activations = 0
    was_active = False
    for sample in samples:
        active = bool(sample.get("has_overshoot"))
        if active and not was_active:
            overshoot_activations += 1
        was_active = active

    waypoint_errors = [
        finite(sample.get("exact_error"))
        for sample in samples
        if sample.get("has_overshoot") and sample.get("phase") == "CORRECTION"
    ]
    waypoint_errors = [value for value in waypoint_errors if value is not None]

    return {
        "label": recording.label,
        "session": recording.path.name,
        "movement_id": movement_id,
        "start_tick": samples[0]["tick"],
        "end_tick": samples[-1]["tick"],
        "duration_ticks": samples[-1]["tick"] - samples[0]["tick"] + 1,
        "initial_exact_error": initial_error,
        "overshoot_eligible": initial_error is not None and initial_error >= 8.0,
        "overshoot_armed_seen": any(
            sample.get("humanize_overshoot_armed") is True for sample in samples
        ),
        "overshoot_activations": overshoot_activations,
        "correction_ticks": len(correction_samples),
        "correction_seen": bool(correction_samples),
        "correction_completed": bool(correction_samples) and outcome == "completed",
        "outcome": outcome,
        "overshoot_magnitude": max(waypoint_errors) if waypoint_errors else None,
        "final_exact_error": finite(samples[-1].get("exact_error")),
        "final_target_stable": samples[-1].get("humanize_target_stable"),
        "maximum_alpha": max(values(samples, "humanize_alpha"), default=None),
        "speed_effect_context": speed_effect_context(samples),
        "gcd": gcd,
    }


def build_humanize_episodes(
    recording: Recording,
    samples: Sequence[dict[str, Any]],
) -> list[dict[str, Any]]:
    episodes: list[dict[str, Any]] = []
    current: list[dict[str, Any]] = []
    movement_id = 0
    previous: dict[str, Any] | None = None

    def close(outcome: str) -> None:
        nonlocal current
        if current:
            episodes.append(summarize_episode(recording, movement_id, current, outcome))
            current = []

    for sample in samples:
        phase = sample.get("phase")
        active = sample.get("humanize") is True and isinstance(phase, str)
        consecutive = previous is not None and sample["tick"] - previous["tick"] == 1

        if not active:
            close("humanize_inactive")
            previous = sample
            continue

        if current and not consecutive:
            close("update_gap")

        previous_phase = previous.get("phase") if previous and consecutive else None
        if current and phase == "PRIMARY" and previous_phase != "PRIMARY":
            close("reacquired")

        if not current:
            movement_id += 1
        current.append(sample)

        if phase == "IDLE":
            correction_seen = any(row.get("phase") == "CORRECTION" for row in current)
            close("completed" if correction_seen else "settled_without_overshoot")
        elif previous_phase == "CORRECTION" and phase == "TRACKING":
            close("canceled_target_motion")

        previous = sample

    close("end_of_recording")
    return episodes


def balance_automated_windows(
    features: Sequence[dict[str, Any]], seed: int
) -> tuple[dict[str, list[dict[str, Any]]], dict[str, tuple[int, int, int]], bool]:
    grouped: dict[tuple[str, str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in features:
        if row.get("label") not in ("off", "on"):
            continue
        key = (
            row["label"],
            str(row.get("target_motion_bin")),
            str(row.get("speed_effect_context")),
        )
        grouped[key].append(row)

    rng = random.Random(seed)
    result = {"off": [], "on": []}
    counts: dict[str, tuple[int, int, int]] = {}
    contexts = sorted({(key[1], key[2]) for key in grouped})

    for motion, effect in contexts:
        off = grouped.get(("off", motion, effect), [])
        on = grouped.get(("on", motion, effect), [])
        available = min(len(off), len(on))
        selected = (
            available
            if motion != "unknown" and available >= MIN_WINDOWS_PER_MATCHED_BIN
            else 0
        )
        counts[f"{motion}/{effect}"] = (len(off), len(on), selected)
        if selected == 0:
            continue
        result["off"].extend(rng.sample(off, selected))
        result["on"].extend(rng.sample(on, selected))

    matched = bool(result["off"] and result["on"])
    if not matched:
        result = {
            "off": [row for row in features if row.get("label") == "off"],
            "on": [row for row in features if row.get("label") == "on"],
        }
    return result, counts, matched


def balance_tracking_samples(
    samples: Sequence[dict[str, Any]], seed: int
) -> dict[str, list[dict[str, Any]]]:
    grouped: dict[tuple[str, str, str], list[dict[str, Any]]] = defaultdict(list)
    for sample in samples:
        label = sample.get("label")
        if label == "off":
            eligible = sample.get("rotation_active") is True
        elif label == "on":
            eligible = sample.get("rotation_active") is True and sample.get("phase") == "TRACKING"
        else:
            continue
        speed = finite(sample.get("requested_target_angular_speed"))
        current_bin = motion_bin(speed)
        if not eligible or current_bin == "unknown":
            continue
        grouped[(label, current_bin, speed_effect_context([sample]))].append(sample)

    rng = random.Random(seed)
    result = {"off": [], "on": []}
    contexts = sorted({(key[1], key[2]) for key in grouped})
    for current_bin, effect in contexts:
        off = grouped.get(("off", current_bin, effect), [])
        on = grouped.get(("on", current_bin, effect), [])
        selected = min(len(off), len(on))
        if selected < MIN_TRACKING_SAMPLES_PER_MATCHED_BIN:
            continue
        result["off"].extend(rng.sample(off, selected))
        result["on"].extend(rng.sample(on, selected))
    return result


def resample_rows(rows: Sequence[dict[str, Any]], rng: random.Random) -> list[dict[str, Any]]:
    if not rows:
        return []
    by_session: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        by_session[str(row.get("session"))].append(row)

    sessions = list(by_session)
    if len(sessions) >= 3:
        result = []
        for session in rng.choices(sessions, k=len(sessions)):
            session_rows = by_session[session]
            result.extend(rng.choices(session_rows, k=len(session_rows)))
        return result
    return rng.choices(list(rows), k=len(rows))


def percentile_interval(items: Sequence[float]) -> tuple[float, float] | None:
    if not items:
        return None
    lower = quantile(items, 0.025)
    upper = quantile(items, 0.975)
    return (lower, upper) if lower is not None and upper is not None else None


def bootstrap_statistic(
    rows: Sequence[dict[str, Any]],
    statistic: Callable[[Sequence[dict[str, Any]]], float | None],
    samples: int,
    seed: int,
) -> tuple[float, float] | None:
    if samples <= 0 or len(rows) < 2:
        return None
    rng = random.Random(seed)
    estimates = []
    for _ in range(samples):
        value = statistic(resample_rows(rows, rng))
        if value is not None and math.isfinite(value):
            estimates.append(value)
    return percentile_interval(estimates)


def bootstrap_ks(
    left: Sequence[dict[str, Any]],
    right: Sequence[dict[str, Any]],
    feature: str,
    samples: int,
    seed: int,
) -> tuple[float, float] | None:
    if samples <= 0 or len(left) < 2 or len(right) < 2:
        return None
    rng = random.Random(seed)
    estimates = []
    for _ in range(samples):
        estimate = ks_statistic(
            values(resample_rows(left, rng), feature),
            values(resample_rows(right, rng), feature),
        )
        if estimate is not None:
            estimates.append(estimate)
    return percentile_interval(estimates)


def bootstrap_ks_difference(
    manual: Sequence[dict[str, Any]],
    off: Sequence[dict[str, Any]],
    on: Sequence[dict[str, Any]],
    feature: str,
    samples: int,
    seed: int,
) -> tuple[float, float] | None:
    if samples <= 0 or min(len(manual), len(off), len(on)) < 2:
        return None
    rng = random.Random(seed)
    estimates = []
    for _ in range(samples):
        manual_values = values(resample_rows(manual, rng), feature)
        off_ks = ks_statistic(manual_values, values(resample_rows(off, rng), feature))
        on_ks = ks_statistic(manual_values, values(resample_rows(on, rng), feature))
        if off_ks is not None and on_ks is not None:
            estimates.append(on_ks - off_ks)
    return percentile_interval(estimates)


def format_number(value: float | None, digits: int = 3) -> str:
    return "n/a" if value is None else f"{value:.{digits}f}"


def format_interval(interval: tuple[float, float] | None) -> str:
    if interval is None:
        return "n/a"
    return f"[{interval[0]:.3f}, {interval[1]:.3f}]"


def distribution_summary(
    rows: Sequence[dict[str, Any]],
    feature: str,
    bootstrap_samples: int,
    seed: int,
) -> str:
    data = values(rows, feature)
    if not data:
        return "n/a"
    interval = bootstrap_statistic(
        rows,
        lambda sampled: quantile(values(sampled, feature), 0.5),
        bootstrap_samples,
        seed,
    )
    return (
        f"{format_number(quantile(data, 0.5))} "
        f"[{format_number(quantile(data, 0.25))}, {format_number(quantile(data, 0.75))}]; "
        f"CI {format_interval(interval)}"
    )


def write_csv(path: Path, rows: Sequence[dict[str, Any]]) -> None:
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    fieldnames = []
    seen = set()
    for row in rows:
        for key in row:
            if key not in seen:
                seen.add(key)
                fieldnames.append(key)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def make_plots(output: Path, aligned: Sequence[dict[str, Any]]) -> list[str]:
    try:
        import matplotlib.pyplot as plt  # type: ignore
    except ImportError:
        return []

    plot_dir = output / "plots"
    plot_dir.mkdir(exist_ok=True)
    stale_reference_plot = plot_dir / "reference_error.png"
    if stale_reference_plot.exists():
        stale_reference_plot.unlink()
    generated = []

    for feature, title, y_label, labels in (
        (
            "angular_speed",
            "Attack-aligned angular speed",
            "degrees/tick",
            ("manual", "off", "on"),
        ),
        (
            "exact_error",
            "Attack-aligned requested-target error",
            "degrees",
            ("off", "on"),
        ),
    ):
        figure, axis = plt.subplots(figsize=(9, 4.5))
        drew_data = False
        for label in labels:
            grouped: dict[int, list[float]] = defaultdict(list)
            for row in aligned:
                if row.get("label") != label:
                    continue
                value = finite(row.get(feature))
                if value is not None:
                    grouped[int(row["offset"])].append(value)
            if not grouped:
                continue
            offsets = sorted(grouped)
            medians = [statistics.median(grouped[offset]) for offset in offsets]
            axis.plot(offsets, medians, marker="o", markersize=3, label=label)
            drew_data = True
        if not drew_data:
            plt.close(figure)
            continue
        axis.axvline(0, color="black", linewidth=1, linestyle="--", label="attack")
        axis.set_title(title)
        axis.set_xlabel("ticks relative to attack")
        axis.set_ylabel(y_label)
        axis.grid(alpha=0.25)
        axis.legend()
        figure.tight_layout()
        filename = f"{feature}.png"
        figure.savefig(plot_dir / filename, dpi=150)
        plt.close(figure)
        generated.append(f"plots/{filename}")

    return generated


def report_exact_error(
    lines: list[str], samples: Sequence[dict[str, Any]], features: Sequence[dict[str, Any]]
) -> None:
    lines.extend(
        [
            "",
            "## Exact requested-target diagnostics",
            "",
            "Exact requested error is a controller-safety measure for automated modes; it is not compared to manual intent.",
            "",
            "| Condition | Active ticks | Active median | Active p90 | Attack median | Attack p90 | Pre-attack median | Pre-attack p90 |",
            "|---|---:|---:|---:|---:|---:|---:|---:|",
        ]
    )
    for label in ("off", "on"):
        active_samples = [
            row
            for row in samples
            if row.get("label") == label and row.get("rotation_active")
        ]
        label_features = [row for row in features if row.get("label") == label]
        active_errors = values(active_samples, "exact_error")
        attack_errors = values(label_features, "attack_exact_error")
        pre_medians = values(label_features, "pre_attack_exact_error_median")
        pre_p90s = values(label_features, "pre_attack_exact_error_p90")
        lines.append(
            f"| {label} | {len(active_errors)} | {format_number(quantile(active_errors, 0.5), 4)} | "
            f"{format_number(quantile(active_errors, 0.9), 4)} | "
            f"{format_number(quantile(attack_errors, 0.5), 4)} | "
            f"{format_number(quantile(attack_errors, 0.9), 4)} | "
            f"{format_number(quantile(pre_medians, 0.5), 4)} | "
            f"{format_number(quantile(pre_p90s, 0.5), 4)} |"
        )

    lines.extend(
        [
            "",
            "### Humanize-on error by phase",
            "",
            "| Phase | Samples | Median | p90 |",
            "|---|---:|---:|---:|",
        ]
    )
    on_samples = [row for row in samples if row.get("label") == "on"]
    for phase in ("IDLE", "PRIMARY", "CORRECTION", "TRACKING"):
        phase_errors = values(
            [row for row in on_samples if row.get("phase") == phase], "exact_error"
        )
        lines.append(
            f"| {phase} | {len(phase_errors)} | {format_number(quantile(phase_errors, 0.5), 4)} | "
            f"{format_number(quantile(phase_errors, 0.9), 4)} |"
        )


def report_tracking_bins(lines: list[str], samples: Sequence[dict[str, Any]]) -> None:
    lines.extend(
        [
            "",
            "## Tracking by requested-target angular speed",
            "",
            "| Condition | Motion bin | Samples | Speed median | Exact-error median | Exact-error p90 |",
            "|---|---|---:|---:|---:|---:|",
        ]
    )
    for label in ("off", "on"):
        tracking = [
            row
            for row in samples
            if row.get("label") == label
            and row.get("rotation_active")
            and (label == "off" or row.get("phase") == "TRACKING")
        ]
        for name, _, _ in MOTION_BINS:
            rows = [
                row
                for row in tracking
                if motion_bin(finite(row.get("requested_target_angular_speed"))) == name
            ]
            speeds = values(rows, "angular_speed")
            errors = values(rows, "exact_error")
            lines.append(
                f"| {label} | {name} | {len(rows)} | {format_number(quantile(speeds, 0.5))} | "
                f"{format_number(quantile(errors, 0.5), 4)} | {format_number(quantile(errors, 0.9), 4)} |"
            )


def report_episodes(lines: list[str], episodes: Sequence[dict[str, Any]]) -> None:
    lines.extend(["", "## Humanize episode audit", ""])
    if not episodes:
        lines.append("No Humanize-on phase episodes were available.")
        return

    outcomes = Counter(str(row.get("outcome")) for row in episodes)
    activated = [row for row in episodes if (row.get("overshoot_activations") or 0) > 0]
    completed = [row for row in activated if row.get("correction_completed")]
    correction_ticks = values(completed, "correction_ticks")
    magnitudes = values(activated, "overshoot_magnitude")
    multiple = sum((row.get("overshoot_activations") or 0) > 1 for row in episodes)

    lines.append(f"- Movement episodes: {len(episodes)}; outcomes: `{dict(outcomes)}`.")
    lines.append(
        f"- Activated overshoots: {len(activated)}; completed corrections: {len(completed)}; "
        f"completion rate: {format_number(len(completed) / len(activated) if activated else None)}."
    )
    lines.append(
        f"- Completed correction duration median/p95: "
        f"{format_number(quantile(correction_ticks, 0.5))}/"
        f"{format_number(quantile(correction_ticks, 0.95))} ticks."
    )
    lines.append(
        f"- Recorded waypoint magnitude median/max: "
        f"{format_number(quantile(magnitudes, 0.5))}/"
        f"{format_number(max(magnitudes) if magnitudes else None)} degrees."
    )
    lines.append(f"- Movements with more than one overshoot activation: {multiple}.")
    if len(activated) < MIN_OVERSHOOT_EPISODES:
        lines.append(
            f"- Episode rates are exploratory: {len(activated)} activated episodes is below the "
            f"{MIN_OVERSHOOT_EPISODES}-episode decision threshold."
        )


def write_report(
    output: Path,
    recordings: Sequence[Recording],
    samples: Sequence[dict[str, Any]],
    features: Sequence[dict[str, Any]],
    exclusions: Sequence[dict[str, Any]],
    episodes: Sequence[dict[str, Any]],
    plots: Sequence[str],
    bootstrap_samples: int,
    seed: int,
) -> None:
    manual = [row for row in features if row.get("label") == "manual"]
    balanced, balance_counts, matched = balance_automated_windows(features, seed)
    grouped = {"manual": manual, "off": balanced["off"], "on": balanced["on"]}

    lines = [
        "# Aim comparison report",
        "",
        f"Analyzer version: `{ANALYZER_VERSION}`. Seed: `{seed}`. Bootstrap replicates: `{bootstrap_samples}`. "
        "Primary values are median [Q1, Q3] with a 95% bootstrap interval for the median. "
        "KS is empirical distribution distance to manual; lower is closer.",
        "",
        "Inputs: " + "; ".join(
            f"{label}=" + ", ".join(f"`{row.path}`" for row in recordings if row.label == label)
            for label in ("manual", "off", "on")
        ),
        "",
        "## Dataset and window validation",
        "",
        "| Label | Files | Schemas | Derived ticks | Attacks | Accepted non-overlap windows | Rejected windows | Excluded ticks |",
        "|---|---:|---|---:|---:|---:|---:|---:|",
    ]

    for label in ("manual", "off", "on"):
        label_recordings = [row for row in recordings if row.label == label]
        label_exclusions = [row for row in exclusions if row.get("label") == label]
        lines.append(
            f"| {label} | {len(label_recordings)} | "
            f"{sorted({row.metadata.get('schema') for row in label_recordings}, key=str)} | "
            f"{sum(row.get('label') == label for row in samples)} | "
            f"{sum(len(row.attacks) for row in label_recordings)} | "
            f"{sum(row.get('label') == label for row in features)} | "
            f"{sum(row.get('row_type') == 'attack_window' for row in label_exclusions)} | "
            f"{sum(row.get('row_type') == 'tick' for row in label_exclusions)} |"
        )

    lines.extend(["", "### Motion/speed-effect balance", ""])
    lines.append(
        "Automated windows were balanced within shared requested-target-motion and player-speed-effect contexts."
        if matched
        else "No context had at least 15 windows in both automated conditions; primary automated comparisons fall back to all valid windows and are exploratory."
    )
    lines.extend(
        [
            "",
            "| Context (motion/effect) | Off | On | Selected each |",
            "|---|---:|---:|---:|",
        ]
    )
    for context, (off_count, on_count, selected) in balance_counts.items():
        lines.append(f"| {context} | {off_count} | {on_count} | {selected} |")

    lines.extend(
        [
            "",
            "## Primary attack-window comparison",
            "",
            f"Comparison counts: manual={len(grouped['manual'])}, off={len(grouped['off'])}, on={len(grouped['on'])}.",
            "",
            "| Feature | Manual | Humanize off | Humanize on | KS off (95% CI) | KS on (95% CI) | Evidence |",
            "|---|---|---|---|---|---|---|",
        ]
    )

    primary_results: dict[str, tuple[float | None, float | None]] = {}
    for index, feature in enumerate(PRIMARY_FEATURES):
        manual_values = values(grouped["manual"], feature)
        off_values = values(grouped["off"], feature)
        on_values = values(grouped["on"], feature)
        ks_off = ks_statistic(manual_values, off_values)
        ks_on = ks_statistic(manual_values, on_values)
        primary_results[feature] = (ks_off, ks_on)
        off_ci = bootstrap_ks(
            grouped["manual"], grouped["off"], feature, bootstrap_samples, seed + 100 + index
        )
        on_ci = bootstrap_ks(
            grouped["manual"], grouped["on"], feature, bootstrap_samples, seed + 200 + index
        )
        difference_ci = bootstrap_ks_difference(
            grouped["manual"],
            grouped["off"],
            grouped["on"],
            feature,
            bootstrap_samples,
            seed + 300 + index,
        )
        evidence = "inconclusive"
        if difference_ci is not None:
            if difference_ci[1] < 0:
                evidence = "on closer"
            elif difference_ci[0] > 0:
                evidence = "off closer"

        lines.append(
            f"| {feature} | "
            f"{distribution_summary(grouped['manual'], feature, bootstrap_samples, seed + index)} | "
            f"{distribution_summary(grouped['off'], feature, bootstrap_samples, seed + 10 + index)} | "
            f"{distribution_summary(grouped['on'], feature, bootstrap_samples, seed + 20 + index)} | "
            f"{format_number(ks_off)} {format_interval(off_ci)} | "
            f"{format_number(ks_on)} {format_interval(on_ci)} | {evidence} |"
        )

    report_exact_error(lines, samples, features)
    report_tracking_bins(lines, samples)
    report_episodes(lines, episodes)

    lines.extend(["", "## Automated acceptance snapshot", ""])
    for label in ("manual", "off", "on"):
        count = sum(row.get("label") == label for row in features)
        status = "PASS" if count >= MIN_WINDOWS_PER_CONDITION else "INSUFFICIENT DATA"
        lines.append(
            f"- `{label}` complete non-overlapping windows: {count}/{MIN_WINDOWS_PER_CONDITION} — {status}."
        )

    core = ("peak_speed", "rms_acceleration", "rms_jerk")
    comparable = [
        feature
        for feature in core
        if primary_results.get(feature, (None, None))[0] is not None
        and primary_results.get(feature, (None, None))[1] is not None
    ]
    not_worse = all(
        primary_results[feature][1] <= primary_results[feature][0] + 0.05
        for feature in comparable
    ) if comparable else False
    improved = sum(
        primary_results[feature][1] < primary_results[feature][0]
        for feature in comparable
    )
    lines.append(
        f"- Peak/acceleration/jerk KS gate: not-worse={not_worse}; improved={improved}/3 "
        "(requires at least two improvements and no regression over 0.05)."
    )
    reversal_off, reversal_on = primary_results.get("reversal_count", (None, None))
    reversal_advantage = (
        reversal_off - reversal_on
        if reversal_off is not None and reversal_on is not None
        else None
    )
    lines.append(
        f"- Reversal KS advantage (off minus on): {format_number(reversal_advantage)}; target >= 0.100."
    )

    matched_tracking = balance_tracking_samples(samples, seed + 400)
    off_tracking_speed = quantile(values(matched_tracking["off"], "angular_speed"), 0.5)
    on_tracking_speed = quantile(values(matched_tracking["on"], "angular_speed"), 0.5)
    tracking_ratio = (
        on_tracking_speed / off_tracking_speed
        if off_tracking_speed is not None
        and off_tracking_speed > 0
        and on_tracking_speed is not None
        else None
    )
    lines.append(
        f"- Motion-matched tracking-speed ratio (on/off): {format_number(tracking_ratio)} "
        f"from {len(matched_tracking['off'])} samples per condition; target >= 0.800."
    )

    manual_pre_jerk = quantile(values(grouped["manual"], "pre_attack_jerk_median"), 0.5)
    on_pre_jerk = quantile(values(grouped["on"], "pre_attack_jerk_median"), 0.5)
    current_pre_gap = (
        abs(manual_pre_jerk - on_pre_jerk)
        if manual_pre_jerk is not None and on_pre_jerk is not None
        else None
    )
    pre_gap_shrink = (
        1.0 - current_pre_gap / abs(2.5321 - 0.9766)
        if current_pre_gap is not None
        else None
    )
    lines.append(
        f"- Pre-attack jerk median-gap shrink from the recorded baseline: "
        f"{format_number(pre_gap_shrink)}; target >= 0.250."
    )

    on_attack_errors = values(
        [row for row in features if row.get("label") == "on"], "attack_exact_error"
    )
    common_gcd = max(
        (
            finite(recording.metadata.get("gcd")) or 0.0
            for recording in recordings
            if recording.label == "on"
        ),
        default=0.0,
    )
    endpoint_limit = 2.0 + 2.0 * common_gcd
    attack_error_p90 = quantile(on_attack_errors, 0.9)
    lines.append(
        f"- Humanize-on attack requested-error p90: {format_number(attack_error_p90, 4)}; "
        f"default limit {endpoint_limit:.4f}."
    )

    activated = [row for row in episodes if (row.get("overshoot_activations") or 0) > 0]
    completed = [row for row in activated if row.get("correction_completed")]
    completion_rate = len(completed) / len(activated) if activated else None
    correction_p95 = quantile(values(completed, "correction_ticks"), 0.95)
    maximum_overshoot = max(values(activated, "overshoot_magnitude"), default=None)
    lines.append(
        f"- Overshoot episodes: {len(activated)}/{MIN_OVERSHOOT_EPISODES}; "
        f"completion={format_number(completion_rate)}, correction p95={format_number(correction_p95)} ticks, "
        f"maximum magnitude={format_number(maximum_overshoot)} degrees."
    )

    lines.extend(["", "## Recording checks", ""])
    sensitivities: dict[str, set[float]] = defaultdict(set)
    gcds: dict[str, set[float]] = defaultdict(set)
    for recording in recordings:
        sensitivity = finite(recording.metadata.get("mouse_sensitivity"))
        gcd = finite(recording.metadata.get("gcd"))
        if sensitivity is not None:
            sensitivities[recording.label].add(round(sensitivity, 8))
        if gcd is not None:
            gcds[recording.label].add(round(gcd, 8))
        for warning in recording.warnings:
            lines.append(f"- `{recording.path.name}`: {warning}")

    all_sensitivities = set().union(*sensitivities.values()) if sensitivities else set()
    all_gcds = set().union(*gcds.values()) if gcds else set()
    if len(all_sensitivities) > 1:
        lines.append(f"- Warning: mouse sensitivity differs: `{dict(sensitivities)}`.")
    if len(all_gcds) > 1:
        lines.append(f"- Warning: rotation GCD differs: `{dict(gcds)}`.")
    if (
        not any(recording.warnings for recording in recordings)
        and len(all_sensitivities) <= 1
        and len(all_gcds) <= 1
    ):
        lines.append("- No schema, sensitivity, or GCD mismatch was found.")

    speed_ticks = Counter(
        (sample["label"], sample.get("player_speed_amplifier", -1))
        for sample in samples
    )
    speed_windows = Counter(
        (row["label"], row.get("speed_effect_context")) for row in features
    )
    lines.append(f"- Player speed-amplifier tick counts: `{dict(speed_ticks)}`.")
    lines.append(f"- Attack-window speed contexts: `{dict(speed_windows)}`.")

    rejection_counts = Counter(
        (row.get("label"), row.get("reason"))
        for row in exclusions
        if row.get("row_type") == "attack_window"
    )
    lines.append(f"- Rejected-window reasons: `{dict(rejection_counts)}`.")

    lines.extend(
        [
            "",
            "## Limitations",
            "",
            "- Manual recordings have no module-requested endpoint. Bounding-box-center reference data is retained only as context and is not a primary similarity or endpoint metric.",
            "- Automated motion matching uses requested-target speed. Manual target intent cannot be matched exactly; manual windows remain an unmatched baseline.",
            "- With fewer than three files per condition, bootstrap intervals resample windows and cannot estimate session-to-session variation.",
            "- Offline distribution similarity is necessary diagnostic evidence, not proof that an external classifier will accept the behavior.",
        ]
    )

    if plots:
        lines.extend(["", "## Plots", ""])
        lines.extend(f"- [{Path(plot).name}]({plot})" for plot in plots)
    else:
        lines.extend(
            [
                "",
                "Plot generation was skipped because Matplotlib is unavailable or no plottable data exists.",
            ]
        )

    (output / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def run_analysis(args: argparse.Namespace) -> int:
    inputs = {
        "manual": args.manual or [],
        "off": args.off or [],
        "on": args.on or [],
    }
    missing = [label for label, paths in inputs.items() if not paths]
    if missing:
        raise ValueError(f"missing recording group(s): {', '.join(missing)}")

    recordings = [
        load_recording(Path(path), label)
        for label, paths in inputs.items()
        for path in paths
    ]
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)

    all_samples: list[dict[str, Any]] = []
    all_exclusions: list[dict[str, Any]] = []
    all_aligned: list[dict[str, Any]] = []
    all_features: list[dict[str, Any]] = []
    all_episodes: list[dict[str, Any]] = []

    for recording in recordings:
        samples, exclusions = derive_samples(recording)
        aligned, features, window_exclusions = build_attack_windows(recording, samples)
        all_samples.extend(samples)
        all_exclusions.extend(exclusions)
        all_exclusions.extend(window_exclusions)
        all_aligned.extend(aligned)
        all_features.extend(features)
        if recording.label == "on":
            all_episodes.extend(build_humanize_episodes(recording, samples))

    write_csv(output / "movement_features.csv", all_features)
    write_csv(output / "attack_aligned.csv", all_aligned)
    write_csv(output / "excluded_samples.csv", all_exclusions)
    write_csv(output / "humanize_episodes.csv", all_episodes)
    plots = [] if args.no_plots else make_plots(output, all_aligned)
    write_report(
        output,
        recordings,
        all_samples,
        all_features,
        all_exclusions,
        all_episodes,
        plots,
        args.bootstrap_samples,
        args.seed,
    )
    print(f"Wrote aim comparison report to {output / 'report.md'}")
    return 0


def synthetic_recording(label: str, schema: int = 2) -> Recording:
    ticks = {}
    humanize = label == "on"
    for tick in range(90):
        yaw = min(20.0, -20.0 + tick * 0.8)
        requested = 20.0 + max(0, tick - 50) * 0.2
        record = {
            "type": "tick",
            "tick": tick,
            "server_yaw": yaw,
            "server_pitch": math.sin(tick / 10.0),
            "requested_yaw": requested if label != "manual" else None,
            "requested_pitch": 0.0 if label != "manual" else None,
            "rotation_active": label != "manual",
            "humanize": humanize if label != "manual" else None,
            "phase": "TRACKING" if humanize else None,
            "has_overshoot": False,
            "humanize_alpha": 0.6 if humanize and schema == 2 else None,
            "humanize_target_stable": False if humanize and schema == 2 else None,
            "humanize_overshoot_armed": False if humanize and schema == 2 else None,
            "player_motion_x": 0.0,
            "player_motion_z": 0.0,
            "player_speed_amplifier": -1,
            "source": "manual" if label == "manual" else "killaura",
            "targets": [
                {
                    "id": 1,
                    "reference_yaw": 20.0,
                    "reference_pitch": 0.0,
                    "angular_distance": abs(20.0 - yaw),
                    "motion_x": 0.0,
                    "motion_z": 0.0,
                    "speed_amplifier": -1,
                }
            ],
        }
        ticks[tick] = record

    return Recording(
        label,
        Path(f"synthetic-{label}.jsonl"),
        {"schema": schema, "gcd": 0.1, "mouse_sensitivity": 0.5},
        ticks,
        [
            {"type": "attack", "tick": 30, "target_id": 1},
            {"type": "attack", "tick": 40, "target_id": 1},
            {"type": "attack", "tick": 70, "target_id": 1},
        ],
        [],
    )


def self_test() -> int:
    assert wrap_angle(181.0) == -179.0
    assert wrap_angle(-181.0) == 179.0
    assert angle_delta(-179.0, 179.0) == 2.0
    assert ks_statistic([0.0, 1.0], [0.0, 1.0]) == 0.0
    assert ks_statistic([0.0, 0.0], [1.0, 1.0]) == 1.0
    assert quantile([0.0, 10.0], 0.5) == 5.0
    assert motion_bin(0.5) == "<1"
    assert motion_bin(1.0) == "1-<3"
    assert motion_bin(6.0) == ">=6"

    recordings = [
        synthetic_recording("manual", schema=1),
        synthetic_recording("off", schema=1),
        synthetic_recording("on", schema=2),
    ]
    samples = []
    features = []
    aligned = []
    exclusions = []
    episodes = []
    for recording in recordings:
        derived, tick_exclusions = derive_samples(recording)
        rows, window_features, window_exclusions = build_attack_windows(
            recording, derived
        )
        samples.extend(derived)
        features.extend(window_features)
        aligned.extend(rows)
        exclusions.extend(tick_exclusions + window_exclusions)
        if recording.label == "on":
            episodes.extend(build_humanize_episodes(recording, derived))

    assert len([row for row in features if row["label"] == "manual"]) == 2
    assert any(row.get("reason") == "overlaps_previous_accepted_window" for row in exclusions)
    assert all(row["duration_ticks"] == 26 for row in features)
    assert aligned

    with tempfile.TemporaryDirectory() as temp_directory:
        output = Path(temp_directory)
        write_csv(output / "features.csv", features)
        write_report(
            output,
            recordings,
            samples,
            features,
            exclusions,
            episodes,
            [],
            10,
            DEFAULT_SEED,
        )
        report = output / "report.md"
        assert report.is_file()
        assert "Primary attack-window comparison" in report.read_text(encoding="utf-8")

    print("aim_compare self-test passed")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manual", nargs="+", help="manual Analysis-mode JSONL recording(s)")
    parser.add_argument("--off", nargs="+", help="Humanize-off Analysis-mode JSONL recording(s)")
    parser.add_argument("--on", nargs="+", help="Humanize-on Analysis-mode JSONL recording(s)")
    parser.add_argument("--output", default="aim-analysis", help="output directory")
    parser.add_argument("--no-plots", action="store_true", help="skip optional Matplotlib plots")
    parser.add_argument(
        "--bootstrap-samples",
        type=int,
        default=DEFAULT_BOOTSTRAP_SAMPLES,
        help="bootstrap replicates for intervals (0 disables)",
    )
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED, help="deterministic sampling seed")
    parser.add_argument("--self-test", action="store_true", help="run dependency-free internal tests")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.bootstrap_samples < 0:
        parser.error("--bootstrap-samples must be non-negative")
    if args.self_test:
        return self_test()
    try:
        return run_analysis(args)
    except (OSError, ValueError) as exception:
        parser.error(str(exception))
    return 2


if __name__ == "__main__":
    sys.exit(main())
