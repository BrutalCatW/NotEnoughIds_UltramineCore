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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnewhorizons.neid.Constants;
import com.gtnewhorizons.neid.NEIDConfig;
import com.gtnewhorizons.neid.mixins.interfaces.IExtendedBlockStorageMixin;

@Mixin(ExtendedBlockStorage.class)
public class MixinExtendedBlockStorage implements IExtendedBlockStorageMixin {

    @Shadow
    private int blockRefCount;

    @Shadow
    private int tickRefCount;

    // NEID uses simple on-heap arrays - direct initialization like lyxinfine
    private short[] block16BArray = new short[Constants.BLOCKS_PER_EBS];
    private short[] block16BMetaArray = new short[Constants.BLOCKS_PER_EBS];

    @Override
    public short[] getBlock16BArray() {
        return this.block16BArray;
    }

    @Override
    public short[] getBlock16BMetaArray() {
        return this.block16BMetaArray;
    }

    @Override
    public byte[] getBlockData() {
        final byte[] ret = new byte[this.block16BArray.length * 2];
        // Use native byte order (little-endian on x86) to match Ultramine server
        ByteBuffer.wrap(ret).asShortBuffer().put(this.block16BArray);
        return ret;
    }

    @Override
    public byte[] getBlockMeta() {
        final byte[] ret = new byte[this.block16BMetaArray.length * 2];
        // Use native byte order (little-endian on x86) to match Ultramine server
        ByteBuffer.wrap(ret).asShortBuffer().put(this.block16BMetaArray);
        return ret;
    }

    @Override
    public void setBlockData(byte[] data, int offset) {
        // Use native byte order (little-endian on x86) to match Ultramine server
        ShortBuffer.wrap(this.block16BArray)
                .put(ByteBuffer.wrap(data, offset, Constants.BLOCKS_PER_EBS * 2).asShortBuffer());
    }

    @Override
    public void setBlockMeta(byte[] data, int offset) {
        // Use native byte order (little-endian on x86) to match Ultramine server
        ShortBuffer.wrap(this.block16BMetaArray)
                .put(ByteBuffer.wrap(data, offset, Constants.BLOCKS_PER_EBS * 2).asShortBuffer());
    }

    // Ultramine slot accessor - cached to avoid repeated reflection
    private Object cachedSlot = null;
    private boolean slotAccessAttempted = false;

    private Object getUltramineSlot() {
        if (!slotAccessAttempted) {
            slotAccessAttempted = true;
            try {
                cachedSlot = this.getClass().getMethod("getSlot").invoke(this);
            } catch (Exception e) {
                // Not Ultramine or method doesn't exist
                cachedSlot = null;
            }
        }
        return cachedSlot;
    }

    private int getBlockId(int x, int y, int z) {
        // ALWAYS read from NEID array - it's the source of truth
        // We sync TO MemSlot before copy() for packet sending, but MemSlot is NOT kept in sync during normal gameplay
        return block16BArray[y << 8 | z << 4 | x] & 0xFFFF;
    }

    private void setBlockId(int x, int y, int z, int id) {
        block16BArray[y << 8 | z << 4 | x] = (short) id;

        // Sync to Ultramine slot if available
        Object slot = getUltramineSlot();
        if (slot != null) {
            try {
                java.lang.reflect.Method setBlockIdMethod = slot.getClass()
                        .getMethod("setBlockId", int.class, int.class, int.class, int.class);
                setBlockIdMethod.invoke(slot, x, y, z, id);
            } catch (Exception e) {
                // Ultramine slot not available or failed
            }
        }
    }

    private int getBlockMetadata(int x, int y, int z) {
        // ALWAYS read from NEID array - it's the source of truth
        // We sync TO MemSlot before copy() for packet sending, but MemSlot is NOT kept in sync during normal gameplay
        return this.block16BMetaArray[y << 8 | z << 4 | x] & 0xFFFF;
    }

    private void setBlockMetadata(int x, int y, int z, int meta) {
        this.block16BMetaArray[y << 8 | z << 4 | x] = (short) (meta & 0xFFFF);

        // Sync to Ultramine slot if available
        Object slot = getUltramineSlot();
        if (slot != null) {
            try {
                java.lang.reflect.Method setMetaMethod = slot.getClass()
                        .getMethod("setMeta", int.class, int.class, int.class, int.class);
                setMetaMethod.invoke(slot, x, y, z, meta);
            } catch (Exception e) {
                // Ultramine slot not available or failed
            }
        }
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
        return getBlockMetadata(x, y, z);
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
        setBlockMetadata(x, y, z, meta);
    }

