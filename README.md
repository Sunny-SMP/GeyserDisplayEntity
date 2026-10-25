# GeyserDisplayEntity

> [!NOTE]
> This is a private fork of [GeyserDisplayEntity](https://github.com/GeyserExtensionists/GeyserDisplayEntity) for the SunnySMP server. It builds against SunnySMP's [Geyser fork](https://github.com/Sunny-SMP/Geyser), and nothing here is meant to go back upstream. For the official project, use [GeyserExtensionists/GeyserDisplayEntity](https://github.com/GeyserExtensionists/GeyserDisplayEntity).
>
> Most of the changes in this fork were written with the help of AI ([Claude Code](https://claude.com/claude-code)).

# About
This Geyser Extension provides Item Display support, allows players to see Nexo, CraftEngine and ItemAdder item displays

# Changes in this fork
### Blocks in displays
Upstream registers block displays but never renders them on Bedrock, and draws item displays of vanilla block items (wool, slabs, pumpkins, ...) with a rig tuned for flat custom items, so they come out tilted and lose roll and per-axis scale. Bedrock has no entity that draws an arbitrary block scaled and rotated freely, so this fork draws both itself. Builders don't need to do anything: block displays, and item displays of unmapped vanilla block items, render on Bedrock as they are.

#### How a display is drawn
Each display picks one of four kinds, and is respawned on Bedrock when a change moves it to another:

| Kind | Used for | Bedrock entity |
|---|---|---|
| Item rig | custom and mapped items (upstream's behaviour) | `geyser:item_display` with `geyser:b_mode` 0 |
| Falling block | plain blocks: unrotated and exactly one block in size | `minecraft:falling_block` |
| Block cube | every other block | `geyser:block_display`, or `geyser:item_display` with `geyser:b_mode` 1 |
| Hidden | hidden types, and blocks the cube can't draw that aren't plain | none |

- **Falling block.** The same entity Geyser uses for Java falling blocks. It draws the exact block, including blocks made of several parts like stairs and connected fences, but always one full block in size and upright. The client doesn't simulate it (`NO_AI`, no gravity). Bedrock draws it half a turn from the same block placed in the world, so the block state sent to it is turned half a turn (facings reversed, 16-step rotations moved by 8, north/south and east/west connections swapped).
- **Block cube.** The resource pack's own cube, used for anything stretched, resized or rotated: glass panes, panels, slabs, trapdoors, fence posts, rotated blocks. It draws one box with the block's real textures at any size, stretch and rotation. `block-displays.force-cube` uses it for plain blocks too.

Block displays wait for their first metadata before spawning, so they're created as the right kind straight away.

#### How the block cube works
1. **Data, at build time.** `tools/block_renderer/generate.py` reads a vanilla Java client jar. For every blockstate file it follows each variant (and each multipart part) to its model, resolves the model's parents and textures, and keeps the model's biggest element as the block's box, turned by the variant's `x`/`y` rotation. Block items get the box of their item model. The result is `src/main/resources/block_render.tsv`: one line per box (corners in pixels, a texture index per face, a translucent flag), per variant and part (the blockstate rule leading to a box) and per block item. It also writes the textures into the pack: first animation frame only, with the default plains grass and foliage colour baked into tinted faces (grass, leaves, vines, ...).
2. **Lookup, at runtime.** `BlockShapes` loads the table on startup. For a block state it takes Geyser's `minecraft:block[prop=value,...]` string and returns the first variant whose properties match, or for multipart blocks the first part whose condition matches (the post of a fence or wall), cached per state.
3. **Transform.** Java draws a point `m` of the model at `orientation * (translation + leftRotation * scale * rightRotation * (origin + modelRotation * modelScale * m))`, where `orientation` is the entity's yaw and pitch, and for item displays `origin`/`modelRotation`/`modelScale` are Java's half turn and the `item_display` mode's transform from `block/block.json`. The cube's bones apply the same chain: `block_left` (position, then the entity's orientation folded into the left rotation), `block_scale`, `block_right` (right rotation times the model rotation) and `block_shape` (the box: centre and size). The Bedrock entity itself never rotates. Rotations are converted to Bedrock's bone convention (Z, Y, X order in a model space turned half a turn, X and Y negated) and positions to pixels with Z flipped.
4. **Drawing.** Bedrock can only give one texture to a geometry per render controller, so the cube is six geometries, one per face, all sharing the bone chain. Each face's render controller picks its texture from the list by property (`geyser:bf_*`, -1 hides the face), and crops it with `uv_anim` to the part of the texture Java would use for that face of the box. Translucent textures (stained glass, ice, slime, ...) use a blending material; everything else uses alpha test.

The cube needs these entity properties on top of upstream's: the right rotation (`geyser:b_rrx/y/z`), the box (`geyser:bx1` ... `geyser:bz2`), the six face textures (`geyser:bf_n/s/e/w/u/d`) and `geyser:b_flags` (translucent, centred box). That's 29 on `geyser:block_display` and 30 on `geyser:item_display`, under Bedrock's limit of 32. They're registered in code, since existing installs never re-copy the `Entities/*.yml` files.

#### Limits
- Blocks made of several boxes (stairs, doors, connected fences and walls, flower pots, ...) show only their biggest box when drawn by the cube. Plain ones are falling blocks and are exact.
- Element rotations inside a model (the tilted planes of flowers, for example) aren't applied, and a box turned by a blockstate rotation gets Java's default UVs for its new position.
- Blocks without a model box (chests, signs, beds, skulls, banners, ...) are drawn by entity renderers on Java and are only shown when plain.
- Tinted blocks use the plains colour everywhere.

#### Displays riding displays
A display riding another display (builders use this to group parts) is drawn by Java at its vehicle's position. Geyser seats riders above the vehicle's hitbox, which lifted them by about 1.3 blocks, so riders on displays get a seat offset that puts them where Java draws them, taking into account where each of the two is drawn on Bedrock. Bedrock drops rider links when either entity is removed, so a display re-links to its vehicle and passengers whenever it respawns.

#### Hiding
Custom and mapped items keep upstream's item rig. Block displays are never hidden by `hide-unmapped-vanilla-displays`, and with `show-plain-vanilla-blocks` (on by default) neither are item displays of vanilla block items without a custom `item_model` or `custom_model_data`. Unmapped custom furniture is still hidden.

#### Regenerating the block data
Run after a Minecraft update, then rebuild the pack:
```
uv run --with pillow python tools/block_renderer/generate.py <client.jar>
python3 tools/build_pack.py
```
The textures are written to `pack/textures/geyser_display/` and aren't committed.

### Mappings
- A mapping can match the Java `item_model` component with `item-model: "namespace:path"`. Unlike `item-identifier`, it stays the same when the Bedrock pack is rebuilt and the converter generates new Bedrock identifiers.

### Resource pack
- The pack's sources are in `pack/`; `tools/build_pack.py` zips them into `packs/GeyserDisplayEntityPack.mcpack`.
- `geyser:block_display` is the block cube: six geometries `geometry.geyser_block_<face>` sharing one bone chain, each drawn by its `controller.render.geyser_block_<face>` with the face's texture (`geyser:bf_*`) and box (`geyser:bx*`/`by*`/`bz*`), animated by `animation.geyser_block`.
- `geyser:item_display` switches from its item rig to the block cube when `geyser:b_mode` is 1.
- `geometry.display` has a `body` bone at the end of the rig, so chest-slot attachables (custom items on item displays) get the display's translation, rotation and scale instead of hanging off the entity's root.
- Pack version is 1.5.0.

### Build
- Compiles against `net.sunnysmp.geyser:core` / `api` (SunnySMP's Geyser fork) from the SunnyInfra repository instead of `org.geysermc.geyser`.
- Java 25 toolchain (was 21), Shadow 9.6.1, newer Gradle wrapper, and a `gradle.properties` with build caching and configuration cache turned on.
- Group is `net.sunnysmp`.

### Repository
- Added `.gitignore` and `.gitattributes`. `.idea` is no longer tracked.

# FAQ
### How to use this extension?
You can use a converter to help you generate all of the required mappings and resoucres you need from [kafal](https://kafal.pogmc.net)
### Where can I contact you?
You can contact us on our Discord: https://discord.gg/NNNaUdAbpP

# Credits
- Most of the code comes from [Kastle's branch](https://github.com/Kas-tle/Geyser/tree/feature/display-entities)

# License
GeyserDisplayEntity is licensed under the [AGPL-3.0 license](LICENSE)
