#!/usr/bin/env python3
"""
Offline GodBridge raycast-window sweep.

This intentionally does not modify game code. It reproduces the small part of
the Scaffold geometry we need to inspect:

    eyes -> eyes + getVectorForRotation(yaw, pitch) * reach

Then it tests which cube face is hit first for a small modeled bridge world.

Assumptions:
  - Full cube blocks.
  - Player feet are on top of y=0 blocks, so feetY=1.0.
  - Standing eye offset is 1.62, sneaking lowers eyes by 0.125.
  - The "bridge block" being clicked is the target block at x=0..1, y=0..1,
    z=0..1. Neighbor blocks can be included so we can detect wrong first hits.
  - rel_x/rel_z are eye/player coordinates relative to that target block. They
    are not always the same as the player's own in-game fractional coordinates,
    because a valid side-face hit may happen when the player is already beyond
    the target block's edge.

This is a geometry sweep, not a server placement simulator.
"""

from __future__ import annotations

import argparse
import csv
import math
import sys
from dataclasses import dataclass
from typing import Iterable, Optional


REACH = 4.5
FEET_Y = 1.0
STANDING_EYE_OFFSET = 1.62
SNEAK_EYE_DROP = 0.125


@dataclass(frozen=True)
class Vec3:
    x: float
    y: float
    z: float

    def __add__(self, other: "Vec3") -> "Vec3":
        return Vec3(self.x + other.x, self.y + other.y, self.z + other.z)

    def __sub__(self, other: "Vec3") -> "Vec3":
        return Vec3(self.x - other.x, self.y - other.y, self.z - other.z)

    def __mul__(self, value: float) -> "Vec3":
        return Vec3(self.x * value, self.y * value, self.z * value)


@dataclass(frozen=True)
class Block:
    x: int
    y: int
    z: int
    name: str


@dataclass(frozen=True)
class Hit:
    block: Block
    side: str
    hit: Vec3
    distance: float


def get_vector_for_rotation(yaw: float, pitch: float) -> Vec3:
    """Port of RotationUtils.getVectorForRotation."""
    yaw_rad = math.radians(yaw)
    pitch_rad = math.radians(pitch)

    f = math.cos(-yaw_rad - math.pi)
    f1 = math.sin(-yaw_rad - math.pi)
    f2 = -math.cos(-pitch_rad)
    f3 = math.sin(-pitch_rad)

    return Vec3(f1 * f2, f3, f * f2)


def fixed_angle_delta(sensitivity: float) -> float:
    """Port of RotationUtils.getFixedAngleDelta."""
    return (sensitivity * 0.6 + 0.2) ** 3 * 1.2


def fixed_sensitivity_angle(target_angle: float, start_angle: float, gcd: float) -> float:
    """Port of RotationUtils.getFixedSensitivityAngle."""
    return start_angle + round((target_angle - start_angle) / gcd) * gcd


def apply_fixed_sensitivity(
    yaw: float,
    pitch: float,
    sensitivity: Optional[float],
    server_yaw: float,
    server_pitch: float,
) -> tuple[float, float]:
    if sensitivity is None:
        return yaw, pitch

    gcd = fixed_angle_delta(sensitivity)
    return (
        fixed_sensitivity_angle(yaw, server_yaw, gcd),
        max(-90.0, min(90.0, fixed_sensitivity_angle(pitch, server_pitch, gcd))),
    )


def intersect_block(start: Vec3, direction: Vec3, max_reach: float, block: Block) -> Optional[Hit]:
    """Ray vs axis-aligned unit cube. Returns first hit on this block, if any."""
    bounds = (
        (block.x, block.x + 1.0, "west", "east"),
        (block.y, block.y + 1.0, "down", "up"),
        (block.z, block.z + 1.0, "north", "south"),
    )

    t_min = 0.0
    t_max = max_reach
    enter_side = ""

    for origin, delta, (min_b, max_b, min_side, max_side) in zip(
        (start.x, start.y, start.z),
        (direction.x, direction.y, direction.z),
        bounds,
    ):
        if abs(delta) < 1.0e-12:
            if origin < min_b or origin > max_b:
                return None
            continue

        t1 = (min_b - origin) / delta
        t2 = (max_b - origin) / delta
        side1 = min_side
        side2 = max_side

        if t1 > t2:
            t1, t2 = t2, t1
            side1, side2 = side2, side1

        if t1 > t_min:
            t_min = t1
            enter_side = side1
        t_max = min(t_max, t2)

        if t_min > t_max:
            return None

    if t_min < 0.0 or t_min > max_reach:
        return None

    hit = start + direction * t_min
    return Hit(block, enter_side, hit, t_min)


def raycast(start: Vec3, direction: Vec3, max_reach: float, blocks: Iterable[Block]) -> Optional[Hit]:
    hits = [hit for block in blocks if (hit := intersect_block(start, direction, max_reach, block))]
    if not hits:
        return None
    return min(hits, key=lambda hit: hit.distance)


