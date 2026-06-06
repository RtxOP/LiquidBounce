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

At the time of the 2026-06-05 source audit, `git status --short` was clean.

Recent pushed diagnostic/experiment commits include:

- `dd764938c Log GodBridge vanilla order heartbeat on risky misses`
- `aed9a5de5 Add GodBridge vanilla raycast order diagnostics`
- `c039ad499 Add GodBridge release phase alignment`

These commits are workspace facts, not claims about correctness.

## 2026-06-05 MCP/Codebase Source Audit

### Vanilla 1.8.9 right-click placement consumes the current mouse-over result

Local MCP files used:

- `/tmp/MCP-Minecraft.java:1756`
- `/tmp/MCP-Minecraft.java:1570`
- `/tmp/MCP-Minecraft.java:1605`
- `/tmp/MCP-PlayerControllerMP.java:390`
- `/tmp/MCP-PlayerControllerMP.java:424`
- `/tmp/MCP-ItemBlock.java:38`
- `/tmp/MCP-ItemBlock.java:43`
- `/tmp/MCP-ItemBlock.java:56`

Confirmed behavior:

- `Minecraft.runTick()` calls `entityRenderer.getMouseOver(1.0F)` once near the start of the tick.
- `Minecraft.rightClickMouse()` later consumes `this.objectMouseOver`.
- For block hits, vanilla calls `playerController.onPlayerRightClick(...)` with exactly:

```text
objectMouseOver.getBlockPos()
objectMouseOver.sideHit
objectMouseOver.hitVec
```

- `PlayerControllerMP.onPlayerRightClick()` sends `C08PacketPlayerBlockPlacement` with the same block, side, and in-block hit offsets, then runs item-use logic.
- `ItemBlock.onItemUse()` places into `pos.offset(side)` when the clicked block is not replaceable, then checks `worldIn.canBlockBePlaced(...)`.

Implication:

- Vanilla has no separate planned placement target. The block/face/hitVec used by placement is the current tick's mouse-over result.
- The raycast side is not cosmetic. For normal full blocks, the clicked side determines the actual replaceable cell where the new block is placed.

### Vanilla raycast face selection is geometric and side-sensitive

Local MCP files used:

- `/tmp/MCP-World.java:888`
- `/tmp/MCP-World.java:1012`
- `/tmp/MCP-World.java:1030`
- `/tmp/MCP-World.java:1041`
- `/tmp/MCP-Block.java:681`
- `/tmp/MCP-Block.java:761`
- `/tmp/MCP-Block.java:793`

Confirmed behavior:

- `World.rayTraceBlocks(...)` walks block cells along the ray and records which grid boundary was crossed.
- `Block.collisionRayTrace(...)` tests the six block bounding-box planes and returns the nearest intersected face.
- The returned `MovingObjectPosition` contains the clicked block position and the selected face.

Implication:

- The transition from `face:UP` to horizontal side hit to `miss` is a direct geometric result of eye position, look vector, and block bounds.
- A vanilla-valid GodBridge click must have a side hit on the tick where right-click is processed. If the tick sample is `UP` or `miss`, vanilla would not place the desired horizontal block on that tick either.

### LiquidBounce renderer raycast is synthetic when OverrideRaycast is active

Local code:

- `src/main/java/net/ccbluex/liquidbounce/injection/forge/mixins/render/MixinEntityRenderer.java:139`
- `src/main/java/net/ccbluex/liquidbounce/injection/forge/mixins/render/MixinEntityRenderer.java:151`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/misc/OverrideRaycast.kt:11`

Confirmed behavior:

- LiquidBounce cancels vanilla `EntityRenderer.getMouseOver`.
- It computes the look vector from `RotationUtils.currentRotation` when `OverrideRaycast.shouldOverride()` is true.
- `OverrideRaycast` has `AlwaysActive` defaulting true, so the rendered/object mouse-over path is normally synthetic when a current rotation exists.

Implication:

- Logs comparing Scaffold's own raycast to `objectMouseOver` only compare two synthetic-current-rotation paths when OverrideRaycast is active.
- Those logs do not prove equivalence to raw player-camera vanilla raycast. They can still prove internal consistency between Scaffold's ray and the overridden mouse-over ray.

### Synthetic ray vector math matches MCP ray vector math

Local MCP/code:

- `/tmp/MCP-Entity.java:1476`
- `/tmp/MCP-Entity.java:1500`
- `src/main/java/net/ccbluex/liquidbounce/utils/rotation/RotationUtils.kt:482`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:1158`

Confirmed behavior:

