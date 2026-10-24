package me.geyserextensionists.geyserdisplayentity.entity;

import me.geyserextensionists.geyserdisplayentity.managers.ConfigManager;
import org.cloudburstmc.math.imaginary.Quaternionf;
import org.cloudburstmc.math.vector.Vector3f;
import org.geysermc.geyser.entity.spawn.EntitySpawnContext;
import org.geysermc.geyser.item.Items;
import org.geysermc.geyser.item.type.Item;
import org.geysermc.geyser.level.block.type.BlockState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.IntEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

/**
 * Renders a block display by equipping the block's item, the same way item displays are rendered.
 */
public class BlockDisplayEntity extends ItemDisplayEntity {

    private static final Vector3f BLOCK_CENTER = Vector3f.from(0.5f, 0.5f, 0.5f);

    public BlockDisplayEntity(EntitySpawnContext entitySpawnContext) {
        super(entitySpawnContext);
    }

    public void setDisplayedBlockState(IntEntityMetadata blockState) {
        Item item = BlockState.of(blockState.getPrimitiveValue()).block().asItem();
        // Blocks without an item form (air, fire, water, ...) have nothing to equip
        setDisplayedItem(item == Items.AIR ? null : new ItemStack(item.javaId()));
    }

    // Block displays exist to show vanilla blocks, so never hide them for lacking a mapping
    @Override
    protected boolean hidesUnmappedVanilla(ConfigManager cfg) {
        return false;
    }

    // Java renders the block model from its corner (0..1 on each axis), while the equipped item
    // is centred on the origin. Shift by the transformed block centre: leftRotation * scale * rightRotation * (0.5, 0.5, 0.5)
    @Override
    protected Vector3f modelOffset() {
        // Called from initializeMetadata, before field initializers have run
        if (lastLeft == null || lastRight == null || scale == null) return BLOCK_CENTER;

        Vector3f center = lastRight.rotate(BLOCK_CENTER).mul(scale);
        return lastLeft.rotate(center);
    }

    @Override
    public void setScale(EntityMetadata<Vector3f, ?> entityMetadata) {
        super.setScale(entityMetadata);
        pushTranslationProperties();
    }

    @Override
    public void setLeftRotation(EntityMetadata<Quaternionf, ?> entityMetadata) {
        super.setLeftRotation(entityMetadata);
        pushTranslationProperties();
    }

    @Override
    public void setRightRotation(EntityMetadata<Quaternionf, ?> entityMetadata) {
        super.setRightRotation(entityMetadata);
        pushTranslationProperties();
    }
}
