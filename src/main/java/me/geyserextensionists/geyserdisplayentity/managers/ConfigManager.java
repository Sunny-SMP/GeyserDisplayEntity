package me.geyserextensionists.geyserdisplayentity.managers;

import me.geyserextensionists.geyserdisplayentity.GeyserDisplayEntity;
import me.geyserextensionists.geyserdisplayentity.util.FileConfiguration;
import me.geyserextensionists.geyserdisplayentity.util.FileUtils;
import org.cloudburstmc.math.vector.Vector3f;
import org.geysermc.geyser.api.util.Identifier;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

public class ConfigManager {

    private final GeyserDisplayEntity extension;

    private FileConfiguration config, lang;

    private final HashMap<Identifier, FileConfiguration> entityTypesCache = new HashMap<>();
    private LinkedHashMap<String, FileConfiguration> configMappingsCache;

    private Set<String> hideTypes = Set.of();
    private Set<String> hideCustomTypes = Set.of();
    private boolean hideUnmappedVanilla = true;
    private boolean showPlainVanillaBlocks = true;
    private boolean logDisplays = false;

    private boolean heldBlocks = false;
    private boolean forceHeldBlocks = false;
    private float hideStretchedBelow = 0f;
    private float hideResizedBeyond = 0f;
    private BlockPose blockPose = BlockPose.DEFAULT;

    /**
     * Pose of the held block in the block rig, undoing Bedrock's in-hand block transform:
     * rotation in degrees, position in pixels (1/16 block).
     */
    public record BlockPose(Vector3f rotation, Vector3f position, float scale) {
        public static final BlockPose DEFAULT = new BlockPose(Vector3f.from(285.9f, 348f, 345f), Vector3f.from(-1.3f, -18.2f, 2.1f), 2.2f);

        public String toYaml() {
            return "rotation: [" + rotation.getX() + ", " + rotation.getY() + ", " + rotation.getZ() + "], "
                    + "position: [" + position.getX() + ", " + position.getY() + ", " + position.getZ() + "], "
                    + "scale: " + scale;
        }
    }

    public ConfigManager(GeyserDisplayEntity extension) {
        this.extension = extension;

        load();
        loadEntityTypes();
    }

    public void load() {
        this.config = new FileConfiguration("config.yml");
        this.lang = new FileConfiguration("Lang/messages.yml");

        this.hideTypes = new HashSet<>(config.getStringList("hide-types"));
        this.hideCustomTypes = config.contains("hide-custom-types") ? new HashSet<>(config.getStringList("hide-custom-types")) : this.hideTypes;
        this.hideUnmappedVanilla = !config.contains("hide-unmapped-vanilla-displays") || config.getBoolean("hide-unmapped-vanilla-displays");
        this.showPlainVanillaBlocks = !config.contains("show-plain-vanilla-blocks") || config.getBoolean("show-plain-vanilla-blocks");
        this.logDisplays = config.getBoolean("settings.debug.log-displays");

        FileConfiguration blockDisplays = config.getConfigurationSection("block-displays");
        this.heldBlocks = blockDisplays != null && blockDisplays.getBoolean("held-item");
        this.forceHeldBlocks = blockDisplays != null && blockDisplays.getBoolean("force-held-item");
        this.hideStretchedBelow = blockDisplays != null && blockDisplays.contains("hide-stretched-below") ? blockDisplays.getFloat("hide-stretched-below") : 0.34f;
        this.hideResizedBeyond = blockDisplays != null && blockDisplays.contains("hide-resized-beyond") ? blockDisplays.getFloat("hide-resized-beyond") : 1.5f;
        this.blockPose = readBlockPose(blockDisplays == null ? null : blockDisplays.getConfigurationSection("pose"));

        if (!Files.exists(GeyserDisplayEntity.getExtension().dataFolder().resolve("Entities"))) {
            FileUtils.createFiles(GeyserDisplayEntity.getExtension(), "Entities/item-displays.yml");
            FileUtils.createFiles(GeyserDisplayEntity.getExtension(), "Entities/block-displays.yml");
        }

        if (!Files.exists(GeyserDisplayEntity.getExtension().dataFolder().resolve("Mappings"))) FileUtils.createFiles(GeyserDisplayEntity.getExtension(), "Mappings/example.yml");

        loadConfigMappings();
    }

