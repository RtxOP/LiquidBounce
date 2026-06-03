# GodBridge Findings

This document records only facts that are currently grounded in source code or cited external references. It intentionally avoids root-cause claims that still need local simulation or instrumentation.

## Definitions

### Rhythm

In this GodBridge investigation, rhythm means the repeating tick-sampled sequence:

```text
player position/eye origin -> valid face raycast window -> placement attempt -> motion advance -> next valid face raycast window -> reset
```

The reset can be jump, strafe, aim reset, or another movement/aim change. Public GodBridge sources commonly describe a reset around 7-8 blocks, but that is community evidence, not official Minecraft documentation.

Sources:

- Minecraft normally runs at 20 game ticks per second, 50 ms per tick: https://minecraft.fandom.com/wiki/Tick
- Community GodBridge explanation using tick/window language: https://www.reddit.com/r/CompetitiveMinecraft/comments/jsj9f3/godbridging_science/
- Community Hypixel guide describing 45/135 yaw, 75.0-75.8 pitch, and jumping every 8 blocks: https://hypixel.net/threads/how-to-godbridge-with-8-cps-tutorial.3958682/

### Drift

In this GodBridge investigation, drift means the tick-by-tick change in the player position and eye/raycast origin relative to the block face that must be hit for the next placement.

More explicitly:

```text
drift = movement of the raycast origin and player fractional block position
        relative to the valid side-face placement window,
        while the intended yaw/pitch and placement cadence remain mostly fixed.
```

Drift is not the same thing as falling. Falling is a later outcome. Drift is a pre-fall geometry/timing condition that may cause the raycast or placement attempt to stop hitting the required block face.

## Confirmed Minecraft Mechanics

### Game logic is tick-sampled

Minecraft normally runs game logic at 20 ticks per second, so one tick is 50 ms.

Source:

- https://minecraft.fandom.com/wiki/Tick

Why this matters:

- GodBridge placement opportunities are sampled at discrete game/update points, not continuously.
- If a valid placement face window exists between placement attempts, the client can miss it.

### Sneaking prevents normal edge-walk falling

Minecraft documentation states that sneaking prevents players from walking off block edges where the drop is high enough. It also lowers eye level by 1/8 block.

Source:

- https://minecraft.fandom.com/wiki/Sneaking

Local code confirmation:

- `src/main/java/net/ccbluex/liquidbounce/injection/forge/mixins/entity/MixinEntityPlayerSP.java:531`

Relevant code behavior:

- The mixin checks `onGround && isSneaking()`.
- When true, movement is reduced in 0.05-block steps until the offset would still leave collision below the player.
- This confirms that while sneaking/on-ground, the player should not simply walk off a normal block edge.

## Confirmed Client Mechanics

### Scaffold GodBridge uses its own placement path

Scaffold does not place by calling vanilla `Minecraft.rightClickMouse()` directly. Its placement path goes through:

- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:1137`
- `src/main/java/net/ccbluex/liquidbounce/utils/extensions/PlayerExtension.kt:227`

Confirmed behavior:

- `Scaffold.tryToPlaceBlock` calls `EntityPlayerSP.onPlayerRightClick`.
- `EntityPlayerSP.onPlayerRightClick` sends `C08PacketPlayerBlockPlacement`.
- It then calls local item/block use logic through `stack.onItemUse`.

Implication:

- Vanilla `rightClickDelayTimer` is probably not the first-order placement limiter for Scaffold's GodBridge path.
- Packet timing, raycast correctness, hit vector correctness, local item-use result, and server acceptance still matter.

Packet references:

- `C08PacketPlayerBlockPlacement` Forge docs: https://github.juanmuscaria.com/DocsMC/net/minecraft/network/play/client/C08PacketPlayerBlockPlacement.html
- Protocol description for block placement cursor coordinates: https://c4k3.github.io/wiki.vg/Protocol.html

### Placement packets include face hit and in-block cursor coordinates

The placement packet includes the block position, face, and cursor/hit coordinates within the block face.

Sources:

- https://c4k3.github.io/wiki.vg/Protocol.html
- https://github.juanmuscaria.com/DocsMC/net/minecraft/network/play/client/C08PacketPlayerBlockPlacement.html

Local code:

- `src/main/java/net/ccbluex/liquidbounce/utils/extensions/PlayerExtension.kt:237`
- `src/main/java/net/ccbluex/liquidbounce/utils/extensions/PlayerExtension.kt:242`

Confirmed behavior:

- The helper computes:

```text
facingX/facingY/facingZ = clickVec - clickPos
```

- Those values are sent in the placement packet.

Implication:

- Raycast hit-vector drift matters. Even if the block and face are correct, the exact hit vector can still differ from vanilla-like input.

### GodBridge placement is gated by raycast result

Scaffold checks whether a rotation raycast hits the expected block and side before accepting a placement target.

Local code:

- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:1021`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:1058`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:1068`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:1086`

Confirmed behavior:

- `findTargetPlace` first checks whether `currRotation` already hits the expected `offsetPos` and side.
- If not, it computes a candidate rotation and raycasts that.
- `performBlockRaytrace` traces from player eyes toward `eyes + getVectorForRotation(rotation) * reach`.

Implication:

- The valid placement window is geometrically defined by:

```text
eyes
rotation yaw/pitch
reach
target block
target face
hit vector
```

### Rotation vectors are derived from yaw/pitch

Local code:

- `src/main/java/net/ccbluex/liquidbounce/utils/rotation/RotationUtils.kt:482`

Confirmed behavior:

- `getVectorForRotation(yaw, pitch)` converts yaw/pitch to a direction vector.

Implication:

- With fixed yaw/pitch, the direction vector can remain stable while the raycast origin moves every tick. This is the core geometric reason drift can exist.

### Rotations are quantized through fixed sensitivity

Local code:

- `src/main/java/net/ccbluex/liquidbounce/utils/rotation/Rotation.kt:73`
- `src/main/java/net/ccbluex/liquidbounce/utils/rotation/RotationUtils.kt:591`
- `src/main/java/net/ccbluex/liquidbounce/utils/rotation/RotationUtils.kt:597`

Confirmed behavior:

- `fixedSensitivity()` snaps yaw/pitch to an angle step derived from Minecraft mouse sensitivity.

Implication:

- Requested angles such as `75.6` may not be the final applied server/client rotation after fixed-sensitivity snapping.

### GodBridge uses fixed-pattern rotations

Local code:

- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:1776`