def block_world(motion: str, include_neighbors: bool) -> tuple[list[Block], Block, tuple[str, ...]]:
    """
    Model a current bridge block and optional neighbor blocks.

    The target is the block face a moving player would normally place against
    when extending the bridge opposite the movement direction.
    """
    if motion == "east":
        target = Block(0, 0, 0, "target")
        expected_sides = ("east",)
        neighbors = [Block(-1, 0, 0, "behind"), Block(0, 0, 1, "side_south"), Block(0, 0, -1, "side_north")]
    elif motion == "west":
        target = Block(0, 0, 0, "target")
        expected_sides = ("west",)
        neighbors = [Block(1, 0, 0, "behind"), Block(0, 0, 1, "side_south"), Block(0, 0, -1, "side_north")]
    elif motion == "south":
        target = Block(0, 0, 0, "target")
        expected_sides = ("south",)
        neighbors = [Block(0, 0, -1, "behind"), Block(1, 0, 0, "side_east"), Block(-1, 0, 0, "side_west")]
    elif motion == "north":
        target = Block(0, 0, 0, "target")
        expected_sides = ("north",)
        neighbors = [Block(0, 0, 1, "behind"), Block(1, 0, 0, "side_east"), Block(-1, 0, 0, "side_west")]
    elif motion == "northwest":
        target = Block(0, 0, 0, "target")
        expected_sides = ("east", "south")
        neighbors = [Block(1, 0, 0, "east_neighbor"), Block(0, 0, 1, "south_neighbor")]
    elif motion == "northeast":
        target = Block(0, 0, 0, "target")
        expected_sides = ("west", "south")
        neighbors = [Block(-1, 0, 0, "west_neighbor"), Block(0, 0, 1, "south_neighbor")]
    elif motion == "southwest":
        target = Block(0, 0, 0, "target")
        expected_sides = ("east", "north")
        neighbors = [Block(1, 0, 0, "east_neighbor"), Block(0, 0, -1, "north_neighbor")]
    elif motion == "southeast":
        target = Block(0, 0, 0, "target")
        expected_sides = ("west", "north")
        neighbors = [Block(-1, 0, 0, "west_neighbor"), Block(0, 0, -1, "north_neighbor")]
    else:
        raise ValueError(f"Unsupported motion: {motion}")

    return [target] + (neighbors if include_neighbors else []), target, expected_sides


def frange(start: float, stop: float, step: float) -> Iterable[float]:
    value = start
    epsilon = step / 10.0
    while value <= stop + epsilon:
        yield round(value, 6)
        value += step


def summarize(rows: list[dict[str, object]]) -> None:
    by_key: dict[tuple[str, float, float, float, float], list[dict[str, object]]] = {}
    for row in rows:
        key = (
            str(row["motion"]),
            float(row["requested_yaw"]),
            float(row["requested_pitch"]),
            float(row["applied_yaw"]),
            float(row["applied_pitch"]),
        )
        by_key.setdefault(key, []).append(row)

    print()
    print("# summary")
    print("motion,requested_yaw,requested_pitch,applied_yaw,applied_pitch,valid,total,percent")
    for key, group in sorted(by_key.items()):
        valid = sum(1 for row in group if row["valid"] == "true")
        total = len(group)
        percent = valid * 100.0 / total if total else 0.0
        print(f"{key[0]},{key[1]:.3f},{key[2]:.3f},{key[3]:.3f},{key[4]:.3f},{valid},{total},{percent:.2f}")


def summarize_windows(rows: list[dict[str, object]]) -> None:
    by_key: dict[tuple[str, float, float, float, float], list[dict[str, object]]] = {}
    for row in rows:
        if row["valid"] != "true":
            continue
        key = (
            str(row["motion"]),
            float(row["requested_yaw"]),
            float(row["requested_pitch"]),
            float(row["applied_yaw"]),
            float(row["applied_pitch"]),
        )
        by_key.setdefault(key, []).append(row)

    print()
    print("# valid_window_summary")
    print(
        "motion,requested_yaw,requested_pitch,applied_yaw,applied_pitch,"
        "valid,min_rel_x,max_rel_x,min_rel_z,max_rel_z,min_hit_y,max_hit_y,sides"
    )
    for key, group in sorted(by_key.items()):
        rel_x = [float(row["rel_x"]) for row in group]
        rel_z = [float(row["rel_z"]) for row in group]
        hit_y = [float(row["hit_y"]) for row in group]
        sides = "|".join(sorted({str(row["hit_side"]) for row in group}))
        print(
            f"{key[0]},{key[1]:.3f},{key[2]:.3f},{key[3]:.3f},{key[4]:.3f},"
            f"{len(group)},{min(rel_x):.3f},{max(rel_x):.3f},{min(rel_z):.3f},{max(rel_z):.3f},"
            f"{min(hit_y):.3f},{max(hit_y):.3f},{sides}"
        )


