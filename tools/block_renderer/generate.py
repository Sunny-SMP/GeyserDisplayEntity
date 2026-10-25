"""Generate the block renderer's data from a vanilla Java client jar.

Bedrock has no entity that draws an arbitrary block scaled and rotated freely, so block displays (and item displays of
block items) are drawn by the resource pack's own cube: one geometry per face, each textured from a list of the
vanilla block textures and cut down to the block's box. This script produces:

  * src/main/resources/block_render.tsv - per block, its blockstate rules leading to a shape (box + face textures),
    and per block item the shape of its item model. The extension reads it at startup.
  * pack/textures/geyser_display/blocks/*.png - the block textures (first animation frame, biome tint baked in).
  * pack/entity, pack/models/entity, pack/render_controllers, pack/animations - the files that list those textures.

Only one box per model is used: the biggest element. Blocks made of several boxes (stairs, connected fences, ...)
show their main box.

Usage: uv run --with pillow python tools/block_renderer/generate.py <client.jar> [repo root]
"""
import io, json, sys, zipfile
from pathlib import Path

from PIL import Image

FACES = ["north", "south", "east", "west", "up", "down"]
NORMALS = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}
DIRECTION = {v: k for k, v in NORMALS.items()}

# Which Bedrock geometry face shows each Java face. The pack's bone space has Z flipped against Java's world axes,
# so north and south swap.
BEDROCK_FACE = {"north": "south", "south": "north", "east": "east", "west": "west", "up": "up", "down": "down"}
SHORT = {"north": "n", "south": "s", "east": "e", "west": "w", "up": "u", "down": "d"}

# Default biome colours for tinted faces (plains), as Java's BlockColors would use outside a world
GRASS, FOLIAGE = (0x91, 0xBD, 0x59), (0x77, 0xAB, 0x2F)
TINTS = {
    **{b: GRASS for b in ("grass_block", "short_grass", "tall_grass", "fern", "large_fern", "potted_fern", "sugar_cane", "bush")},
    "birch_leaves": (0x80, 0xA7, 0x55), "spruce_leaves": (0x61, 0x99, 0x61), "lily_pad": (0x20, 0x80, 0x30),
    "water": (0x3F, 0x76, 0xE4), "water_cauldron": (0x3F, 0x76, 0xE4),
}


class Assets:
    def __init__(self, jar):
        self.zip = zipfile.ZipFile(jar)
        self.names = set(self.zip.namelist())
        self.model_cache = {}

    def json(self, path):
        return json.loads(self.zip.read(path))

    @staticmethod
    def location(ref, kind):
        ns, _, path = ref.partition(":") if ":" in ref else ("minecraft", "", ref)
        return f"assets/{ns}/{kind}/{path}"

    def model(self, ref):
        """Resolved (textures, elements) of a model, following parents."""
        ref = ref if ":" in ref else "minecraft:" + ref
        if ref in self.model_cache:
            return self.model_cache[ref]
        path = self.location(ref, "models") + ".json"
        if path not in self.names:
            self.model_cache[ref] = ({}, None)
            return self.model_cache[ref]
        data = self.json(path)
        textures, elements = ({}, None)
        if "parent" in data and not data["parent"].startswith("builtin/"):
            textures, elements = self.model(data["parent"])
            textures = dict(textures)
        textures.update(data.get("textures", {}))
        if "elements" in data:
            elements = data["elements"]
        self.model_cache[ref] = (textures, elements)
        return self.model_cache[ref]


def resolve_texture(textures, ref, depth=0):
    # Since 1.21.11 a texture can also be {"sprite": ..., "force_translucent": ...}
    sprite = lambda r: r.get("sprite", "") if isinstance(r, dict) else r
    ref = sprite(ref)
    while ref.startswith("#") and depth < 16:
        ref = sprite(textures.get(ref[1:], ""))
        depth += 1
    return None if not ref or ref.startswith("#") else (ref if ":" in ref else "minecraft:" + ref)


def main_element(elements):
    def size(e):
        d = [abs(t - f) for f, t in zip(e["from"], e["to"])]
        return (d[0] * d[1] * d[2], d[0] * d[1] + d[1] * d[2] + d[0] * d[2])
    return max(elements, key=size)


