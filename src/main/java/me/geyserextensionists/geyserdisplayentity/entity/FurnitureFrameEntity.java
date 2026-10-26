package me.geyserextensionists.geyserdisplayentity.entity;

import me.geyserextensionists.geyserdisplayentity.GeyserDisplayEntity;
import me.geyserextensionists.geyserdisplayentity.util.DisplayTransforms;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.math.imaginary.Quaternionf;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.BlockEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobArmorEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.geysermc.geyser.api.util.Identifier;
import org.geysermc.geyser.entity.properties.GeyserEntityPropertyManager;
import org.geysermc.geyser.entity.properties.type.FloatProperty;
import org.geysermc.geyser.entity.spawn.EntitySpawnContext;
import org.geysermc.geyser.entity.type.ItemFrameEntity;
import org.geysermc.geyser.translator.item.ItemTranslator;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.IntEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;

import java.util.EnumMap;

import static me.geyserextensionists.geyserdisplayentity.GeyserDisplayEntity.MAX_VALUE;
import static me.geyserextensionists.geyserdisplayentity.GeyserDisplayEntity.MIN_VALUE;

/**
 * An item frame that, when holding a custom item with a known "fixed" display transform (furniture placed in item
 * frames), draws the item's 3D model like Java instead of Geyser's frame block, which can only
 * show a flat icon. The model is the custom item in the chest slot of a {@code geyser:item_display}, like custom
 * items on item displays, spawned under the frame's own id so hitting it reaches the Java frame.
 * <p>
 * Bedrock can place, scale and turn that entity about the vertical axis only, so this applies when Java's result
 * is upright, like furniture in floor frames; anything else keeps the frame block.
 */
public class FurnitureFrameEntity extends ItemFrameEntity {

    private static final Quaternionf HALF_TURN = Quaternionf.fromAngleDegAxis(180f, 0f, 1f, 0f);

    private record Placement(Vector3f position, float yaw, float scale, ItemStack item, boolean border) {
    }

    private final Vector3i frameBlock;
    private Direction direction = Direction.SOUTH;
    private int itemRotation;
    private ItemStack item;

    private boolean frameRemoved;
    private boolean borderShown;
    private Placement drawn;
    private String lastReason;

    public FurnitureFrameEntity(EntitySpawnContext context) {
        super(context);
        // Where Geyser puts the frame block
        frameBlock = position().toInt();
    }

    @Override
    public void setDirection(Direction direction) {
        super.setDirection(direction);
        this.direction = direction;
    }

    @Override
    public void setItemInFrame(EntityMetadata<ItemStack, ?> entityMetadata) {
        super.setItemInFrame(entityMetadata);
        item = entityMetadata.getValue();
    }

    @Override
    public void setItemRotation(IntEntityMetadata entityMetadata) {
        super.setItemRotation(entityMetadata);
        itemRotation = entityMetadata.getPrimitiveValue();
    }

    @Override
    public void updateBlock(boolean force) {
        Placement placement = placement();
        if (placement == null) {
            removeModel();
            // Put the frame back if a model replaced it
            super.updateBlock(force || frameRemoved);
            frameRemoved = false;
            return;
        }

        if (!frameRemoved || force || borderShown != placement.border()) {
            sendFrameBlock(placement.border());
            frameRemoved = true;
            borderShown = placement.border();
        }
        if (!placement.equals(drawn)) {
            removeModel();
            spawnModel(placement);
        }
    }

