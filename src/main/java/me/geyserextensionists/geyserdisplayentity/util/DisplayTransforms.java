package me.geyserextensionists.geyserdisplayentity.util;

import org.cloudburstmc.math.imaginary.Quaternionf;
import org.cloudburstmc.math.vector.Vector3f;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Java "fixed" display transform of each custom item model, by item_model id, from display_transforms.json
 * (written by the Bedrock converter next to the item mappings). Item frames draw their item with it.
 */
public final class DisplayTransforms {

    /**
     * Java's ItemTransform: translate (blocks), then rotate (rotationXYZ), then scale.
     */
    public record Transform(Vector3f translation, Quaternionf rotation, Vector3f scale) {
    }

    private static final Map<String, Transform> transforms = new HashMap<>();

    private DisplayTransforms() {
    }

    public static void load(Path file) throws IOException {
        transforms.clear();
        if (!Files.exists(file)) return;

        // JSON is valid YAML, so the extension's YAML loader reads it
        ConfigurationNode root = YamlConfigurationLoader.builder().path(file).build().load();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : root.childrenMap().entrySet()) {
            ConfigurationNode node = entry.getValue();
            // Java clamps translations to 80 pixels and scales to 4
            Vector3f translation = vector(node.node("translation"), 0f, 80f).div(16);
            Vector3f angles = vector(node.node("rotation"), 0f, Float.MAX_VALUE);
            Vector3f scale = vector(node.node("scale"), 1f, 4f);
            Quaternionf rotation = Quaternionf.fromAngleDegAxis(angles.getX(), 1f, 0f, 0f)
                    .mul(Quaternionf.fromAngleDegAxis(angles.getY(), 0f, 1f, 0f))
                    .mul(Quaternionf.fromAngleDegAxis(angles.getZ(), 0f, 0f, 1f));
            transforms.put(entry.getKey().toString(), new Transform(translation, rotation, scale));
        }
    }

    private static Vector3f vector(ConfigurationNode node, float fallback, float limit) throws SerializationException {
        List<Float> values = node.getList(Float.class, List.of());
        if (values.size() < 3) return Vector3f.from(fallback, fallback, fallback);
        return Vector3f.from(clamp(values.get(0), limit), clamp(values.get(1), limit), clamp(values.get(2), limit));
    }

    private static float clamp(float value, float limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    public static int size() {
        return transforms.size();
    }

    /**
     * The fixed transform of an item_model, or null if the converter didn't list it.
     */
    public static Transform get(String itemModel) {
        return transforms.get(itemModel);
    }
}
