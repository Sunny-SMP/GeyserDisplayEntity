package me.geyserextensionists.geyserdisplayentity.entity;

import me.geyserextensionists.geyserdisplayentity.GeyserDisplayEntity;
import me.geyserextensionists.geyserdisplayentity.managers.ConfigManager;
import me.geyserextensionists.geyserdisplayentity.type.DisplayType;
import me.geyserextensionists.geyserdisplayentity.util.BlockShapes;
import me.geyserextensionists.geyserdisplayentity.util.DeltaUtils;
import me.geyserextensionists.geyserdisplayentity.util.FileConfiguration;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.math.imaginary.Quaternionf;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobArmorEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityLinkPacket;
import org.geysermc.geyser.api.util.Identifier;
import org.geysermc.geyser.entity.BedrockEntityDefinition;
import org.geysermc.geyser.entity.VanillaEntities;
import org.geysermc.geyser.entity.properties.type.FloatProperty;
import org.geysermc.geyser.entity.properties.type.IntProperty;
import org.geysermc.geyser.entity.spawn.EntitySpawnContext;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.vehicle.ClientVehicle;
import org.geysermc.geyser.registry.type.ItemMapping;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.item.ItemTranslator;
import org.geysermc.geyser.util.MathUtils;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.BooleanEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ByteEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.CustomModelData;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static me.geyserextensionists.geyserdisplayentity.GeyserDisplayEntity.MAX_VALUE;
import static me.geyserextensionists.geyserdisplayentity.GeyserDisplayEntity.MIN_VALUE;

public class ItemDisplayEntity extends SlotDisplayEntity {

    /**
     * How the display is drawn on Bedrock. Custom and mapped items use the original item rig. Vanilla blocks that are
     * plain (unrotated, one block in size) are falling blocks, which draw the exact block. Every other block is drawn
     * by the resource pack's block cube, whose bones apply the full Java transform to the block's box and textures.
     */
    protected enum Kind { ITEM_RIG, BLOCK_CUBE, FALLING_BLOCK, HIDDEN }

    // Bedrock entity models face the opposite way to Java's world axes at yaw 0
    private static final Quaternionf MODEL_SPACE = Quaternionf.fromAngleDegAxis(180f, 0f, 1f, 0f);
    private static final Quaternionf HALF_TURN = Quaternionf.fromAngleDegAxis(180f, 0f, 1f, 0f);

    // Right rotation of the block cube; translation, left rotation and scale reuse geyser:t_*, geyser:r_* and geyser:s_*
    public static final String RIGHT_ROTATION_X = "geyser:b_rrx";
    public static final String RIGHT_ROTATION_Y = "geyser:b_rry";
    public static final String RIGHT_ROTATION_Z = "geyser:b_rrz";

    // The block's box in pixels, and the texture index per face (north, south, east, west, up, down; -1 draws none)
    public static final String[] BOX = {"geyser:bx1", "geyser:by1", "geyser:bz1", "geyser:bx2", "geyser:by2", "geyser:bz2"};
    public static final String[] FACES = {"geyser:bf_n", "geyser:bf_s", "geyser:bf_e", "geyser:bf_w", "geyser:bf_u", "geyser:bf_d"};
    // 1: translucent textures (blend instead of cutout); 2: the box is centred on the origin (item models)
    public static final String FLAGS = "geyser:b_flags";

    // Switches geyser:item_display between the item rig (0) and the block cube (1)
    public static final String BLOCK_MODE = "geyser:b_mode";

    private DisplayType displayType = DisplayType.NONE;
    private Byte color;
    private boolean custom = false;
    private boolean needHide = false;
    private boolean mappingApplied = false;
    private BlockDefinition itemBlock;
    private BlockShapes.Shape itemShape;

    protected Kind kind;
    private BedrockEntityDefinition rigDefinition;

    // The Java transform, kept whole for the block kinds; the item rig is replayed from the raw metadata
    private Vector3f javaTranslation = Vector3f.ZERO;
    private Vector3f javaScale = Vector3f.ONE;
    private Quaternionf javaLeftRotation = Quaternionf.IDENTITY;
    private Quaternionf javaRightRotation = Quaternionf.IDENTITY;
    private EntityMetadata<Vector3f, ?> translationMetadata;
    private EntityMetadata<Vector3f, ?> scaleMetadata;
    private EntityMetadata<Quaternionf, ?> leftRotationMetadata;
    private EntityMetadata<Quaternionf, ?> rightRotationMetadata;
    private float lastYaw;
    private float lastPitch;