Confirmed behavior:

- Straight GodBridge chooses:

```text
Rotation(movingYaw + side, optimized/custom pitch)
```

- Diagonal GodBridge chooses:

```text
Rotation(movingYaw, 75.6f)
```

- The result is passed through `fixedSensitivity()`.

Implication:

- This mode intentionally uses fixed-ish GodBridge rotations and then relies on raycast validation.
- The existence of fixed rotations is not by itself evidence of a bug.

### Player motion changes every tick

Local code:

- `src/main/java/net/ccbluex/liquidbounce/utils/simulation/SimulatedPlayer.kt:485`
- `src/main/java/net/ccbluex/liquidbounce/utils/simulation/SimulatedPlayer.kt:526`
- `src/main/java/net/ccbluex/liquidbounce/utils/simulation/SimulatedPlayer.kt:959`

Confirmed behavior:

- Movement input adds horizontal motion through `moveFlying`.
- Position is advanced through `moveEntity`.
- Gravity and friction are applied after movement.

Implication:

- Even with constant input and rotation, `posX`, `posZ`, eye position, and fractional block position change every tick.

### GodBridge side selection is a coarse position classifier

Local code:

- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:1342`

Confirmed behavior:

- `updateGodBridgeSide` uses player position and movement yaw to determine `isOnRightSide`.
- It checks projected position and whether current/next blocks below are air.

Implication:

- This logic classifies side choice. It is not a continuous measurement of the full raycast-valid face window.

## Confirmed Current Workspace State

At the time this document was written, the working tree had an uncommitted change in:

- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt`

Known local changes from prior work:

- `WaitForRotationsPostAlignTicks` range is `0..50`.
- `GOD_BRIDGE_DIAGONAL_NUDGE_TICKS` is `6`.
- Post-alignment wait clears forward and strafe input and does not keep injected strafe active.

These are workspace facts, not claims about correctness.

## Online Evidence Status

### Stronger sources

These are documentation/protocol-style sources and are stronger than community guides:

- Minecraft tick timing: https://minecraft.fandom.com/wiki/Tick
- Sneaking behavior: https://minecraft.fandom.com/wiki/Sneaking
- Placement packet fields: https://c4k3.github.io/wiki.vg/Protocol.html
- 1.8 packet class docs: https://github.juanmuscaria.com/DocsMC/net/minecraft/network/play/client/C08PacketPlayerBlockPlacement.html

### Weaker but relevant community sources

These are useful for understanding GodBridge player practice, but they are not official Minecraft documentation:

- Godbridging Science: https://www.reddit.com/r/CompetitiveMinecraft/comments/jsj9f3/godbridging_science/
- Hypixel GodBridge guide: https://hypixel.net/threads/how-to-godbridge-with-8-cps-tutorial.3958682/

Facts supported by community sources only:

- GodBridge commonly uses `45` or `135` degree yaw.
- GodBridge commonly uses pitch near `75.0` to `75.8`.
- Jump/strafe/aim reset is commonly discussed around 7-8 blocks.
- The concept of a short placement "window of opportunity" is common in GodBridge explanations.

## Not Yet Proven

The following are not established facts yet:

- The exact `fracX/fracZ/yaw/pitch/eyeY` boundaries of the valid GodBridge side-face raycast window.
- Why pitch `75.6` causes more falls in this specific client setup.
- Whether current failures are caused by client raycast miss, stale target selection, bad hit vector, local item-use failure, server rejection, or desync.
- Which pre-8-block failure occurs first.
- Whether the recent WaitFor changes are involved in the remaining pre-8-block falling case.

These need local geometry sweep, simulation, or instrumentation rather than more web research.
