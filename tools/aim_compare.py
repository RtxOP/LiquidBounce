#!/usr/bin/env python3
"""Compare manual, non-humanized, and humanized aim recordings.

The client recorder writes JSON Lines so tick and attack events do not depend on
their in-client ordering. This tool intentionally uses descriptive statistics;
it does not train a surrogate anti-cheat model.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
import sys
import tempfile
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Sequence


ATTACK_WINDOW_BEFORE = 20
ATTACK_WINDOW_AFTER = 5
MAX_ACQUISITION_TICKS = 80
REPORT_FEATURES = (
    "duration_ticks",
    "peak_speed",
    "peak_location",
    "rms_acceleration",
    "rms_jerk",
    "reversal_count",
    "overshoot_crossings",
    "time_on_target_fraction",
    "mean_reference_error",
    "mean_target_speed",
    "yaw_pitch_correlation",
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
                warnings.append(f"line {line_number}: invalid JSON ({exception.msg})")
                continue

            record_type = record.get("type")
            if record_type == "session":
                if metadata:
                    warnings.append(f"line {line_number}: duplicate session record")
                else:
                    metadata = record
            elif record_type == "tick":
                tick = record.get("tick")
                if not isinstance(tick, int):
                    warnings.append(f"line {line_number}: tick record has no integer tick")
                elif tick in ticks:
                    warnings.append(f"line {line_number}: duplicate tick {tick}; using the last record")
                    ticks[tick] = record
                else:
                    ticks[tick] = record
            elif record_type == "attack":
                attacks.append(record)

    if not metadata:
        warnings.append("missing session metadata")
    elif metadata.get("schema") != 1:
        warnings.append(f"unsupported schema {metadata.get('schema')!r}; expected 1")
    if not ticks:
        warnings.append("contains no tick records")

    attacks.sort(key=lambda item: item.get("tick", -1))
    return Recording(label, path, metadata, ticks, attacks, warnings)


def attack_target_windows(recording: Recording) -> dict[int, int]:
    result: dict[int, tuple[int, int]] = {}
    available_ticks = recording.ticks

    for attack in recording.attacks:
        attack_tick = attack.get("tick")
        target_id = attack.get("target_id")
        if not isinstance(attack_tick, int) or not isinstance(target_id, int):
            continue
        for tick in range(attack_tick - ATTACK_WINDOW_BEFORE, attack_tick + ATTACK_WINDOW_AFTER + 1):
            if tick not in available_ticks:
                continue
            distance = abs(tick - attack_tick)
            previous = result.get(tick)
            if previous is None or distance < previous[0]:
                result[tick] = (distance, target_id)

    return {tick: value[1] for tick, value in result.items()}


def targets_by_id(tick: dict[str, Any]) -> dict[int, dict[str, Any]]:
    return {
        target["id"]: target
        for target in tick.get("targets", [])
        if isinstance(target, dict) and isinstance(target.get("id"), int)
    }


def choose_reference_target(
    tick: dict[str, Any], attack_target_id: int | None
) -> tuple[int | None, dict[str, Any] | None]:
    targets = targets_by_id(tick)
    preferred_ids = (attack_target_id, tick.get("kill_aura_target_id"))

    for target_id in preferred_ids:
        if isinstance(target_id, int) and target_id in targets:
            return target_id, targets[target_id]

    if not targets:
        return None, None

    target = min(
        targets.values(),
        key=lambda item: finite(item.get("angular_distance")) or float("inf"),
    )
    return target["id"], target


def derive_samples(recording: Recording) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    samples: list[dict[str, Any]] = []
    exclusions: list[dict[str, Any]] = []
    attack_targets = attack_target_windows(recording)
    gcd = finite(recording.metadata.get("gcd")) or 0.0
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
                {
                    "label": recording.label,
                    "session": recording.path.name,
                    "tick": tick_number,
                    "reason": hold_reason,
                }
            )
            previous = None
            continue

        server_yaw = finite(tick.get("server_yaw"))
        server_pitch = finite(tick.get("server_pitch"))
        if server_yaw is None or server_pitch is None:
            exclusions.append(
                {
                    "label": recording.label,
                    "session": recording.path.name,
                    "tick": tick_number,
                    "reason": "missing_server_rotation",
                }
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
            "tick": tick_number,
            "gcd": gcd,
            "source": tick.get("source"),
            "rotation_active": bool(tick.get("rotation_active")),
            "humanize": tick.get("humanize"),
            "phase": tick.get("phase"),
            "has_overshoot": bool(tick.get("has_overshoot")),
            "server_yaw": server_yaw,
            "server_pitch": server_pitch,
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

            if target_id is not None and target_id == previous.get("target_id"):
                if reference_yaw is not None and previous.get("reference_yaw") is not None:
                    target_yaw_velocity = angle_delta(reference_yaw, previous["reference_yaw"])
                    target_pitch_velocity = reference_pitch - previous["reference_pitch"]
                    sample["target_yaw_velocity"] = target_yaw_velocity
                    sample["target_pitch_velocity"] = target_pitch_velocity
                    sample["target_angular_speed"] = math.hypot(
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


def split_target_runs(samples: Sequence[dict[str, Any]]) -> list[list[dict[str, Any]]]:
    runs: list[list[dict[str, Any]]] = []
    current: list[dict[str, Any]] = []

    for sample in samples:
        if sample.get("reference_error") is None or sample.get("target_id") is None:
            if current:
                runs.append(current)
                current = []
            continue

        if current and (
            sample["tick"] - current[-1]["tick"] != 1
            or sample["target_id"] != current[-1]["target_id"]
        ):
            runs.append(current)
            current = []
        current.append(sample)

    if current:
        runs.append(current)
    return runs


def movement_segments(samples: Sequence[dict[str, Any]]) -> list[tuple[str, list[dict[str, Any]]]]:
    result: list[tuple[str, list[dict[str, Any]]]] = []

    for run in split_target_runs(samples):
        gcd = max((sample.get("gcd", 0.0) for sample in run), default=0.0)
        settle_threshold = max(1.0, gcd * 1.5)
        start_threshold = max(3.0, gcd * 4.0)
        index = 0

        while index < len(run):
            if run[index]["reference_error"] > start_threshold:
                start = index
                settled = 0
                index += 1
                while index < len(run) and index - start < MAX_ACQUISITION_TICKS:
                    settled = settled + 1 if run[index]["reference_error"] <= settle_threshold else 0
                    index += 1
                    if settled >= 2:
                        break
                segment = run[start:index]
                if len(segment) >= 3:
                    result.append(("acquisition", segment))
            else:
                start = index
                while index < len(run) and run[index]["reference_error"] <= start_threshold:
                    index += 1
                segment = run[start:index]
                if len(segment) >= 5:
                    result.append(("tracking", segment))

    return result


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
        sum((x - mean_x) ** 2 for x in xs) * sum((y - mean_y) ** 2 for y in ys)
    )
    return numerator / denominator if denominator else None


def sign_crossings(items: Sequence[float], dead_zone: float) -> int:
    previous = 0
    crossings = 0
    for value in items:
        sign = 1 if value > dead_zone else -1 if value < -dead_zone else 0
        if sign and previous and sign != previous:
            crossings += 1
        if sign:
            previous = sign
    return crossings


def extract_features(
    label: str,
    session: str,
    kind: str,
    segment: Sequence[dict[str, Any]],
    attack_ticks: set[int],
) -> dict[str, Any]:
    speeds = values(segment, "angular_speed")
    accelerations = values(segment, "angular_acceleration")
    jerks = values(segment, "angular_jerk")
    errors = values(segment, "reference_error")
    exact_errors = values(segment, "exact_error")
    target_speeds = values(segment, "target_angular_speed")
    yaw_velocities = values(segment, "yaw_velocity")
    pitch_velocities = values(segment, "pitch_velocity")
    gcd = max(values(segment, "gcd"), default=0.0)
    on_target_threshold = max(1.0, gcd * 1.5)

    peak_speed = max(speeds) if speeds else None
    peak_location = None
    if peak_speed is not None and len(segment) > 1:
        peak_sample_index = next(
            index
            for index, sample in enumerate(segment)
            if sample.get("angular_speed") == peak_speed
        )
        peak_location = peak_sample_index / (len(segment) - 1)

    reversals = 0
    velocity_samples = [
        (sample.get("yaw_velocity"), sample.get("pitch_velocity")) for sample in segment
    ]
    for previous, current in zip(velocity_samples, velocity_samples[1:]):
        if None in previous or None in current:
            continue
        previous_speed = math.hypot(previous[0], previous[1])
        current_speed = math.hypot(current[0], current[1])
        if previous_speed > gcd and current_speed > gcd:
            if previous[0] * current[0] + previous[1] * current[1] < 0:
                reversals += 1

    error_yaw = values(segment, "reference_error_yaw")
    error_pitch = values(segment, "reference_error_pitch")

    return {
        "label": label,
        "session": session,
        "kind": kind,
        "start_tick": segment[0]["tick"],
        "end_tick": segment[-1]["tick"],
        "target_id": segment[-1].get("target_id"),
        "duration_ticks": len(segment),
        "initial_reference_error": errors[0] if errors else None,
        "final_reference_error": errors[-1] if errors else None,
        "mean_reference_error": statistics.fmean(errors) if errors else None,
        "mean_exact_error": statistics.fmean(exact_errors) if exact_errors else None,
        "peak_speed": peak_speed,
        "peak_location": peak_location,
        "angular_path_length": sum(speeds),
        "rms_acceleration": rms(accelerations),
        "rms_jerk": rms(jerks),
        "reversal_count": reversals,
        "overshoot_crossings": sign_crossings(error_yaw, gcd * 0.5)
        + sign_crossings(error_pitch, gcd * 0.5),
        "time_on_target_fraction": (
            sum(error <= on_target_threshold for error in errors) / len(errors) if errors else None
        ),
        "mean_target_speed": statistics.fmean(target_speeds) if target_speeds else None,
        "yaw_pitch_correlation": correlation(yaw_velocities, pitch_velocities),
        "mean_player_horizontal_speed": statistics.fmean(
            values(segment, "player_horizontal_speed")
        ),
        "mean_target_horizontal_speed": (
            statistics.fmean(values(segment, "target_horizontal_speed"))
            if values(segment, "target_horizontal_speed")
            else None
        ),
        "overshoot_phase_ticks": sum(sample.get("has_overshoot", False) for sample in segment),
        "contains_attack": any(sample["tick"] in attack_ticks for sample in segment),
    }


def build_attack_rows(
    recording: Recording, samples: Sequence[dict[str, Any]]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    by_tick = {sample["tick"]: sample for sample in samples}
    aligned: list[dict[str, Any]] = []
    features: list[dict[str, Any]] = []
    attack_ticks = {
        attack["tick"] for attack in recording.attacks if isinstance(attack.get("tick"), int)
    }

    for attack in recording.attacks:
        attack_tick = attack.get("tick")
        if not isinstance(attack_tick, int):
            continue
        window = []
        attack_target_id = attack.get("target_id")
        for offset in range(-ATTACK_WINDOW_BEFORE, ATTACK_WINDOW_AFTER + 1):
            sample = by_tick.get(attack_tick + offset)
            if sample is None:
                continue
            row = {
                "label": recording.label,
                "session": recording.path.name,
                "attack_tick": attack_tick,
                "attack_target_id": attack.get("target_id"),
                "offset": offset,
                "sample_target_id": sample.get("target_id"),
                "angular_speed": sample.get("angular_speed"),
                "angular_acceleration": sample.get("angular_acceleration"),
                "angular_jerk": sample.get("angular_jerk"),
                "reference_error": sample.get("reference_error"),
                "exact_error": sample.get("exact_error"),
                "target_angular_speed": sample.get("target_angular_speed"),
                "phase": sample.get("phase"),
                "has_overshoot": sample.get("has_overshoot"),
            }
            aligned.append(row)
            if not isinstance(attack_target_id, int) or sample.get("target_id") == attack_target_id:
                window.append(sample)
        if len(window) >= 5:
            features.append(
                extract_features(
                    recording.label,
                    recording.path.name,
                    "attack",
                    window,
                    attack_ticks,
                )
            )

    return aligned, features


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


def format_number(value: float | None, digits: int = 3) -> str:
    return "n/a" if value is None else f"{value:.{digits}f}"


def distribution_summary(rows: Sequence[dict[str, Any]], feature: str) -> str:
    data = values(rows, feature)
    if not data:
        return "n/a"
    return (
        f"{format_number(quantile(data, 0.5))} "
        f"[{format_number(quantile(data, 0.25))}, {format_number(quantile(data, 0.75))}]"
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
    generated = []

    for feature, title, y_label in (
        ("angular_speed", "Attack-aligned angular speed", "degrees/tick"),
        ("reference_error", "Attack-aligned target-reference error", "degrees"),
    ):
        figure, axis = plt.subplots(figsize=(9, 4.5))
        drew_data = False
        for label in ("manual", "off", "on"):
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


def write_report(
    output: Path,
    recordings: Sequence[Recording],
    samples: Sequence[dict[str, Any]],
    features: Sequence[dict[str, Any]],
    exclusions: Sequence[dict[str, Any]],
    plots: Sequence[str],
) -> None:
    attack_features = [row for row in features if row.get("kind") == "attack"]
    comparison_rows = attack_features or list(features)
    grouped = {
        label: [row for row in comparison_rows if row.get("label") == label]
        for label in ("manual", "off", "on")
    }
    lines = [
        "# Aim comparison report",
        "",
        "The primary comparison uses attack-aligned windows. Values are median [Q1, Q3]. "
        "Distribution distance is the empirical Kolmogorov-Smirnov statistic; lower is closer "
        "to the manual baseline.",
        "",
        "## Dataset",
        "",
        "| Label | Files | Ticks | Attacks | Excluded ticks |",
        "|---|---:|---:|---:|---:|",
    ]
    for label in ("manual", "off", "on"):
        label_recordings = [recording for recording in recordings if recording.label == label]
        lines.append(
            f"| {label} | {len(label_recordings)} | "
            f"{sum(1 for row in samples if row['label'] == label)} | "
            f"{sum(len(recording.attacks) for recording in label_recordings)} | "
            f"{sum(1 for row in exclusions if row['label'] == label)} |"
        )

    lines.extend(
        [
            "",
            "## Attack-window feature comparison",
            "",
            "| Feature | Manual | Humanize off | Humanize on | KS off | KS on | Closer |",
            "|---|---:|---:|---:|---:|---:|---|",
        ]
    )

    closer_counts = Counter()
    ks_off_values = []
    ks_on_values = []
    for feature in REPORT_FEATURES:
        manual_values = values(grouped["manual"], feature)
        off_values = values(grouped["off"], feature)
        on_values = values(grouped["on"], feature)
        ks_off = ks_statistic(manual_values, off_values)
        ks_on = ks_statistic(manual_values, on_values)
        closer = "n/a"
        if ks_off is not None and ks_on is not None:
            ks_off_values.append(ks_off)
            ks_on_values.append(ks_on)
            if abs(ks_off - ks_on) < 1e-9:
                closer = "tie"
            elif ks_on < ks_off:
                closer = "on"
            else:
                closer = "off"
            closer_counts[closer] += 1
        lines.append(
            f"| {feature} | {distribution_summary(grouped['manual'], feature)} | "
            f"{distribution_summary(grouped['off'], feature)} | "
            f"{distribution_summary(grouped['on'], feature)} | "
            f"{format_number(ks_off)} | {format_number(ks_on)} | {closer} |"
        )

    lines.extend(["", "## Interpretation", ""])
    if ks_off_values and ks_on_values:
        mean_off = statistics.fmean(ks_off_values)
        mean_on = statistics.fmean(ks_on_values)
        lines.append(
            f"- Mean feature-distribution distance to manual: off={mean_off:.3f}, on={mean_on:.3f}."
        )
        lines.append(
            f"- Humanize on is closer for {closer_counts['on']} features; off is closer for "
            f"{closer_counts['off']}; {closer_counts['tie']} are tied."
        )
        if mean_on < mean_off:
            lines.append(
                "- In these recordings, Humanize moves the measured trajectory features toward "
                "the manual baseline overall. This does not establish that an external detector "
                "will accept it."
            )
        else:
            lines.append(
                "- In these recordings, Humanize does not move the measured trajectory features "
                "toward the manual baseline overall. Controller revision should precede more "
                "constant tuning."
            )
    else:
        lines.append("- There is not enough comparable attack-window data for a distance result.")

    lines.extend(["", "## Recording checks", ""])
    sensitivities = defaultdict(set)
    gcds = defaultdict(set)
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
        lines.append(f"- Warning: mouse sensitivity differs between recordings: {sensitivities}.")
    if len(all_gcds) > 1:
        lines.append(f"- Warning: rotation GCD differs between recordings: {gcds}.")
    if not any(recording.warnings for recording in recordings) and len(all_sensitivities) <= 1 and len(all_gcds) <= 1:
        lines.append("- No schema, sensitivity, or GCD mismatch was found.")

    speed_counts = Counter(
        (sample["label"], sample.get("player_speed_amplifier", -1)) for sample in samples
    )
    if speed_counts:
        lines.append(f"- Player speed-amplifier tick counts: {dict(speed_counts)}.")

    for label in ("manual", "off", "on"):
        label_samples = [sample for sample in samples if sample["label"] == label]
        source_counts = Counter(sample.get("source") for sample in label_samples)
        lines.append(f"- `{label}` source tick counts: {dict(source_counts)}.")
        if label == "manual":
            automated = sum(sample.get("rotation_active", False) for sample in label_samples)
            if automated:
                lines.append(
                    f"- Warning: manual recordings contain {automated} ticks with an active rotation request."
                )
        else:
            expected_humanize = label == "on"
            automated_samples = [
                sample for sample in label_samples if sample.get("rotation_active", False)
            ]
            mismatches = sum(
                sample.get("humanize") is not expected_humanize for sample in automated_samples
            )
            if mismatches:
                lines.append(
                    f"- Warning: `{label}` contains {mismatches} active-rotation ticks with the "
                    "wrong Humanize state."
                )

    lines.extend(
        [
            "",
            "## Limitations",
            "",
            "- Manual target error uses the attacked/nearest player's bounding-box center as a "
            "reference; it is not a claim about the player's intended pixel-perfect aim point.",
            "- Exact requested-target error is available only while a rotation module supplies an endpoint.",
            "- The report is descriptive and should not be treated as a replica of an unknown anti-cheat model.",
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

    for recording in recordings:
        samples, exclusions = derive_samples(recording)
        all_samples.extend(samples)
        all_exclusions.extend(exclusions)
        attack_ticks = {
            attack["tick"]
            for attack in recording.attacks
            if isinstance(attack.get("tick"), int)
        }
        for kind, segment in movement_segments(samples):
            all_features.append(
                extract_features(
                    recording.label,
                    recording.path.name,
                    kind,
                    segment,
                    attack_ticks,
                )
            )
        aligned, attack_features = build_attack_rows(recording, samples)
        all_aligned.extend(aligned)
        all_features.extend(attack_features)

    write_csv(output / "movement_features.csv", all_features)
    write_csv(output / "attack_aligned.csv", all_aligned)
    write_csv(output / "excluded_samples.csv", all_exclusions)
    plots = [] if args.no_plots else make_plots(output, all_aligned)
    write_report(
        output,
        recordings,
        all_samples,
        all_features,
        all_exclusions,
        plots,
    )
    print(f"Wrote aim comparison report to {output / 'report.md'}")
    return 0


def self_test() -> int:
    assert wrap_angle(181.0) == -179.0
    assert wrap_angle(-181.0) == 179.0
    assert angle_delta(-179.0, 179.0) == 2.0
    assert ks_statistic([0.0, 1.0], [0.0, 1.0]) == 0.0
    assert ks_statistic([0.0, 0.0], [1.0, 1.0]) == 1.0
    assert quantile([0.0, 10.0], 0.5) == 5.0
    assert sign_crossings([-2.0, -1.0, 1.0, 2.0], 0.1) == 1

    synthetic_ticks = {}
    for tick in range(30):
        yaw = min(10.0, -10.0 + tick)
        synthetic_ticks[tick] = {
            "type": "tick",
            "tick": tick,
            "server_yaw": yaw,
            "server_pitch": 0.0,
            "player_motion_x": 0.0,
            "player_motion_z": 0.0,
            "source": "manual",
            "targets": [
                {
                    "id": 1,
                    "reference_yaw": 10.0,
                    "reference_pitch": 0.0,
                    "angular_distance": abs(10.0 - yaw),
                    "motion_x": 0.0,
                    "motion_z": 0.0,
                    "speed_amplifier": -1,
                }
            ],
        }
    synthetic = Recording(
        "manual",
        Path("synthetic.jsonl"),
        {"schema": 1, "gcd": 0.1},
        synthetic_ticks,
        [{"type": "attack", "tick": 20, "target_id": 1}],
        [],
    )
    samples, exclusions = derive_samples(synthetic)
    aligned, features = build_attack_rows(synthetic, samples)
    assert len(samples) == 30 and not exclusions
    assert len(aligned) == 26 and len(features) == 1
    assert features[0]["kind"] == "attack"

    with tempfile.TemporaryDirectory() as temp_directory:
        output = Path(temp_directory)
        write_csv(output / "features.csv", features)
        write_csv(output / "aligned.csv", aligned)
        write_report(output, [synthetic], samples, features, exclusions, [])
        assert (output / "report.md").is_file()
        assert "Aim comparison report" in (output / "report.md").read_text(encoding="utf-8")
    print("aim_compare self-test passed")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manual", nargs="+", help="manual Analysis-mode JSONL recording(s)")
    parser.add_argument("--off", nargs="+", help="Humanize-off Analysis-mode JSONL recording(s)")
    parser.add_argument("--on", nargs="+", help="Humanize-on Analysis-mode JSONL recording(s)")
    parser.add_argument("--output", default="aim-analysis", help="output directory")
    parser.add_argument("--no-plots", action="store_true", help="skip optional Matplotlib plots")
    parser.add_argument("--self-test", action="store_true", help="run dependency-free internal tests")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    try:
        return run_analysis(args)
    except (OSError, ValueError) as exception:
        parser.error(str(exception))
    return 2


if __name__ == "__main__":
    sys.exit(main())