    public ItemDisplayEntity(EntitySpawnContext entitySpawnContext) {
        super(entitySpawnContext);
    }

    @Override
    public void spawnEntity() {
        if (rigDefinition == null) rigDefinition = bedrockDefinition();
        kind = wantedKind();
        applyKind();
        spawnAsKind();
    }

    private void spawnAsKind() {
        if (kind == Kind.HIDDEN) return;
        super.spawnEntity();
        moveAbsoluteRaw(position, yaw, pitch, headYaw, onGround, true);
        if (kind != Kind.FALLING_BLOCK) updateMainHand(session);
        relink();
    }

    /**
     * Re-sends the links to this display's vehicle and passengers. Bedrock drops them when either side is removed,
     * which a respawn as another kind does, and a link sent before a block display's deferred spawn never arrives.
     */
    private void relink() {
        if (vehicle != null && vehicle.isValid()) link(vehicle, this);
        for (Entity passenger : passengers) {
            if (passenger == null || !passenger.isValid()) continue;
            link(this, passenger);
            if (passenger instanceof ItemDisplayEntity display) display.updateSeat();
        }
    }

    private void link(Entity mount, Entity rider) {
        boolean first = !mount.getPassengers().isEmpty() && mount.getPassengers().get(0) == rider;
        SetEntityLinkPacket linkPacket = new SetEntityLinkPacket();
        linkPacket.setEntityLink(new EntityLinkData(mount.geyserId(), rider.geyserId(),
                first ? EntityLinkData.Type.RIDER : EntityLinkData.Type.PASSENGER, false, false, 0f));
        session.sendUpstreamPacket(linkPacket);
    }

    // Geyser seats a rider above the vehicle's hitbox, but Java draws a display riding another display right at the
    // vehicle's position. Bedrock puts a rider at its vehicle's position plus this offset
    @Override
    public void setRiderSeatPosition(Vector3f position) {
        if (vehicle instanceof SlotDisplayEntity) {
            position = vehicle.getPosition().add(bedrockPosition().sub(this.position)).sub(vehicle.bedrockPosition());
        }
        super.setRiderSeatPosition(position);
    }

    private void updateSeat() {
        if (!(vehicle instanceof SlotDisplayEntity)) return;
        setRiderSeatPosition(Vector3f.ZERO);
        super.updateBedrockMetadata();
    }

    protected void respawn() {
        despawnEntity();
        spawnEntity();
    }

    @Override
    public void updateBedrockMetadata() {
        if ((valid || kind == Kind.HIDDEN) && kind != wantedKind()) {
            respawn();
            return;
        }

        if (kind == Kind.FALLING_BLOCK || kind == Kind.HIDDEN) {
            dropProperties();
        } else if (kind == Kind.BLOCK_CUBE) {
            pushCube();
        }
        // The seat depends on where both this display and its vehicle are drawn
        if (vehicle instanceof SlotDisplayEntity) setRiderSeatPosition(Vector3f.ZERO);
        super.updateBedrockMetadata();
        for (Entity passenger : passengers) {
            if (passenger instanceof ItemDisplayEntity display && display.isValid()) display.updateSeat();
        }
    }

    @Override
    public void updateBedrockEntityProperties() {
        if (kind == Kind.FALLING_BLOCK || kind == Kind.HIDDEN) {
            dropProperties();
            return;
        }
        super.updateBedrockEntityProperties();
    }

    /**
     * Re-reads the config and applies it, so /reload reaches displays already in view.
     */
    public void refresh() {
        if (!valid && kind != Kind.HIDDEN) return;
        if (kind != wantedKind()) {
            respawn();
            return;
        }
        if (kind == Kind.BLOCK_CUBE) {
            pushCube();
            updateBedrockEntityProperties();
        }
    }

    /**
     * Whether this display shows a vanilla block rather than an item.
     */
    protected boolean showsBlock() {
        return (itemBlock != null || itemShape != null) && !custom && !mappingApplied;
    }

    /**
     * The box and textures the block cube draws, or null if this block can't be drawn by it.
     */
    protected BlockShapes.Shape cubeShape() {
        return itemShape;
    }

    /**
     * Whether the cube's box is centred on the display (item models) rather than starting at its corner (blocks).
     */
    protected boolean centredModel() {
        return true;
    }