- MCP `Entity.getVectorForRotation()` and LiquidBounce `RotationUtils.getVectorForRotation()` use the same trigonometric structure.
- MCP `Entity.rayTrace()` and Scaffold `performBlockRaytraceFromEyes()` both call `rayTraceBlocks(..., false, false, true)` for the block ray.

Implication:

- The basic synthetic ray formula is not currently a strong root-cause candidate.
- Differences are more likely to come from which rotation/eye position/target is used, not from the vector formula itself.

### Movement correction is not proven to be the failure

Local MCP/code:

- `/tmp/MCP-Entity.java:1224`
- `src/main/java/net/ccbluex/liquidbounce/utils/rotation/Rotation.kt:91`
- `src/main/java/net/ccbluex/liquidbounce/utils/simulation/SimulatedPlayer.kt:959`
- `src/main/java/net/ccbluex/liquidbounce/utils/rotation/RotationSettings.kt:32`
- `src/main/java/net/ccbluex/liquidbounce/utils/rotation/RotationUtils.kt:728`
- `src/main/java/net/ccbluex/liquidbounce/injection/forge/mixins/entity/MixinEntity.java:249`
- `src/main/java/net/ccbluex/liquidbounce/injection/forge/mixins/entity/MixinEntityPlayerSP.java:341`

Confirmed behavior:

- MCP `moveFlying()` normalizes diagonal input before applying yaw-based horizontal acceleration.
- LiquidBounce has a non-strict strafe correction that can map real-player input into a corrected forward/strafe pair for `currentRotation`.
- That correction is only applied to movement when `RotationSettings.strafe` is true. The setting defaults false.
- `RotationUtils.onStrafe` returns immediately when `activeSettings.strafe` is false.
- `MixinEntityPlayerSP` computes `modifiedInput`, but that alone does not move the player. The movement rewrite happens through the `moveFlying`/`StrafeEvent` path.

Implication:

- It is not correct to conclude "synthetic rotation is broken" from the transform alone; the transform can be mathematically reasonable when engaged.
- It is also not correct to conclude that `Strafe=false` is a root cause from code structure alone.
- The thing that matters is the resulting world-space motion vector and tick phase, not whether the motion was produced from real yaw or `currentRotation`.
- A server-side rotation split can still be vanilla-equivalent if the user's real-yaw-relative input produces the same world-space movement that a vanilla player would produce while physically looking at the GodBridge angle.

### Server-side currentRotation splits state, but the effect must be proven geometrically

Local code:

- `src/main/java/net/ccbluex/liquidbounce/utils/rotation/RotationUtils.kt:546`
- `src/main/java/net/ccbluex/liquidbounce/utils/rotation/RotationUtils.kt:658`
- `src/main/java/net/ccbluex/liquidbounce/utils/rotation/RotationUtils.kt:756`
- `src/main/java/net/ccbluex/liquidbounce/injection/forge/mixins/render/MixinEntityRenderer.java:150`
- `src/main/java/net/ccbluex/liquidbounce/injection/forge/mixins/render/MixinEntityRenderer.java:151`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:1363`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:1367`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:1383`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:2461`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:2465`

Confirmed behavior:

- With `ApplyServerSide` true, `RotationUtils` stores the requested yaw/pitch in `currentRotation` instead of writing it into `mc.thePlayer.rotationYaw/rotationPitch`.
- `RotationUtils.onPacket` writes `currentRotation` into outgoing `C03PacketPlayer`.
- `MixinEntityRenderer` uses `currentRotation` for `objectMouseOver` when `OverrideRaycast` is active.
- GodBridge rotation generation also branches on `options.applyServerSide`.
- `getGodBridgeMovingYaw(...)` computes the movement direction from real `player.rotationYaw` and movement input.
- Straight GodBridge then sets synthetic look yaw to `movingYaw +/- 45`.

Implication:

- In vanilla 1.8.9, the same rotation fields drive crosshair raycast, movement yaw, and outgoing player look packets.
- In this client, server-side Scaffold can make raycast and packets look rotated while the local physical player still moves under the real camera yaw unless movement strafe correction is active.
- That state split is a source-backed difference from vanilla, but it is not automatically harmful.
- In GodBridge, the split appears intentional: real yaw/input defines the world movement direction, while synthetic yaw represents the vanilla head angle relative to that movement direction.
- Therefore `Strafe=false` is not a standalone bug. To make this a root-cause candidate, we must show that the resulting world-space movement delta differs from a vanilla player moving in the same bridge direction while looking at the synthetic GodBridge yaw.
- If those deltas match, this state split is not the cause. If they do not match, then it can change the fractional X/Z phase schedule that determines whether a tick samples `face:UP`, horizontal side hit, or `miss`.

### Scaffold normal placement path uses a planned target gate

Local code:

- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:537`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:542`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:579`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:595`

Confirmed behavior:

- `onTick` reads `target = placeRotation?.placeInfo`.
- It computes the current raycast separately.
- It only places on the normal path when the current raycast matches the planned target block and, when proper raycast is required, the planned side.
- If it places, it may use the current raycast block/side/hitVec, but only after the planned target gate passes.

Implication:

- This is not vanilla's "click current objectMouseOver" behavior.
- A valid current ray hit can still be ignored if it does not match `placeRotation`.

### Scaffold GodBridge target search is center-only and planner-based

Local code:

- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:721`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:986`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:1021`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:1084`

Confirmed behavior:

- `findBlock()` chooses a `blockPosition` based mostly on player position, commonly `BlockPos(player).down()` for normal horizontal placement.
- `search()` scans neighboring clicked blocks around that planned replaceable block.
- For GodBridge, `search()` uses only `Vec3(0.5, 0.5, 0.5)` rather than the non-GodBridge area scan.
- `findTargetPlace()` validates that a ray can hit the planned neighbor and side.

Implication:

- GodBridge's normal path is closer to normal Scaffold planning than to human vanilla GodBridge clicking.
- It preselects a future/expected placeable relationship, then later requires the current ray to match that relationship.

### Extra-click placement is closer to current-ray placement

Local code:

- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:825`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:835`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:859`