    // Visible frames keep an empty frame under the model, like Java draws the frame behind the item; invisible ones none
    private void sendFrameBlock(boolean border) {
        boolean glow = getJavaDefinition().is(EntityType.GLOW_ITEM_FRAME);
        UpdateBlockPacket block = new UpdateBlockPacket();
        block.setDataLayer(0);
        block.setBlockPosition(frameBlock);
        if (border) {
            block.setDefinition(session.getBlockMappings().getItemFrame(NbtMap.builder()
                    .putString("name", glow ? "minecraft:glow_frame" : "minecraft:frame")
                    .putCompound("states", NbtMap.builder()
                            .putInt("facing_direction", direction.ordinal())
                            .putByte("item_frame_map_bit", (byte) 0)
                            .putByte("item_frame_photo_bit", (byte) 0)
                            .build())
                    .build()));
        } else {
            block.setDefinition(session.getBlockMappings().getBedrockAir());
        }
        block.getFlags().add(UpdateBlockPacket.Flag.PRIORITY);
        block.getFlags().add(UpdateBlockPacket.Flag.NETWORK);
        session.sendUpstreamPacket(block);
        if (!border) return;

        BlockEntityDataPacket empty = new BlockEntityDataPacket();
        empty.setBlockPosition(frameBlock);
        empty.setData(NbtMap.builder()
                .putInt("x", frameBlock.getX())
                .putInt("y", frameBlock.getY())
                .putInt("z", frameBlock.getZ())
                .putByte("isMovable", (byte) 1)
                .putString("id", glow ? "GlowItemFrame" : "ItemFrame")
                .build());
        session.sendUpstreamPacket(empty);
    }

    @Override
    public void despawnEntity() {
        removeModel();
        super.despawnEntity();
    }

    // Geyser sends moves to geyserId, which is the model while one is drawn: with the frame's own pitch (-90 on the
    // floor) that tips it over. Keep the new position and place the model again instead.
    @Override
    public void moveAbsoluteRaw(Vector3f position, float yaw, float pitch, float headYaw, boolean isOnGround, boolean teleported) {
        if (drawn == null) {
            super.moveAbsoluteRaw(position, yaw, pitch, headYaw, isOnGround, teleported);
            return;
        }
        setPosition(position);
        setYaw(yaw);
        setPitch(pitch);
        setHeadYaw(headYaw);
        setOnGround(isOnGround);
        updateBlock(false);
    }

    @Override
    public void moveRelativeRaw(double relX, double relY, double relZ, float yaw, float pitch, float headYaw, boolean isOnGround) {
        moveAbsoluteRaw(position().add(relX, relY, relZ), yaw, pitch, headYaw, isOnGround, false);
    }

    /**
     * Where Bedrock has to draw the model to match Java, or null if this frame keeps Geyser's frame block.
     * <p>
     * Java draws a model point m (in blocks) at
     * centre + frame * (back + spin * 0.5 * (t + R * S * (m - 0.5))), with frame the facing rotation, back the
     * offset of the item from the frame (0.5 blocks in invisible frames, 0.4375 in visible ones), spin the item
     * rotation and t/R/S the item's fixed transform.
     * Bedrock draws the chest attachable at position + scale * yaw * half turn * (m - 0.5) + (0, scale / 2, 0).
     */
    private Placement placement() {
        if (item == null) return skip("empty");
        boolean visible = !getFlag(EntityFlag.INVISIBLE);
        DataComponents components = item.getDataComponentsPatch();
        Key model = components == null ? null : components.get(DataComponentTypes.ITEM_MODEL);
        if (model == null) return skip("item without item_model");
        DisplayTransforms.Transform fixed = DisplayTransforms.get(model.asString());
        if (fixed == null) return skip(model.asString() + " not in display_transforms.json");

        Vector3f s = fixed.scale();
        if (Math.abs(s.getX() - s.getY()) > 1e-3f || Math.abs(s.getY() - s.getZ()) > 1e-3f) return skip(model.asString() + " scales unevenly");
        float scale = 0.5f * s.getX();

        Quaternionf frame = frameRotation();
        Quaternionf spin = Quaternionf.fromAngleDegAxis(itemRotation * 45f, 0f, 0f, 1f);
        Quaternionf rotation = frame.mul(spin).mul(fixed.rotation());

        // Bedrock's entity can only turn about the vertical axis on top of the half turn of the attachable
        Quaternionf yaw = rotation.mul(HALF_TURN.invert()).normalize();
        Vector3f up = yaw.rotate(Vector3f.UP);
        if (up.getY() < 0.999f) return skip(model.asString() + " isn't upright facing " + direction);
        Vector3f forward = yaw.rotate(Vector3f.from(0, 0, 1));
        float bedrockYaw = (float) -Math.toDegrees(Math.atan2(forward.getX(), forward.getZ()));

        Vector3f centre = position().add(step().mul(0.46875f));
        Vector3f offset = frame.rotate(Vector3f.from(0, 0, visible ? 0.4375f : 0.5f).add(spin.rotate(fixed.translation().mul(0.5f))));
        Vector3f position = centre.add(offset).sub(0, scale / 2, 0);
        log(model.asString() + " drawn at " + position + ", yaw " + bedrockYaw + ", scale " + scale);
        return new Placement(position, bedrockYaw, scale, item, visible);
    }