    /**
     * Where the model's origin sits in the display's transformed space, before the right rotation.
     */
    protected Vector3f cubeOrigin() {
        return modelOrigin();
    }

    protected BlockDefinition fallingBlockDefinition() {
        return itemBlock;
    }

    private Kind wantedKind() {
        if (!showsBlock()) return Kind.ITEM_RIG;
        if (needHide) return Kind.HIDDEN;

        // A falling block draws the exact block (stairs, fences, ...), but only one full block in size and upright
        Vector3f size = javaScale.mul(modelScale());
        boolean fullSize = Math.abs(size.getX() - 1) < 1e-3f && Math.abs(size.getY() - 1) < 1e-3f && Math.abs(size.getZ() - 1) < 1e-3f;
        Quaternionf rotation = orientation().mul(javaLeftRotation).mul(javaRightRotation).mul(fallingRotation()).normalize();
        boolean plain = fullSize && Math.abs(rotation.getW()) > 0.99999f && fallingBlockDefinition() != null;
        if (plain && !GeyserDisplayEntity.getExtension().getConfigManager().isForceCube()) return Kind.FALLING_BLOCK;

        if (cubeShape() != null) return Kind.BLOCK_CUBE;
        // Blocks without a model box (chests, signs, ...) can only be shown plain
        return plain ? Kind.FALLING_BLOCK : Kind.HIDDEN;
    }

    private void applyKind() {
        boolean itemDisplay = !(this instanceof BlockDisplayEntity);

        switch (kind) {
            case FALLING_BLOCK -> {
                bedrockDefinition(VanillaEntities.FALLING_BLOCK.defaultBedrockDefinition());
                metadata.put(EntityDataTypes.BLOCK, fallingBlockDefinition());
                // Without AI the client doesn't simulate the falling block, so it stays where the server puts it
                setFlag(EntityFlag.NO_AI, true);
                setFlag(EntityFlag.HAS_GRAVITY, false);
                setFlag(EntityFlag.INVISIBLE, false);
                metadata.put(EntityDataTypes.SCALE, 1f);
                dropProperties();
            }
            case HIDDEN -> dropProperties();
            case BLOCK_CUBE -> {
                bedrockDefinition(rigDefinition);
                setFlag(EntityFlag.INVISIBLE, false);
                metadata.put(EntityDataTypes.SCALE, 1f);
                if (itemDisplay) setInt(BLOCK_MODE, 1);
                pushCube();
            }
            case ITEM_RIG -> {
                bedrockDefinition(rigDefinition);
                if (itemDisplay) setInt(BLOCK_MODE, 0);
                // Rebuild the item rig from the latest Java transform. A respawned entity only gets the properties
                // sent with it, so send all of them rather than just the ones Java changed
                if (translationMetadata != null) super.setTranslation(translationMetadata);
                if (scaleMetadata != null) super.setScale(scaleMetadata);
                if (leftRotationMetadata != null) super.setLeftRotation(leftRotationMetadata);
                if (rightRotationMetadata != null) super.setRightRotation(rightRotationMetadata);
                pushTranslationProperties();
                pushRotationProperties();
                setFloat("geyser:s_x", scale.getX());
                setFloat("geyser:s_y", scale.getY());
                setFloat("geyser:s_z", scale.getZ());
                setFloat("geyser:s_q", qScale);
                if (!needHide) {
                    if (config != null && config.getBoolean("vanilla-scale")) {
                        applyScale();
                    } else {
                        metadata.put(EntityDataTypes.SCALE, 1f);
                    }
                }
            }
        }
    }

    @Override
    public void addAdditionalSpawnData(AddEntityPacket addEntityPacket) {
        // Falling blocks have no properties registered on the client
        if (kind == Kind.FALLING_BLOCK) {
            addEntityPacket.getProperties().getIntProperties().clear();
            addEntityPacket.getProperties().getFloatProperties().clear();
        }
    }

    private void dropProperties() {
        if (propertyManager == null) return;
        propertyManager.applyIntProperties(new ArrayList<>());
        propertyManager.applyFloatProperties(new ArrayList<>());
    }

    // Display entities never fall, whatever their no-gravity flag says
    @Override
    public void setGravity(BooleanEntityMetadata entityMetadata) {
    }

    @Override
    public void setTranslation(EntityMetadata<Vector3f, ?> entityMetadata) {
        translationMetadata = entityMetadata;
        javaTranslation = entityMetadata.getValue();
        if (kind == Kind.ITEM_RIG) super.setTranslation(entityMetadata);
    }

