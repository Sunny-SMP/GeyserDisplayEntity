# GeyserDisplayEntity

> [!NOTE]
> This is a private fork of [GeyserDisplayEntity](https://github.com/GeyserExtensionists/GeyserDisplayEntity) for the SunnySMP server. It builds against SunnySMP's [Geyser fork](https://github.com/Sunny-SMP/Geyser), and nothing here is meant to go back upstream. For the official project, use [GeyserExtensionists/GeyserDisplayEntity](https://github.com/GeyserExtensionists/GeyserDisplayEntity).
>
> Most of the changes in this fork were written with the help of AI ([Claude Code](https://claude.com/claude-code)).

# About
This Geyser Extension provides Item Display support, allows players to see Nexo, CraftEngine and ItemAdder item displays

# Changes in this fork
### Blocks in displays
Upstream registers block displays but never renders them on Bedrock, and draws item displays of vanilla block items (wool, slabs, pumpkins, ...) with a rig tuned for flat custom items, so they come out tilted and lose roll and per-axis scale. This fork draws both kinds of display, block displays and item displays of unmapped vanilla block items, as Bedrock's `minecraft:falling_block`, the same entity Geyser uses for Java falling blocks:
- The block is drawn at full size and level. Block displays show their exact block state.
- The client doesn't simulate it (`NO_AI`, no gravity).
- Bedrock always draws a falling block one full block in size and upright, whatever the entity's scale or rotation. A rotated display keeps its position but stays upright. Displays a full block can't stand in for are hidden: `block-displays.hide-stretched-below` (default 0.34) hides thin or flat ones like ropes and panels, and `block-displays.hide-resized-beyond` (default 1.5) hides ones much bigger or smaller than a block.

Custom and mapped items keep upstream's item rig. Block displays are never hidden by `hide-unmapped-vanilla-displays`, and with `show-plain-vanilla-blocks` (on by default) neither are item displays of vanilla block items without a custom `item_model` or `custom_model_data`. Unmapped custom furniture is still hidden.

#### Experimental: block rig
With `block-displays.held-item: true`, rotated or stretched blocks are instead shown as the block's item, held by a custom entity whose bones apply the full Java transform (translation, left rotation, per-axis scale, right rotation, plus the entity's own yaw and pitch). Item displays also get Java's half turn and the `block/block.json` display transform for their `item_display` mode. A held item shows the block's default model, so block states are lost, and blocks whose Bedrock item is a flat icon (fences, doors, ...) render flat.

Bedrock tilts and shrinks a held block in a fixed way, and `block-displays.pose` sets the rotation, position and scale that undo it. It isn't calibrated yet. To calibrate, set `force-held-item: true`, put a block display next to a placed block of the same type, and adjust the pose live until they match:
- `/geyserdisplayentity pose rotate <x|y|z> <degrees>`
- `/geyserdisplayentity pose move <x|y|z> <pixels>`
- `/geyserdisplayentity pose scale <value>`
- `/geyserdisplayentity pose reset` (back to the config's pose)

Each change applies to displays in view straight away and prints the pose to copy into `config.yml`. The Bedrock entity is respawned when a change moves a display between the falling block and the block rig, and block displays wait for their first metadata before spawning.

### Mappings
- A mapping can match the Java `item_model` component with `item-model: "namespace:path"`. Unlike `item-identifier`, it stays the same when the Bedrock pack is rebuilt and the converter generates new Bedrock identifiers.

### Resource pack
- `geyser:block_display` has its own geometry (`geometry.block_display`) and animation (`animation.display.block`), driven by the `geyser:t_*`, `geyser:r_*`, `geyser:s_*` and `geyser:b_*` properties.
- `geyser:item_display` switches to the same geometry and animation when `geyser:b_mode` is 1, through the new `controller.render.item_display`.
- `geometry.display` has a `body` bone at the end of the rig, so chest-slot attachables (custom items on item displays) get the display's translation, rotation and scale instead of hanging off the entity's root.
- Pack version is 1.4.2.

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