    private Placement skip(String reason) {
        log("kept as a frame: " + reason);
        return null;
    }

    // With settings.debug.log-displays, says once per change why a frame is drawn as it is
    private void log(String reason) {
        if (reason.equals(lastReason) || !GeyserDisplayEntity.getExtension().getConfigManager().isLogDisplays()) return;
        lastReason = reason;
        GeyserDisplayEntity.getExtension().logger().info("Item frame at " + frameBlock + " (" + direction + "): " + reason);
    }

    // Java's ItemFrameRenderer: horizontal frames turn by 180 - yaw, floor and ceiling frames tilt by -/+90 then half turn
    private Quaternionf frameRotation() {
        return switch (direction) {
            case UP -> Quaternionf.fromAngleDegAxis(-90f, 1f, 0f, 0f).mul(HALF_TURN);
            case DOWN -> Quaternionf.fromAngleDegAxis(90f, 1f, 0f, 0f).mul(HALF_TURN);
            case SOUTH -> Quaternionf.fromAngleDegAxis(180f, 0f, 1f, 0f);
            case WEST -> Quaternionf.fromAngleDegAxis(90f, 0f, 1f, 0f);
            case NORTH -> Quaternionf.IDENTITY;
            case EAST -> Quaternionf.fromAngleDegAxis(-90f, 0f, 1f, 0f);
        };
    }

    private Vector3f step() {
        return switch (direction) {
            case UP -> Vector3f.UP;
            case DOWN -> Vector3f.from(0, -1, 0);
            case SOUTH -> Vector3f.from(0, 0, 1);
            case NORTH -> Vector3f.from(0, 0, -1);
            case EAST -> Vector3f.from(1, 0, 0);
            case WEST -> Vector3f.from(-1, 0, 0);
        };
    }

    private void spawnModel(Placement placement) {
        AddEntityPacket add = new AddEntityPacket();
        add.setIdentifier("geyser:item_display");
        add.setRuntimeEntityId(geyserId);
        add.setUniqueEntityId(geyserId);
        add.setPosition(placement.position());
        add.setMotion(Vector3f.ZERO);
        add.setRotation(Vector2f.from(0, placement.yaw()));
        add.setHeadRotation(placement.yaw());
        add.setBodyRotation(placement.yaw());
        add.getMetadata().putFlags(new EnumMap<>(EntityFlag.class));
        add.getMetadata().put(EntityDataTypes.SCALE, placement.scale());

        // The item rig's own scale stays 1: the entity scale above is what reaches the attachable
        GeyserEntityPropertyManager properties = new GeyserEntityPropertyManager(GeyserDisplayEntity.itemDisplayDefinition().registeredProperties());
        for (String id : new String[]{"geyser:s_x", "geyser:s_y", "geyser:s_z", "geyser:s_q"}) {
            properties.addProperty(new FloatProperty(Identifier.of(id), MAX_VALUE, MIN_VALUE, 0F), 1f);
        }
        properties.applyIntProperties(add.getProperties().getIntProperties());
        properties.applyFloatProperties(add.getProperties().getFloatProperties());
        session.sendUpstreamPacket(add);

        MobArmorEquipmentPacket equipment = new MobArmorEquipmentPacket();
        equipment.setRuntimeEntityId(geyserId);
        equipment.setHelmet(ItemData.AIR);
        equipment.setChestplate(ItemTranslator.translateToBedrock(session, placement.item()));
        equipment.setLeggings(ItemData.AIR);
        equipment.setBoots(ItemData.AIR);
        equipment.setBody(ItemData.AIR);
        session.sendUpstreamPacket(equipment);

        drawn = placement;
    }

    private void removeModel() {
        if (drawn == null) return;
        RemoveEntityPacket remove = new RemoveEntityPacket();
        remove.setUniqueEntityId(geyserId);
        session.sendUpstreamPacket(remove);
        drawn = null;
    }
}