    @Override
    public void setScale(EntityMetadata<Vector3f, ?> entityMetadata) {
        scaleMetadata = entityMetadata;
        javaScale = entityMetadata.getValue();
        if (kind == Kind.ITEM_RIG) super.setScale(entityMetadata);
    }

    @Override
    public void setLeftRotation(EntityMetadata<Quaternionf, ?> entityMetadata) {
        leftRotationMetadata = entityMetadata;
        javaLeftRotation = entityMetadata.getValue();
        if (kind == Kind.ITEM_RIG) super.setLeftRotation(entityMetadata);
    }

    @Override
    public void setRightRotation(EntityMetadata<Quaternionf, ?> entityMetadata) {
        rightRotationMetadata = entityMetadata;
        javaRightRotation = entityMetadata.getValue();
        if (kind == Kind.ITEM_RIG) super.setRightRotation(entityMetadata);
    }

    // Java's FIXED billboard orientation: rotationYXZ(-yaw, pitch, 0)
    private Quaternionf orientation() {
        return Quaternionf.fromAngleDegAxis(-getYaw(), 0f, 1f, 0f).mul(Quaternionf.fromAngleDegAxis(getPitch(), 1f, 0f, 0f));
    }

    /**
     * Centre of the drawn block in the display's transformed space, before the right rotation is applied.
     * Java's item renderer turns the item half a turn and applies the item model's display transform.
     */
    protected Vector3f modelOrigin() {
        return HALF_TURN.rotate(modelTransform().translation());
    }

    /**
     * Rotation of the drawn block after the display's right rotation.
     */
    protected Quaternionf modelRotation() {
        return HALF_TURN.mul(modelTransform().rotation());
    }

    /**
     * Rotation that must be identity for a falling block to match. The item renderer's half turn is left out:
     * a falling block can't turn, and most blocks look the same either way.
     */
    protected Quaternionf fallingRotation() {
        return modelTransform().rotation();
    }

    protected float modelScale() {
        return modelTransform().scale();
    }

    private record ModelTransform(Vector3f translation, Quaternionf rotation, float scale) {
        // Java's ItemTransform: translate, then rotationXYZ, then scale
        static ModelTransform of(float tx, float ty, float tz, float rx, float ry, float rz, float scale) {
            Quaternionf rotation = Quaternionf.fromAngleDegAxis(rx, 1f, 0f, 0f)
                    .mul(Quaternionf.fromAngleDegAxis(ry, 0f, 1f, 0f))
                    .mul(Quaternionf.fromAngleDegAxis(rz, 0f, 0f, 1f));
            return new ModelTransform(Vector3f.from(tx / 16, ty / 16, tz / 16), rotation, scale);
        }
    }

    // Display transforms of vanilla's block/block.json, the parent of every block item model
    private ModelTransform modelTransform() {
        return switch (displayType) {
            case FIXED -> ModelTransform.of(0, 0, 0, 0, 0, 0, 0.5f);
            case GROUND -> ModelTransform.of(0, 3, 0, 0, 0, 0, 0.25f);
            case GUI -> ModelTransform.of(0, 0, 0, 30, 225, 0, 0.625f);
            case THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> ModelTransform.of(0, 2.5f, 0, 75, 45, 0, 0.375f);
            case FIRST_PERSON_RIGHT_HAND -> ModelTransform.of(0, 0, 0, 0, 45, 0, 0.4f);
            case FIRST_PERSON_LEFT_HAND -> ModelTransform.of(0, 0, 0, 0, 225, 0, 0.4f);
            case NONE, HEAD -> ModelTransform.of(0, 0, 0, 0, 0, 0, 1f);
        };
    }

    // Where Java draws the centre of the block, relative to the entity position:
    // orientation * (translation + leftRotation * scale * rightRotation * modelOrigin)
    private Vector3f blockCenter() {
        Vector3f origin = javaRightRotation.rotate(modelOrigin()).mul(javaScale).mul(modelScale());
        return orientation().rotate(javaTranslation.add(javaLeftRotation.rotate(origin)));
    }