Confirmed behavior:

- `doPlaceAttempt()` takes the current raytrace directly.
- It places using `raytrace.blockPos`, `raytrace.sideHit`, and `raytrace.hitVec`.
- This path still has its own `shouldPlace` filter, but it does not require `placeRotation` to match first.

Implication:

- The codebase already contains both architectures:

```text
normal Scaffold path: planned target -> current ray must match target -> place
extra-click path: current ray -> shouldPlace filter -> place
vanilla: objectMouseOver/current ray -> place
```

### The planner/gate difference is a plausible issue, not yet proven root cause

Why the difference could matter:

- Vanilla samples the crosshair result once per tick and clicks whatever block/face is there.
- Scaffold GodBridge samples a planned target separately from the current ray and rejects placement if the current ray does not match the planned target.
- User logs have shown sequences where the ray state can move from `face:UP` to side-hit to `miss` over a narrow tick window.
- If the planner target is stale, one block behind/ahead, or based on the wrong side for the current fixed GodBridge ray, the client can wait through the only valid side-hit tick.

Why this is not proven yet:

- The planner/gate mismatch explains a possible failure mode, but it does not by itself prove that the observed falls are caused by stale targets.
- A vanilla client also only samples `objectMouseOver` once per tick. If a valid side-hit window exists only between tick samples, vanilla would miss too.
- Therefore the remaining question is whether Scaffold's planner/gate creates missed tick-sampled side hits that vanilla/current-ray placement would have accepted.
- Several provided failure excerpts show the current sampled ray becoming `miss` before a side-hit pass on that target. Those excerpts point more strongly at phase/rotation/movement equivalence than at target-gate rejection.

### Release-phase alignment is compensatory, not root-cause evidence

Local code:

- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:1987`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:1518`

Confirmed behavior:

- Release-phase alignment waits for a configured fractional phase and can zero movement while waiting.
- The user reported continued failures after this was added.

Implication:

- Release-phase alignment may affect initial phase and timing, but it is not strong evidence for the true root cause.
- Further fixes should not be built around adding more compensatory waits without a concrete source-backed mismatch.

### Sprint state is a vanilla-equivalence axis

Local code:

- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt:104`
- `src/main/java/net/ccbluex/liquidbounce/features/module/modules/movement/Sprint.kt:64`

Confirmed behavior:

- Scaffold has a `Sprint` setting defaulting false.
- The Sprint module explicitly disables sprint while Scaffold is active unless `Scaffold.sprint` is true.
- User logs showing horizontal motion around `0.120` are consistent with walking-speed movement rather than sprinting.

Implication:

- Comparisons to vanilla GodBridge must specify walking vs sprinting.
- This is not currently a proposed fix; it is a state-equivalence note.

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
- Whether current failures are caused by stale planner target selection, current-ray miss, bad hit vector, local item-use failure, server rejection, or desync.
- Which pre-8-block failure occurs first.
- Whether direct current-ray placement would accept a placement on ticks where the planned-target gate currently misses.
- Whether the remaining pre-8-block falling case appears when all compensatory wait/release-phase behavior is bypassed.

These need source-level geometry comparison or local simulation. Additional logging should not be added unless there is a concrete, narrow question that cannot be answered from code or deterministic simulation.
