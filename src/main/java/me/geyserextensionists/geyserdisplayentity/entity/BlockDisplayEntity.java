package me.geyserextensionists.geyserdisplayentity.entity;

import me.geyserextensionists.geyserdisplayentity.managers.ConfigManager;
import org.cloudburstmc.math.imaginary.Quaternionf;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.geysermc.geyser.entity.spawn.EntitySpawnContext;
import org.geysermc.geyser.item.Items;
import org.geysermc.geyser.item.type.Item;
import org.geysermc.geyser.level.block.property.Properties;
import org.geysermc.geyser.level.block.property.Property;
import org.geysermc.geyser.level.block.type.BlockState;
import org.geysermc.geyser.level.physics.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.IntEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

/**
 * A block display, drawn through {@link ItemDisplayEntity}'s block kinds: a falling block of the exact block state
 * when unrotated and evenly scaled, otherwise the block's item held by {@code geyser:block_display}.
 */
public class BlockDisplayEntity extends ItemDisplayEntity {

    private static final Vector3f BLOCK_CENTER = Vector3f.from(0.5f, 0.5f, 0.5f);

    private BlockDefinition block;
    private boolean hasItem;

    // Bedrock spawn waits for the first metadata, so the entity is created as the right kind straight away
    private boolean metadataReceived;
    private boolean spawnPending;

    public BlockDisplayEntity(EntitySpawnContext entitySpawnContext) {
        super(entitySpawnContext);
    }

    @Override
    public void spawnEntity() {
        if (!metadataReceived) {
            spawnPending = true;
            return;
        }
        super.spawnEntity();
    }

    @Override
    public void updateBedrockMetadata() {
        metadataReceived = true;
        if (spawnPending) {
            spawnPending = false;
            spawnEntity();
            return;
        }
        super.updateBedrockMetadata();
    }

    public void setDisplayedBlockState(IntEntityMetadata blockState) {
        int javaId = blockState.getPrimitiveValue();
        BlockState state = BlockState.of(javaId);
        // Bedrock draws a falling block half a turn from the same block placed in the world, whatever the entity's
        // rotation, so send the state turned half a turn to cancel it out
        block = session.getBlockMappings().getBedrockBlock(halfTurn(state).javaId());

        Item item = state.block().asItem();
        hasItem = item != Items.AIR;
        setDisplayedItem(hasItem ? new ItemStack(item.javaId()) : null);
        // Blocks without an item form (air, fire, water, ...) are always falling blocks, which draw the block themselves
        if (!hasItem) setInvisible(false);
    }

    // Java's 180 degree block rotation: horizontal facings reverse, 16-step rotations move by 8, and
    // north/south and east/west connections (fences, walls, panes, ...) swap
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState halfTurn(BlockState state) {
        Property<?>[] keys = state.block().propertyKeys();
        if (keys == null) return state;

        BlockState turned = state;
        for (Property key : keys) {
            Comparable value = state.getValue(key);
            if (value instanceof Direction direction && direction.isHorizontal()) {
                if (key.indexOf(direction.reversed()) >= 0) turned = turned.withValue(key, direction.reversed());
            } else if (key == Properties.ROTATION_16) {
                turned = turned.withValue(key, ((Integer) value + 8) % 16);
            } else {
                String opposite = switch (key.name()) {
                    case "north" -> "south";
                    case "south" -> "north";
                    case "east" -> "west";
                    case "west" -> "east";
                    default -> null;
                };
                if (opposite == null) continue;
                for (Property other : keys) {
                    if (other.name().equals(opposite)) turned = turned.withValue(other, value);
                }
            }
        }
        return turned;
    }

    @Override
    protected boolean showsBlock() {
        return true;
    }

    @Override
    protected boolean hasHeldItem() {
        return hasItem;
    }

    @Override
    protected BlockDefinition fallingBlockDefinition() {
        return block;
    }

    // Java draws the block model from its corner (0..1 on each axis), with no item transform
    @Override
    protected Vector3f modelOrigin() {
        return BLOCK_CENTER;
    }

    @Override
    protected Quaternionf modelRotation() {
        return Quaternionf.IDENTITY;
    }

    @Override
    protected Quaternionf fallingRotation() {
        return Quaternionf.IDENTITY;
    }

    @Override
    protected float modelScale() {
        return 1f;
    }

    // Applied up front, so the falling block is frozen from its first spawn
    @Override
    public void initializeMetadata() {
        super.initializeMetadata();
        setFlag(EntityFlag.NO_AI, true);
        setFlag(EntityFlag.HAS_GRAVITY, false);
    }

    // Block displays exist to show vanilla blocks, so never hide them for lacking a mapping
    @Override
    protected boolean hidesUnmappedVanilla(ConfigManager cfg) {
        return false;
    }
}