    /**
     * Sends the Java transform and the block's shape to the block cube. Java draws a point m of the model at
     * orientation * (translation + leftRotation * scale * rightRotation * (origin + modelRotation * modelScale * m)).
     * The bones apply position, leftRotation, scale, rightRotation and the box in that order; the entity's own yaw and
     * pitch are folded into the left rotation, so the Bedrock entity itself is never rotated.
     */
    private void pushCube() {
        BlockShapes.Shape shape = cubeShape();
        if (shape == null) return;

        Vector3f origin = javaLeftRotation.rotate(javaRightRotation.rotate(cubeOrigin()).mul(javaScale));
        Vector3f position = orientation().rotate(javaTranslation.add(origin));
        // Bedrock animation positions are in pixels, with Z flipped against Java's world axes
        setFloat("geyser:t_x", position.getX() * 16);
        setFloat("geyser:t_y", position.getY() * 16);
        setFloat("geyser:t_z", -position.getZ() * 16);

        Vector3f left = toBedrockEuler(orientation().mul(javaLeftRotation));
        setFloat("geyser:r_x", left.getX());
        setFloat("geyser:r_y", left.getY());
        setFloat("geyser:r_z", left.getZ());

        Vector3f scale = javaScale.mul(modelScale());
        setFloat("geyser:s_x", scale.getX());
        setFloat("geyser:s_y", scale.getY());
        setFloat("geyser:s_z", scale.getZ());

        Vector3f right = toBedrockEuler(javaRightRotation.mul(modelRotation()));
        setFloat(RIGHT_ROTATION_X, right.getX());
        setFloat(RIGHT_ROTATION_Y, right.getY());
        setFloat(RIGHT_ROTATION_Z, right.getZ());

        // Item models are centred on the display, blocks start at its corner
        float offset = centredModel() ? 8 : 0;
        for (int i = 0; i < 3; i++) {
            setFloat(BOX[i], shape.from()[i] - offset);
            setFloat(BOX[i + 3], shape.to()[i] - offset);
        }
        for (int i = 0; i < 6; i++) setInt(FACES[i], shape.faces()[i]);
        setInt(FLAGS, (shape.translucent() ? 1 : 0) | (centredModel() ? 2 : 0));
    }

    // Bedrock bones rotate Z * Y * X in model space, with the X and Y angles negated
    private Vector3f toBedrockEuler(Quaternionf javaRotation) {
        Quaternionf model = MODEL_SPACE.mul(javaRotation).mul(MODEL_SPACE.invert());
        Vector3f euler = toEulerZYX(model);
        return Vector3f.from(-euler.getX(), -euler.getY(), euler.getZ());
    }

    private void setFloat(String id, float value) {
        propertyManager.addProperty(new FloatProperty(Identifier.of(id), MAX_VALUE, MIN_VALUE, 0F), value);
    }

    private void setInt(String id, int value) {
        propertyManager.addProperty(new IntProperty(Identifier.of(id), MAX_VALUE, MIN_VALUE, 0), value);
    }

    // The block kinds own all translation; the item rig's offsets don't apply to them
    @Override
    protected void pushTranslationProperties() {
        if (kind != Kind.ITEM_RIG) return;
        super.pushTranslationProperties();
    }

    @Override
    public Vector3f bedrockPosition() {
        // Called from the super constructor, before field initializers have run
        if (javaTranslation == null || javaScale == null || javaLeftRotation == null || javaRightRotation == null) return super.bedrockPosition();

        if (kind == Kind.BLOCK_CUBE) return position;
        if (kind == Kind.FALLING_BLOCK) {
            // Bedrock draws a falling block one full block in size, from its bottom centre
            return position.add(blockCenter()).sub(0, 0.5f, 0);
        }

        if (config == null) return super.bedrockPosition();
        return super.bedrockPosition().up((float) config.getDouble("y-offset"));
    }

    public void setDisplayedItem(EntityMetadata<ItemStack, ?> entityMetadata) {
        setDisplayedItem(entityMetadata.getValue());
    }