    private static BlockPose readBlockPose(FileConfiguration pose) {
        BlockPose fallback = BlockPose.DEFAULT;
        if (pose == null) return fallback;

        Vector3f rotation = readVec3(pose, "rotation");
        Vector3f position = readVec3(pose, "position");
        return new BlockPose(
                rotation != null ? rotation : fallback.rotation(),
                position != null ? position : fallback.position(),
                pose.contains("scale") ? pose.getFloat("scale") : fallback.scale());
    }

    private static Vector3f readVec3(FileConfiguration cfg, String key) {
        if (!cfg.contains(key)) return null;
        List<Float> v = cfg.getFloatList(key);
        if (v.size() < 3) return null;
        return Vector3f.from(v.get(0), v.get(1), v.get(2));
    }

    private void loadEntityTypes() {
        List<File> entityFiles = new ArrayList<>(FileUtils.getAllFiles(GeyserDisplayEntity.getExtension().dataFolder().resolve("Entities").toFile(), ".yml"));

        for (File file : entityFiles) {
            FileConfiguration entityConfigFile = new FileConfiguration("Entities/" + file.getName());
            FileConfiguration entityConfig = entityConfigFile.getConfigurationSection("entity");
            if (entityConfig == null) continue;

            entityTypesCache.put(Identifier.of(entityConfig.getString("id")), entityConfig);
            extension.logger().info("Loaded EntityTypes: " + entityConfig.getString("id"));
        }
    }

    private void loadConfigMappings() {
        LinkedHashMap<String, FileConfiguration> tempConfigMappingsCache = new LinkedHashMap<>();
        List<File> mappingFiles = new ArrayList<>(FileUtils.getAllFiles(GeyserDisplayEntity.getExtension().dataFolder().resolve("Mappings").toFile(), ".yml"));
        mappingFiles.sort(Comparator.comparing(File::getAbsolutePath, String.CASE_INSENSITIVE_ORDER));

        for (File file : mappingFiles) {
            FileConfiguration mappingsConfigFile = new FileConfiguration("Mappings/" + file.getName());
            FileConfiguration mappingsConfig = mappingsConfigFile.getConfigurationSection("mappings");
            if (mappingsConfig == null) continue;

            tempConfigMappingsCache.put(file.getName().replace(".yml", ""), mappingsConfig);
        }

        this.configMappingsCache = tempConfigMappingsCache;
    }

    public FileConfiguration getConfig() {
        return config;
    }

    // "general" section, falling back to the pre-migration "displayentityoptions" one; null if neither exists
    public FileConfiguration getGeneralConfig() {
        FileConfiguration general = config.getConfigurationSection("general");
        if (general == null) general = config.getConfigurationSection("displayentityoptions");
        return general;
    }

    public FileConfiguration getLang() {
        return lang;
    }

    public HashMap<Identifier, FileConfiguration> getEntityTypesCache() {
        return entityTypesCache;
    }

    public LinkedHashMap<String, FileConfiguration> getConfigMappingsCache() {
        return configMappingsCache;
    }

    public Set<String> getHideTypes() {
        return hideTypes;
    }

    public Set<String> getHideCustomTypes() {
        return hideCustomTypes;
    }

    public boolean isHideUnmappedVanilla() {
        return hideUnmappedVanilla;
    }

    // Whether vanilla block items without a custom model stay visible when unmapped vanilla displays are hidden
    public boolean isShowPlainVanillaBlocks() {
        return showPlainVanillaBlocks;
    }

    public boolean isLogDisplays() {
        return logDisplays;
    }

    // Whether rotated or stretched blocks use the block rig; otherwise every block is a falling block
    public boolean isHeldBlocks() {
        return heldBlocks;
    }

    // Whether every block uses the block rig, even plain ones, to calibrate the pose
    public boolean isForceHeldBlocks() {
        return forceHeldBlocks;
    }

    // Falling blocks whose smallest scale axis is below this fraction of the largest are hidden; 0 hides none
    public float getHideStretchedBelow() {
        return hideStretchedBelow;
    }

    // Falling blocks are always one full block; displays more than this many times bigger or smaller are hidden; 0 hides none
    public float getHideResizedBeyond() {
        return hideResizedBeyond;
    }

    public BlockPose getBlockPose() {
        return blockPose;
    }

    // Changed live by the pose command; the next /reload restores the config's pose
    public void setBlockPose(BlockPose blockPose) {
        this.blockPose = blockPose;
    }
}