    /**
     * @author Cleptomania
     * @reason Original ASM was a complete overwrite to redirect to Hooks.removeInvalidBlocksHook which accepted the
     *         ExtendedBlockStorage class as a parameter. That method has been re-implemented here and modified to use
     *         the new block16BArray provided by the mixin, as opposed to getting the data from ExtendedBlockStorage.
     */
    @Overwrite
    public void removeInvalidBlocks() {
        // CRITICAL: Reset counters before counting! Otherwise fillChunk() will accumulate counts
        this.blockRefCount = 0;
        this.tickRefCount = 0;

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

    /**
     * Ultramine Core specific method - fix buggy MSB clearing. This method only exists in Ultramine, so we use Inject
     * with require=0 to make it optional. The original Ultramine implementation truncates block IDs to 255, causing
     * corruption.
     */
    @Inject(method = "clearMSBArray", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    public void neid$fixClearMSBArray(CallbackInfo ci) {
        // Preserve LSB (lower 8 bits), clear only MSB (upper 8 bits)
        for (int i = 0; i < Constants.BLOCKS_PER_EBS; i++) {
            this.block16BArray[i] = (short) (this.block16BArray[i] & 0x00FF);
        }
        ci.cancel(); // Don't execute original buggy implementation
    }

    /**
     * CRITICAL FIX: Sync NEID arrays TO MemSlot BEFORE copy() for client chunk sending. Ultramine's copy() creates a
     * new MemSlot via slot.copy(), then ChunkSnapshot reads from it. We must sync BEFORE copy() so the original MemSlot
     * contains correct data to be copied.
     */
    @Inject(method = "copy", at = @At("HEAD"), remap = false, require = 0)
    private void neid$syncBeforeCopy(CallbackInfoReturnable<ExtendedBlockStorage> cir) {
        // Count blocks in NEID arrays
        int nonAir = 0;
        int over255 = 0;
        for (int i = 0; i < this.block16BArray.length; i++) {
            int blockId = this.block16BArray[i] & 0xFFFF;
            if (blockId != 0) {
                nonAir++;
                if (blockId > 255) over255++;
            }
        }
        System.out
                .println("[NEID] copy() HEAD - syncing to original MemSlot: nonAir=" + nonAir + ", over255=" + over255);

        // Sync THIS object's NEID arrays to THIS MemSlot (will be copied by copy())
        syncNeidToUltramineSlot();
    }

    /**
     * Sync FROM Ultramine MemSlot TO NEID arrays. Populates NEID arrays with world data.
     */
    private void syncFromUltramineSlot() {
        Object slot = getUltramineSlot();
        if (slot == null) {
            System.out.println("[NEID] No Ultramine slot - skipping FROM sync");
            return;
        }

        try {
            Class<?> slotClass = slot.getClass();
            java.lang.reflect.Method getBlockId = slotClass.getMethod("getBlockId", int.class, int.class, int.class);
            java.lang.reflect.Method getMeta = slotClass.getMethod("getMeta", int.class, int.class, int.class);

            int synced = 0;
            int nonAir = 0;
            int over255 = 0;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        int index = y << 8 | z << 4 | x;
                        int blockId = (Integer) getBlockId.invoke(slot, x, y, z);
                        int meta = (Integer) getMeta.invoke(slot, x, y, z);

                        this.block16BArray[index] = (short) (blockId & 0xFFFF);
                        this.block16BMetaArray[index] = (short) (meta & 0xFFFF);

                        if (blockId != 0) {
                            nonAir++;
                            if (blockId > 255) over255++;
                        }
                        synced++;
                    }
                }
            }
            System.out.println(
                    "[NEID] Synced FROM MemSlot to NEID: " + synced
                            + " blocks (nonAir="
                            + nonAir
                            + ", over255="
                            + over255
                            + ")");
        } catch (Exception e) {
            System.err.println("[NEID] Failed to sync FROM MemSlot: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Sync NEID arrays TO Ultramine MemSlot. Critical for client chunk synchronization.
     */
    private void syncNeidToUltramineSlot() {
        Object slot = getUltramineSlot();
        if (slot == null) {
            System.out.println("[NEID] No Ultramine slot - skipping sync");
            return;
        }

        try {
            Class<?> slotClass = slot.getClass();
            java.lang.reflect.Method setBlockId = slotClass
                    .getMethod("setBlockId", int.class, int.class, int.class, int.class);
            java.lang.reflect.Method setMeta = slotClass
                    .getMethod("setMeta", int.class, int.class, int.class, int.class);

            int synced = 0;
            int nonZero = 0;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        int index = y << 8 | z << 4 | x;
                        int blockId = block16BArray[index] & 0xFFFF;
                        int meta = block16BMetaArray[index] & 0xFFFF;
                        if (blockId != 0) nonZero++;
                        setBlockId.invoke(slot, x, y, z, blockId);
                        setMeta.invoke(slot, x, y, z, meta);
                        synced++;
                    }
                }
            }
            System.out.println("[NEID] Synced " + synced + " blocks to MemSlot (nonZero=" + nonZero + ")");
        } catch (Exception e) {
            System.err.println("[NEID] Failed to sync to MemSlot: " + e.getMessage());
            e.printStackTrace();
        }
    }

}