    protected void setDisplayedItem(ItemStack stack) {
        if (stack == null) {
            this.item = ItemData.AIR;
            this.itemBlock = null;
            this.itemShape = null;
            setInvisible(true);
            return;
        }

        config = GeyserDisplayEntity.getExtension().getConfigManager().getGeneralConfig();

        ItemData item = ItemTranslator.translateToBedrock(session, stack);
        this.item = item;

        // Read the dye straight from the components: Geyser's internal item classes
        // (DyeableArmorItem, DyeItem, ...) get renamed between builds and break the extension
        this.color = null;
        DataComponents data = stack.getDataComponentsPatch();
        if (data != null) {
            Integer dyed = data.get(DataComponentTypes.DYED_COLOR);
            if (dyed != null) {
                this.color = Byte.parseByte(String.valueOf(getColor(dyed)));
            }
        }

        String type = session.getItemMappings().getMapping(stack.getId()).getJavaItem().javaKey().asString();

        boolean mappingApplied = false;
        for (FileConfiguration mappingsConfig : GeyserDisplayEntity.getExtension().getConfigManager().getConfigMappingsCache().values()) {
            if (mappingsConfig == null) continue;

            for (Object mappingKey : mappingsConfig.getRootNode().childrenMap().keySet()) {
                String mappingString = mappingKey.toString();

                FileConfiguration mappingConfig = mappingsConfig.getConfigurationSection(mappingString);
                if (mappingConfig == null) continue;
                if (!mappingConfig.getString("type").equals(type)) continue;

                boolean applied;
                if (mappingConfig.contains("item-model")) {
                    applied = applyJavaItemModel(stack, mappingConfig);
                } else if (mappingConfig.contains("model-data")) {
                    applied = applyLegacyModelData(stack, mappingConfig);
                } else {
                    applied = applyModernItemModels(item, mappingConfig);
                }

                if (applied) {
                    mappingApplied = true;
                    if (GeyserDisplayEntity.getExtension().getConfigManager().getConfig().getBoolean("settings.debug.per-player-load-mappings")) GeyserDisplayEntity.getExtension().logger().info("Loading Mappings: " + mappingString + " - " + mappingConfig.getString("item-identifier"));
                    break;
                }
            }

            if (mappingApplied) break;
        }
        this.mappingApplied = mappingApplied;

        if (!item.getDefinition().getIdentifier().startsWith("minecraft:")) {
            custom = true;
            if (color != null) {
                getMetadata().put(EntityDataTypes.COLOR, color);
            }
        } else {
            custom = false;
        }

        // HIDE_TYPES check only if stack is present (it is)
        ItemMapping itemMapping = session.getItemMappings().getMapping(stack);
        this.itemBlock = itemMapping.isBlock() ? itemMapping.getBedrockBlockDefinition() : null;
        String javaID = itemMapping.getJavaItem().javaKey().asString();
        this.itemShape = BlockShapes.forItem(javaID);
        boolean wasHiddenByType = needHide;

        ConfigManager cfg = GeyserDisplayEntity.getExtension().getConfigManager();
        boolean hiddenByType = cfg.getHideTypes().contains(javaID);
        boolean forceHiddenCustomType = cfg.getHideCustomTypes().contains(javaID);
        // A plain block item is drawn as the block itself, so only hide it when it carries a custom model
        boolean plainBlock = itemBlock != null && (data == null
                || (data.get(DataComponentTypes.ITEM_MODEL) == null && data.get(DataComponentTypes.CUSTOM_MODEL_DATA) == null));
        boolean unmappedVanilla = hidesUnmappedVanilla(cfg) && !custom && !mappingApplied
                && !(plainBlock && cfg.isShowPlainVanillaBlocks());

        boolean hidden = (hiddenByType && !custom) || forceHiddenCustomType || unmappedVanilla;

        if (cfg.isLogDisplays()) {
            GeyserDisplayEntity.getExtension().logger().info(getClass().getSimpleName() + " java=" + javaID
                    + " bedrock=" + item.getDefinition().getIdentifier()
                    + " custom=" + custom + " mapped=" + mappingApplied
                    + " -> " + (hidden ? "HIDDEN" : "SHOWN"));
        }

        if (hidden) {
            setInvisible(true);
            needHide = true;
            this.metadata.put(EntityDataTypes.SCALE, 0f);
        } else {
            needHide = false;
            if (wasHiddenByType) {
                setInvisible(false);

                if (config != null && config.getBoolean("vanilla-scale")) {
                    applyScale();
                } else {
                    this.metadata.put(EntityDataTypes.SCALE, 1f);
                }
            }
        }

        updateMainHand(session);
    }

    protected boolean hidesUnmappedVanilla(ConfigManager cfg) {
        return cfg.isHideUnmappedVanilla();
    }

    private boolean applyLegacyModelData(ItemStack item, FileConfiguration mappingConfig) {
        CustomModelData modelData = null;
        DataComponents components = item.getDataComponentsPatch();

        if (components != null) modelData = components.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (mappingConfig.getInt("model-data") == -1) return entityApplyDisplayConfig(mappingConfig);
        if (modelData == null) return false;

        if (Math.abs(mappingConfig.getInt("model-data") - modelData.floats().get(0)) < 0.5) return entityApplyDisplayConfig(mappingConfig);

        return false;
    }

