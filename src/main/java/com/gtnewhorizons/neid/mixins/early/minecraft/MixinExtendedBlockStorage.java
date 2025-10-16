package com.gtnewhorizons.neid.mixins.early.minecraft;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnewhorizons.neid.Constants;
import com.gtnewhorizons.neid.NEIDConfig;
import com.gtnewhorizons.neid.NEIDMemSlot;
import com.gtnewhorizons.neid.mixins.interfaces.IExtendedBlockStorageMixin;

@Mixin(ExtendedBlockStorage.class)
public class MixinExtendedBlockStorage implements IExtendedBlockStorageMixin {

    @Shadow
    private int blockRefCount;

    @Shadow
    private int tickRefCount;

    private NEIDMemSlot neidSlot;

    // Keep references to the arrays for backward compatibility
    // Will be initialized by constructor injection or getSlot()
    private short[] block16BArray;
    private short[] block16BMetaArray;

    @Inject(method = "<init>(IZ)V", at = @At("RETURN"))
    private void neid$initNEIDSlot(int yBase, boolean hasSkyLight, CallbackInfo ci) {
        this.neidSlot = new NEIDMemSlot();
        this.block16BArray = this.neidSlot.getBlocksArray();
        this.block16BMetaArray = this.neidSlot.getMetadataArray();
    }

    @Inject(method = "<init>(IZZ)V", at = @At("RETURN"), require = 0)
    private void neid$initNEIDSlotZerofill(int yBase, boolean hasSkyLight, boolean zerofill, CallbackInfo ci) {
        this.neidSlot = new NEIDMemSlot();
        if (zerofill) {
            this.neidSlot.zerofillAll();
        }
        this.block16BArray = this.neidSlot.getBlocksArray();
        this.block16BMetaArray = this.neidSlot.getMetadataArray();
    }

    // Note: Ultramine master branch конструктор ExtendedBlockStorage(MemSlot slot, ...) не патчится через mixin
    // потому что требует compile-time зависимости на Ultramine классы.
    // Вместо этого, Ultramine будет вызывать getSlot() и получать NEIDMemSlot напрямую.

    /**
     * Ultramine Core master branch integration - provide access to MemSlot Returns Object to avoid compile-time
     * dependency on Ultramine classes
     */
    public Object getSlot() {
        // Lazy initialization для Ultramine master branch
        if (neidSlot == null) {
            neidSlot = new NEIDMemSlot();
            block16BArray = neidSlot.getBlocksArray();
            block16BMetaArray = neidSlot.getMetadataArray();
        }
        return neidSlot;
    }

    @Override
    public short[] getBlock16BArray() {
        if (this.block16BArray == null) {
            getSlot(); // Lazy init
        }
        return this.block16BArray;
    }

    @Override
    public short[] getBlock16BMetaArray() {
        if (this.block16BMetaArray == null) {
            getSlot(); // Lazy init
        }
        return this.block16BMetaArray;
    }

    @Override
    public byte[] getBlockData() {
        final byte[] ret = new byte[this.block16BArray.length * 2];
        ByteBuffer.wrap(ret).asShortBuffer().put(this.block16BArray);
        return ret;
    }

    @Override
    public byte[] getBlockMeta() {
        final byte[] ret = new byte[this.block16BMetaArray.length * 2];
        ByteBuffer.wrap(ret).asShortBuffer().put(this.block16BMetaArray);
        return ret;
    }

    @Override
    public void setBlockData(byte[] data, int offset) {
        ShortBuffer.wrap(this.block16BArray)
                .put(ByteBuffer.wrap(data, offset, Constants.BLOCKS_PER_EBS * 2).asShortBuffer());
    }

    @Override
    public void setBlockMeta(byte[] data, int offset) {
        ShortBuffer.wrap(this.block16BMetaArray)
                .put(ByteBuffer.wrap(data, offset, Constants.BLOCKS_PER_EBS * 2).asShortBuffer());
    }

    private int getBlockId(int x, int y, int z) {
        return block16BArray[y << 8 | z << 4 | x] & 0xFFFF;
    }

    private void setBlockId(int x, int y, int z, int id) {
        block16BArray[y << 8 | z << 4 | x] = (short) id;
    }

    /**
     * @author Cleptomania
     * @reason Shims our block16BArray short array in place of built-in blockLSBArray. Original ASM was a complete
     *         overwrite as well. Likely no collision here.
     */
    @Overwrite
    public Block getBlockByExtId(int x, int y, int z) {
        return Block.getBlockById(getBlockId(x, y, z));
    }

    /**
     * @author Cleptomania
     * @reason Shims our block16BMetaArray short array in place of the built-in NibbleArray. Overwrite because very
     *         unlikely anything else touches this, and if it did it would almost certainly be incompatible with NEID at
     *         all.
     */
    @Overwrite
    public int getExtBlockMetadata(int x, int y, int z) {
        return this.block16BMetaArray[y << 8 | z << 4 | x] & 0xFFFF;
    }

    /**
     * @author Cleptomania
     * @reason This is for setExtBlockID but the function isn't deobf'd. Original ASM was not a complete overwrite, but
     *         was pretty close to it Extreme doubt that anything would conflict with this one.
     */
    @Overwrite
    public void func_150818_a(int x, int y, int z, Block b) {
        Block old = this.getBlockByExtId(x, y, z);
        if (old != Blocks.air) {
            --this.blockRefCount;
            if (old.getTickRandomly()) {
                --this.tickRefCount;
            }
        }

        if (b != Blocks.air) {
            ++this.blockRefCount;
            if (b.getTickRandomly()) {
                ++this.tickRefCount;
            }
        }

        int newId = Block.getIdFromBlock(b);
        if (NEIDConfig.CatchUnregisteredBlocks && newId == -1) {
            throw new IllegalArgumentException(
                    "Block " + b
                            + " is not registered. <-- Say about this to the author of this mod, or you can try to enable \"RemoveInvalidBlocks\" option in NEID config.");
        }
        if ((newId < 0 || newId > Constants.MAX_BLOCK_ID) && (newId != -1)) {
            throw new IllegalArgumentException("id out of range: " + newId);
        }
        if (newId == -1) {
            newId = Block.getIdFromBlock(old);
        }

        this.setBlockId(x, y, z, newId);
    }

    /**
     * @author Cleptomania
     * @reason Shims our block16BMetaArray in place of the vanilla NibbleArray. Overwrite because very unlikely anything
     *         else touches this, and if it did it would almost certainly be incompatible with NEID at all.
     */
    @Overwrite
    public void setExtBlockMetadata(int x, int y, int z, int meta) {
        this.block16BMetaArray[y << 8 | z << 4 | x] = (short) (meta & 0xFFFF);
    }

    /**
     * @author Cleptomania
     * @reason Original ASM was a complete overwrite to redirect to Hooks.removeInvalidBlocksHook which accepted the
     *         ExtendedBlockStorage class as a parameter. That method has been re-implemented here and modified to use
     *         the new block16BArray provided by the mixin, as opposed to getting the data from ExtendedBlockStorage.
     */
    @Overwrite
    public void removeInvalidBlocks() {
        for (int off = 0; off < block16BArray.length; ++off) {
            final int id = block16BArray[off] & 0xFFFF;
            if (id > 0) {
                final Block block = (Block) Block.blockRegistry.getObjectById(id);
                if (block == null) {
                    if (NEIDConfig.RemoveInvalidBlocks) {
                        block16BArray[off] = 0;
                    }
                } else if (block != Blocks.air) {
                    ++blockRefCount;
                    if (block.getTickRandomly()) {
                        ++tickRefCount;
                    }
                }
            }
        }
    }

}