def parse_csv_floats(raw: str) -> list[float]:
    return [float(part.strip()) for part in raw.split(",") if part.strip()]


def main() -> int:
    parser = argparse.ArgumentParser(description="Sweep GodBridge raycast placement windows.")
    parser.add_argument("--motion", default="east", choices=[
        "east", "west", "north", "south", "northwest", "northeast", "southwest", "southeast",
    ])
    parser.add_argument("--yaws", default="90", help="Comma-separated requested yaws.")
    parser.add_argument("--pitches", default="73.5,75.0,75.6,75.8", help="Comma-separated requested pitches.")
    parser.add_argument("--rel-start", type=float, default=-0.5, help="Start of relative coordinate sweep.")
    parser.add_argument("--rel-stop", type=float, default=1.5, help="End of relative coordinate sweep.")
    parser.add_argument("--frac-step", type=float, default=0.025)
    parser.add_argument("--z", type=float, default=None, help="Fixed relZ. Defaults to sweeping relZ too.")
    parser.add_argument("--x", type=float, default=None, help="Fixed relX. Defaults to sweeping relX too.")
    parser.add_argument("--sneak", action="store_true", help="Lower eye height by 0.125 block.")
    parser.add_argument("--sensitivity", type=float, default=None, help="Apply fixedSensitivity using this MC sensitivity.")
    parser.add_argument("--server-yaw", type=float, default=0.0)
    parser.add_argument("--server-pitch", type=float, default=0.0)
    parser.add_argument("--reach", type=float, default=REACH)
    parser.add_argument("--no-neighbors", action="store_true", help="Only raycast the target block.")
    parser.add_argument("--summary", action="store_true", help="Print summary after CSV rows.")
    parser.add_argument("--summary-only", action="store_true", help="Only print the summary table.")
    parser.add_argument("--window-summary", action="store_true", help="Print min/max ranges for valid hits.")
    parser.add_argument("--valid-only", action="store_true", help="Only print valid hit rows.")
    args = parser.parse_args()

    yaws = parse_csv_floats(args.yaws)
    pitches = parse_csv_floats(args.pitches)
    xs = [args.x] if args.x is not None else list(frange(args.rel_start, args.rel_stop, args.frac_step))
    zs = [args.z] if args.z is not None else list(frange(args.rel_start, args.rel_stop, args.frac_step))

    blocks, target, expected_sides = block_world(args.motion, not args.no_neighbors)
    eye_y = FEET_Y + STANDING_EYE_OFFSET - (SNEAK_EYE_DROP if args.sneak else 0.0)

    fieldnames = [
        "motion",
        "rel_x",
        "rel_z",
        "requested_yaw",
        "requested_pitch",
        "applied_yaw",
        "applied_pitch",
        "eye_y",
        "hit_block",
        "hit_side",
        "hit_x",
        "hit_y",
        "hit_z",
        "distance",
        "expected_block",
        "expected_side",
        "valid",
    ]

    rows: list[dict[str, object]] = []
    writer = csv.DictWriter(sys.stdout, fieldnames=fieldnames)
    if not args.summary_only:
        writer.writeheader()

    for yaw in yaws:
        for pitch in pitches:
            applied_yaw, applied_pitch = apply_fixed_sensitivity(
                yaw, pitch, args.sensitivity, args.server_yaw, args.server_pitch
            )
            direction = get_vector_for_rotation(applied_yaw, applied_pitch)

            for frac_x in xs:
                for frac_z in zs:
                    start = Vec3(float(frac_x), eye_y, float(frac_z))
                    hit = raycast(start, direction, args.reach, blocks)
                    valid = hit is not None and hit.block == target and hit.side in expected_sides
                    row = {
                        "motion": args.motion,
                        "rel_x": f"{frac_x:.3f}",
                        "rel_z": f"{frac_z:.3f}",
                        "requested_yaw": f"{yaw:.3f}",
                        "requested_pitch": f"{pitch:.3f}",
                        "applied_yaw": f"{applied_yaw:.3f}",
                        "applied_pitch": f"{applied_pitch:.3f}",
                        "eye_y": f"{eye_y:.3f}",
                        "hit_block": hit.block.name if hit else "none",
                        "hit_side": hit.side if hit else "none",
                        "hit_x": f"{hit.hit.x:.3f}" if hit else "",
                        "hit_y": f"{hit.hit.y:.3f}" if hit else "",
                        "hit_z": f"{hit.hit.z:.3f}" if hit else "",
                        "distance": f"{hit.distance:.3f}" if hit else "",
                        "expected_block": target.name,
                        "expected_side": "|".join(expected_sides),
                        "valid": "true" if valid else "false",
                    }
                    rows.append(row)
                    if not args.summary_only and (not args.valid_only or valid):
                        writer.writerow(row)

    if args.summary or args.summary_only:
        summarize(rows)
    if args.window_summary:
        summarize_windows(rows)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