def rotation_matrix(x, y):
    """Java's ModelRotation: rotateYXZ(-y, -x, 0), in exact 90 degree steps."""
    def rx(a):
        c, s = [(1, 0), (0, 1), (-1, 0), (0, -1)][(a // 90) % 4]
        return [[1, 0, 0], [0, c, -s], [0, s, c]]
    def ry(a):
        c, s = [(1, 0), (0, 1), (-1, 0), (0, -1)][(a // 90) % 4]
        return [[c, 0, s], [0, 1, 0], [-s, 0, c]]
    def mul(a, b):
        return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
    return mul(ry(-y), rx(-x))


def apply(m, v):
    return tuple(sum(m[i][k] * v[k] for k in range(3)) for i in range(3))


class Generator:
    def __init__(self, assets):
        self.assets = assets
        self.textures = {}      # (texture, tint) -> index
        self.translucent = set()
        self.shapes = {}        # shape tuple -> index
        self.images = {}        # index -> PIL image

    def texture(self, name, tint):
        key = (name, tint)
        if key in self.textures:
            return self.textures[key]
        path = self.assets.location(name, "textures") + ".png"
        if path not in self.assets.names:
            return -1
        image = Image.open(io.BytesIO(self.assets.zip.read(path))).convert("RGBA")
        if image.height > image.width:  # animated strip: first frame
            image = image.crop((0, 0, image.width, image.width))
        if tint:
            pixels = image.load()
            for px in range(image.width):
                for py in range(image.height):
                    r, g, b, a = pixels[px, py]
                    pixels[px, py] = (r * tint[0] // 255, g * tint[1] // 255, b * tint[2] // 255, a)
        index = len(self.textures)
        self.textures[key] = index
        self.images[index] = image
        alphas = {a for _, _, _, a in image.getdata()}
        if any(0 < a < 255 for a in alphas):
            self.translucent.add(index)
        return index

    def shape(self, model_ref, owner, x=0, y=0):
        textures, elements = self.assets.model(model_ref)
        if not elements:
            return None
        element = main_element(elements)
        rot = rotation_matrix(x, y)
        corners = [apply(rot, (cx - 8, cy - 8, cz - 8)) for cx in (element["from"][0], element["to"][0])
                   for cy in (element["from"][1], element["to"][1]) for cz in (element["from"][2], element["to"][2])]
        lo = [min(c[i] for c in corners) + 8 for i in range(3)]
        hi = [max(c[i] for c in corners) + 8 for i in range(3)]
        faces = {f: -1 for f in FACES}
        flags = 0
        for name, face in element.get("faces", {}).items():
            texture = resolve_texture(textures, face.get("texture", ""))
            if texture is None:
                continue
            tint = TINTS.get(owner, FOLIAGE) if "tintindex" in face else None
            index = self.texture(texture, tint)
            if index < 0:
                continue
            faces[DIRECTION[apply(rot, NORMALS[name])]] = index
            if index in self.translucent:
                flags |= 1
        if all(i < 0 for i in faces.values()):
            return None
        key = tuple(round(v, 4) for v in lo + hi) + tuple(faces[f] for f in FACES) + (flags,)
        return self.shapes.setdefault(key, len(self.shapes))


def condition(when):
    """Multipart 'when' as alternatives separated by '||', each a comma list of prop=v1|v2 (all must hold)."""
    if not when:
        return "*"
    if "OR" in when or "or" in when:
        return "||".join(condition(w) for w in when.get("OR", when.get("or")))
    if "AND" in when or "and" in when:
        merged = {}
        for w in when.get("AND", when.get("and")):
            merged.update(w)
        return condition(merged)
    return ",".join(f"{k}={v}" for k, v in when.items())


def main():
    jar = sys.argv[1]
    root = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).resolve().parents[2]
    assets = Assets(jar)
    gen = Generator(assets)

    # Sanity checks on the rotation convention: a furnace facing east shows its front east,
    # an x-axis log shows its ends east and west
    furnace = gen.assets.model("minecraft:block/furnace")[1][0]["faces"]
    assert DIRECTION[apply(rotation_matrix(0, 90), NORMALS["north"])] == "east", "furnace y=90"
    assert DIRECTION[apply(rotation_matrix(90, 90), NORMALS["up"])] in ("east", "west"), "log x=90 y=90"

    lines, missing = [], []
    blockstates = sorted(n for n in assets.names if n.startswith("assets/minecraft/blockstates/") and n.endswith(".json"))
    for path in blockstates:
        block = "minecraft:" + path.rsplit("/", 1)[1][:-5]
        owner = block.split(":")[1]
        data = assets.json(path)
        found = False
        if "variants" in data:
            for key, apply_ in data["variants"].items():
                apply_ = apply_[0] if isinstance(apply_, list) else apply_
                shape = gen.shape(apply_["model"], owner, apply_.get("x", 0), apply_.get("y", 0))
                if shape is not None:
                    lines.append(f"variant\t{block}\t{key}\t{shape}")
                    found = True
        for part in data.get("multipart", []):
            apply_ = part["apply"][0] if isinstance(part["apply"], list) else part["apply"]
            shape = gen.shape(apply_["model"], owner, apply_.get("x", 0), apply_.get("y", 0))
            if shape is not None:
                lines.append(f"part\t{block}\t{condition(part.get('when'))}\t{shape}")
                found = True
        if not found:
            missing.append(block)

    items = 0
    for path in sorted(n for n in assets.names if n.startswith("assets/minecraft/items/") and n.endswith(".json")):
        model = assets.json(path).get("model", {})
        if model.get("type") not in ("minecraft:model", "model"):
            continue
        item = "minecraft:" + path.rsplit("/", 1)[1][:-5]
        _, elements = assets.model(model["model"])
        # Only 3D models: flat item sprites are drawn fine by the item rig already
        if not elements or model["model"].split(":")[-1].startswith("item/generated"):
            continue
        shape = gen.shape(model["model"], item.split(":")[1])
        if shape is not None:
            lines.append(f"item\t{item}\t{shape}")
            items += 1

    shape_lines = [f"shape\t{index}\t" + "\t".join(str(v) for v in key) for key, index in sorted(gen.shapes.items(), key=lambda kv: kv[1])]
    out = root / "src/main/resources/block_render.tsv"
    out.write_text("# Generated by tools/block_renderer/generate.py - do not edit\n"
                   "# shape: index, box from xyz, box to xyz (pixels), face textures n s e w u d (-1 = none), flags (1 = translucent)\n"
                   + "\n".join(shape_lines + lines) + "\n")

    write_pack(root / "pack", gen)
    print(f"blocks: {len(blockstates) - len(missing)} of {len(blockstates)} (no model box: {len(missing)}), "
          f"block items: {items}, shapes: {len(gen.shapes)}, textures: {len(gen.textures)} ({len(gen.translucent)} translucent)")


def write_pack(pack, gen):
    textures_dir = pack / "textures/geyser_display/blocks"
    textures_dir.mkdir(parents=True, exist_ok=True)
    for old in textures_dir.glob("*.png"):
        old.unlink()
    for index, image in gen.images.items():
        image.save(textures_dir / f"{index}.png", optimize=True)
    count = len(gen.textures)

    # One geometry per face: the shared bone chain and a unit cube centred on its last bone with only that face
    geometries = []
    for face in FACES:
        geometries.append({
            "description": {"identifier": f"geometry.geyser_block_{face}", "texture_width": 16, "texture_height": 16,
                            "visible_bounds_width": 16, "visible_bounds_height": 16, "visible_bounds_offset": [0, 0, 0]},
            "bones": [
                {"name": "block_left", "pivot": [0, 0, 0]},
                {"name": "block_scale", "parent": "block_left", "pivot": [0, 0, 0]},
                {"name": "block_right", "parent": "block_scale", "pivot": [0, 0, 0]},
                {"name": "block_shape", "parent": "block_right", "pivot": [0, 0, 0], "cubes": [
                    {"origin": [-8, -8, -8], "size": [16, 16, 16],
                     "uv": {BEDROCK_FACE[face]: {"uv": [0, 0], "uv_size": [16, 16]}}}]},
            ],
        })
    (pack / "models/entity/geyser_block.geo.json").write_text(json.dumps(
        {"format_version": "1.12.0", "minecraft:geometry": geometries}, indent="\t"))

    p = lambda name: f"q.property('geyser:{name}')"
    # Box in pixels; centred models (item displays) are shifted by -8 on every axis, Java's UVs aren't
    off = f"(math.mod(math.floor({p('b_flags')} / 2), 2) * 8)"
    x1, y1, z1, x2, y2, z2 = (f"({p(n)} + {off})" for n in ("bx1", "by1", "bz1", "bx2", "by2", "bz2"))
    # Java's default UV for each face of a box: [u1, v1, u2, v2]
    uvs = {
        "north": (f"16 - {x2}", f"16 - {y2}", f"16 - {x1}", f"16 - {y1}"),
        "south": (x1, f"16 - {y2}", x2, f"16 - {y1}"),
        "east": (f"16 - {z2}", f"16 - {y2}", f"16 - {z1}", f"16 - {y1}"),
        "west": (z1, f"16 - {y2}", z2, f"16 - {y1}"),
        "up": (x1, z1, x2, z2),
        "down": (x1, f"16 - {z2}", x2, f"16 - {z1}"),
    }
    texture_array = [f"Texture.t{i}" for i in range(count)]
    controllers = {}
    for face in FACES:
        u1, v1, u2, v2 = uvs[face]
        controllers[f"controller.render.geyser_block_{face}"] = {
            "arrays": {"textures": {"Array.textures": texture_array},
                       "materials": {"Array.materials": ["Material.cutout", "Material.blend"]}},
            "geometry": f"Geometry.block_{face}",
            "materials": [{"*": f"Array.materials[math.mod({p('b_flags')}, 2)]"}],
            "textures": [f"Array.textures[{p('bf_' + SHORT[face])}]"],
            "uv_anim": {"offset": [f"({u1}) / 16", f"({v1}) / 16"],
                        "scale": [f"(({u2}) - ({u1})) / 16", f"(({v2}) - ({v1})) / 16"]},
        }
    (pack / "render_controllers/geyser_block.render_controllers.json").write_text(json.dumps(
        {"format_version": "1.8.0", "render_controllers": controllers}, indent="\t"))

    (pack / "animations/geyser_block.animation.json").write_text(json.dumps({
        "format_version": "1.8.0",
        "animations": {"animation.geyser_block": {"loop": True, "bones": {
            "block_left": {"position": [p("t_x"), p("t_y"), p("t_z")], "rotation": [p("r_x"), p("r_y"), p("r_z")]},
            "block_scale": {"scale": [p("s_x"), p("s_y"), p("s_z")]},
            "block_right": {"rotation": [p("b_rrx"), p("b_rry"), p("b_rrz")]},
            "block_shape": {
                "position": [f"({p('bx1')} + {p('bx2')}) / 2", f"({p('by1')} + {p('by2')}) / 2", f"-({p('bz1')} + {p('bz2')}) / 2"],
                "scale": [f"({p('bx2')} - {p('bx1')}) / 16", f"({p('by2')} - {p('by1')}) / 16", f"({p('bz2')} - {p('bz1')}) / 16"],
            },
        }}},
    }, indent="\t"))

    block_textures = {f"t{i}": f"textures/geyser_display/blocks/{i}" for i in range(count)}
    block_geometry = {f"block_{face}": f"geometry.geyser_block_{face}" for face in FACES}
    block_materials = {"cutout": "entity_alphatest", "blend": "entity_alphablend"}
    face_controllers = lambda extra: [{f"controller.render.geyser_block_{face}": f"{extra}{p('bf_' + SHORT[face])} >= 0"} for face in FACES]

    block_entity = {"format_version": "1.10.0", "minecraft:client_entity": {"description": {
        "identifier": "geyser:block_display", "min_engine_version": "1.8.0",
        "materials": block_materials, "textures": block_textures, "geometry": block_geometry,
        "animations": {"block": "animation.geyser_block"},
        "scripts": {"animate": ["block"]},
        "render_controllers": face_controllers(""),
        "enable_attachables": False,
    }}}
    (pack / "entity/block_display.entity.json").write_text(json.dumps(block_entity, indent=4))

    # The item display keeps its item rig (geyser:b_mode 0) and switches to the block renderer for block items (1)
    item_path = pack / "entity/item_display.entity.json"
    item_entity = json.loads(item_path.read_text())
    d = item_entity["minecraft:client_entity"]["description"]
    rig = f"{p('b_mode')} == 0"
    d["materials"] = {"default": "armor_stand", **block_materials}
    d["textures"] = {"default": "textures/entity/armor_stand", **block_textures}
    d["geometry"] = {"default": "geometry.display", **block_geometry}
    d["animations"] = {k: v for k, v in d["animations"].items() if k in ("interpolate", "stable", "debug")}
    d["animations"]["block"] = "animation.geyser_block"
    d["scripts"]["animate"] = [
        {"interpolate": f"v.interpolating && {rig}"},
        {"stable": f"!v.interpolating && {rig}"},
        {"debug": rig},
        {"block": f"{p('b_mode')} == 1"},
    ]
    d["render_controllers"] = [{"controller.render.display": rig}] + face_controllers(f"{p('b_mode')} == 1 && ")
    item_path.write_text(json.dumps(item_entity, indent=4))


if __name__ == "__main__":
    main()