    // Matches the Java item_model component, which stays the same across Bedrock pack rebuilds unlike the
    // generated Bedrock identifier
    private boolean applyJavaItemModel(ItemStack item, FileConfiguration mappingConfig) {
        DataComponents components = item.getDataComponentsPatch();
        Key itemModel = components == null ? null : components.get(DataComponentTypes.ITEM_MODEL);
        if (itemModel == null || !itemModel.asString().equals(mappingConfig.getString("item-model"))) return false;

        return entityApplyDisplayConfig(mappingConfig);
    }

    private boolean applyModernItemModels(ItemData itemData, FileConfiguration mappingConfig) {
        String itemBedrockIdentifier = itemData.getDefinition().getIdentifier().replace("geyser_custom:", "");
        String mappingIdentifier = mappingConfig.getString("item-identifier");

        if (mappingIdentifier.equalsIgnoreCase("none")) return entityApplyDisplayConfig(mappingConfig);
        if (mappingIdentifier.equalsIgnoreCase(itemBedrockIdentifier)) return entityApplyDisplayConfig(mappingConfig);

        return false;
    }

    private boolean entityApplyDisplayConfig(FileConfiguration mappingConfig) {
        FileConfiguration mappingOptions = mappingConfig.getConfigurationSection("displayentityoptions");
        if (mappingOptions == null) {
            // Legacy compatibility for pre-migration config.yml format
            mappingOptions = mappingConfig.getConfigurationSection("options");
        }

        if (mappingOptions != null) {
            config = mappingOptions;
        } else if (config == null) {
            return false;
        }

        if (config.getBoolean("vanilla-scale")) applyScale();
        applyMappingDisplayTransform(config);

        if (valid) {
            moveAbsoluteRaw(position, yaw, pitch, headYaw, onGround, true);
        }
        return true;
    }

    private void applyMappingDisplayTransform(FileConfiguration cfg) {
        Vector3f rot = readVec3(cfg, "rotation");
        Vector3f trans = readVec3(cfg, "translation");
        if (rot == null && trans == null) return;
        applyMappingTransform(
                rot != null ? rot : Vector3f.from(0, 0, 0),
                trans != null ? trans : Vector3f.from(0, 0, 0));
    }

    protected static Vector3f readVec3(FileConfiguration cfg, String key) {
        if (cfg == null || !cfg.contains(key)) return null;
        List<Float> v = cfg.getFloatList(key);
        if (v.size() < 3) return null;
        return Vector3f.from(v.get(0), v.get(1), v.get(2));
    }

    @Override
    protected void applyScale() {
        if (needHide) {
            metadata.put(EntityDataTypes.SCALE, 0f);
        } else {
            super.applyScale();
        }
    }

    @Override
    public void updateMainHand(GeyserSession session) {
        // Falling blocks and the block cube draw the block themselves
        if (!valid || kind == Kind.FALLING_BLOCK || kind == Kind.BLOCK_CUBE) return;

        ItemData helmet = ItemData.AIR; // TODO
        ItemData chest = item;

        if (custom && (config == null || !config.getBoolean("hand"))) {
            MobArmorEquipmentPacket armorEquipmentPacket = new MobArmorEquipmentPacket();
            armorEquipmentPacket.setRuntimeEntityId(geyserId);
            armorEquipmentPacket.setHelmet(helmet);
            armorEquipmentPacket.setBoots(ItemData.AIR);
            armorEquipmentPacket.setChestplate(chest);
            armorEquipmentPacket.setLeggings(ItemData.AIR);
            armorEquipmentPacket.setBody(ItemData.AIR);

            session.sendUpstreamPacket(armorEquipmentPacket);
        } else {
            MobEquipmentPacket handPacket = new MobEquipmentPacket();
            handPacket.setRuntimeEntityId(geyserId);
            handPacket.setItem(item);
            handPacket.setHotbarSlot(-1);
            handPacket.setInventorySlot(0);
            handPacket.setContainerId(ContainerId.INVENTORY);

            session.sendUpstreamPacket(handPacket);
        }
    }

    public void setDisplayType(ByteEntityMetadata entityMetadata) {
        int i = entityMetadata.getPrimitiveValue();
        displayType = DisplayType.values()[i];
    }

    private static int getColor(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        double[] colorLab = DeltaUtils.rgbToLab(r, g, b);

        List<int[]> colors = Arrays.asList(
                new int[]{249, 255, 254},    // 0: White
                new int[]{249, 128, 29},     // 1: Orange
                new int[]{199, 78, 189},     // 2: Magenta
                new int[]{58, 179, 218},     // 3: Light Blue
                new int[]{254, 216, 61},     // 4: Yellow
                new int[]{128, 199, 31},     // 5: Lime
                new int[]{243, 139, 170},    // 6: Pink
                new int[]{71, 79, 82},       // 7: Gray
                new int[]{159, 157, 151},    // 8: Light Gray
                new int[]{22, 156, 156},     // 9: Cyan
                new int[]{137, 50, 184},     // 10: Purple
                new int[]{60, 68, 170},      // 11: Blue
                new int[]{131, 84, 50},      // 12: Brown
                new int[]{94, 124, 22},      // 13: Green
                new int[]{176, 46, 38},      // 14: Red
                new int[]{29, 29, 33}        // 15: Black
        );

        int closestColorIndex = -1;
        double minDeltaE = Double.MAX_VALUE;

        for (int i = 0; i < colors.size(); i++) {
            int[] rgb = colors.get(i);
            double[] lab = DeltaUtils.rgbToLab(rgb[0], rgb[1], rgb[2]);
            double deltaE = DeltaUtils.calculateDeltaE(colorLab, lab);
            if (deltaE < minDeltaE) {
                minDeltaE = deltaE;
                closestColorIndex = i;
            }
        }

        return closestColorIndex;
    }

    @Override
    public void moveAbsoluteRaw(Vector3f position, float yaw, float pitch, float headYaw, boolean isOnGround, boolean teleported) {
        setPosition(position);
        setYaw(yaw);
        setPitch(pitch);
        setHeadYaw(headYaw);
        setOnGround(isOnGround);

        MoveEntityAbsolutePacket moveEntityPacket = new MoveEntityAbsolutePacket();
        moveEntityPacket.setRuntimeEntityId(geyserId);
        moveEntityPacket.setPosition(bedrockPosition());
        moveEntityPacket.setRotation(bedrockRotation());
        moveEntityPacket.setOnGround(isOnGround);
        moveEntityPacket.setTeleported(teleported);

        session.sendUpstreamPacket(moveEntityPacket);

        // The entity's yaw and pitch are part of the block kinds' transform
        if (kind != null && kind != Kind.ITEM_RIG && valid && (yaw != lastYaw || pitch != lastPitch)) {
            lastYaw = yaw;
            lastPitch = pitch;
            updateBedrockMetadata();
        }
    }

    @Override
    public void moveRelativeRaw(double relX, double relY, double relZ, float yaw, float pitch, float headYaw, boolean isOnGround) {
        if (this instanceof ClientVehicle clientVehicle) {
            if (clientVehicle.shouldSimulateMovement()) return;

            clientVehicle.getVehicleComponent().moveRelative(relX, relY, relZ);
        }

        this.position = Vector3f.from(this.position.getX() + relX, this.position.getY() + relY, this.position.getZ() + relZ);
        setOnGround(isOnGround);

        if (pitch != this.pitch) {
            this.pitch = pitch;
        }

        if (yaw != this.yaw) {
            this.yaw = yaw;
        }

        if (headYaw != this.headYaw) {
            this.headYaw = headYaw;
        }

        moveAbsoluteRaw(this.position, this.yaw, this.pitch, this.headYaw, isOnGround, false);
    }

    @Override
    public Vector3f bedrockRotation() {
        // The block rig folds the entity's rotation into its bones; falling blocks ignore rotation
        if (kind != null && kind != Kind.ITEM_RIG) return Vector3f.ZERO;

        Quaternionf combined = Quaternionf.from(lastLeft).mul(lastRight).normalize();
        Vector3f fwd = combined.rotate(0f, 0f, 1f);
        float yawDeg = (float) Math.toDegrees(Math.atan2(-fwd.getX(), fwd.getZ()));
        float pitchDeg = (float) Math.toDegrees(Math.asin(MathUtils.clamp(fwd.getY(), -1f, 1f)));
        yawDeg += getYaw();
        yawDeg = MathUtils.wrapDegrees(yawDeg);
        return Vector3f.from(pitchDeg, yawDeg, yawDeg);
    }
}
