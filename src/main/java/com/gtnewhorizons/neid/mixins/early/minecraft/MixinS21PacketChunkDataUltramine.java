package com.gtnewhorizons.neid.mixins.early.minecraft;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.function.IntToLongFunction;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;
import com.gtnewhorizons.neid.ClientBlockTransformerRegistry;
import com.gtnewhorizons.neid.Constants;
import com.gtnewhorizons.neid.mixins.interfaces.IExtendedBlockStorageMixin;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;

/**
 * Mixin for Ultramine's S21PacketChunkData to handle NEID 16-bit block format. Ultramine uses slot.copyLSB() instead of
 * getBlockLSBArray(), so vanilla NEID mixins don't apply. We need to intercept the copy process and write NEID data
 * instead.
 */
@Mixin(targets = "net.minecraft.network.play.server.S21PacketChunkData", remap = false)
public class MixinS21PacketChunkDataUltramine {

    /**
     * Intercept the loop that copies LSB data and replace it with NEID 16-bit block data. We inject after the loop
     * starts to capture locals, then modify the offset to account for larger NEID data.
     */
    @Inject(
            method = "func_149269_a",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ultramine/server/chunk/alloc/MemSlot;copyLSB([BI)V",
                    shift = At.Shift.BEFORE,
                    remap = false),
            locals = LocalCapture.CAPTURE_FAILHARD,
            require = 0,
            remap = false)
    private static void neid$copyNeidBlockData(Chunk p_149269_0_, boolean p_149269_1_, int p_149269_2_,
            CallbackInfoReturnable<?> cir, LocalIntRef jRef, ExtendedBlockStorage[] aextendedblockstorage, int k,
            Object extracted, byte[] abyte, int l) {
        // Copy NEID 16-bit block data instead of vanilla LSB
        if (aextendedblockstorage[l] != null) {
            IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) aextendedblockstorage[l];
            byte[] neidBlocks = ebsMixin.getBlockData();
            System.arraycopy(neidBlocks, 0, abyte, jRef.get(), Constants.BLOCKS_PER_EBS * 2);
            jRef.set(jRef.get() + (Constants.BLOCKS_PER_EBS * 2)); // Advance by 8192 bytes instead of 4096
        }
    }

    /**
     * Intercept the loop that copies metadata and replace it with NEID 16-bit metadata. We inject after the metadata
     * loop starts.
     */
    @Inject(
            method = "func_149269_a",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ultramine/server/chunk/alloc/MemSlot;copyBlockMetadata([BI)V",
                    shift = At.Shift.BEFORE,
                    remap = false),
            locals = LocalCapture.CAPTURE_FAILHARD,
            require = 0,
            remap = false)
    private static void neid$copyNeidMetaData(Chunk p_149269_0_, boolean p_149269_1_, int p_149269_2_,
            CallbackInfoReturnable<?> cir, LocalIntRef jRef, ExtendedBlockStorage[] aextendedblockstorage, int k,
            Object extracted, byte[] abyte, int l, int l2) {
        // Copy NEID 16-bit metadata instead of vanilla nibble array
        if (aextendedblockstorage[l2] != null) {
            IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) aextendedblockstorage[l2];
            byte[] neidMeta = ebsMixin.getBlockMeta();
            System.arraycopy(neidMeta, 0, abyte, jRef.get(), Constants.BLOCKS_PER_EBS * 2);
            jRef.set(jRef.get() + (Constants.BLOCKS_PER_EBS * 2)); // Advance by 8192 bytes instead of 2048
        }
    }

    /**
     * Skip the MSB copy loop entirely since NEID doesn't use MSB arrays. Inject at the start of the MSB loop and set
     * the offset to skip it.
     */
    @Inject(
            method = "func_149269_a",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ultramine/server/chunk/alloc/MemSlot;copyMSB([BI)V",
                    shift = At.Shift.BEFORE,
                    remap = false),
            cancellable = true,
            locals = LocalCapture.CAPTURE_FAILHARD,
            require = 0,
            remap = false)
    private static void neid$skipMSBCopy(Chunk p_149269_0_, boolean p_149269_1_, int p_149269_2_,
            CallbackInfoReturnable<?> cir, LocalIntRef jRef, ExtendedBlockStorage[] aextendedblockstorage, int k,
            Object extracted, byte[] abyte, int l, int l2, int l3, int l4) {
        // Skip MSB copy - NEID already has full 16-bit IDs
        // Don't modify jRef, just skip the copyMSB call
    }

    /**
     * After all data is copied, transform block IDs for client-side rendering using ClientBlockTransformerRegistry.
     */
    @Inject(method = "func_149269_a", at = @At("RETURN"), require = 0, remap = false)
    private static void neid$transformClientBlocks(Chunk p_149269_0_, boolean p_149269_1_, int p_149269_2_,
            CallbackInfoReturnable<?> cir) throws Exception {
        // Get the data object via reflection to avoid compile-time dependency on S21PacketChunkData.Extracted
        Object dataObj = cir.getReturnValue();
        if (dataObj == null) return;

        // Access field_150282_a via reflection
        java.lang.reflect.Field field150282a = dataObj.getClass().getDeclaredField("field_150282_a");
        field150282a.setAccessible(true);
        byte[] data = (byte[]) field150282a.get(dataObj);
        ExtendedBlockStorage[] ebs = p_149269_0_.getBlockStorageArray();

        ShortBuffer[] blocks = new ShortBuffer[ebs.length];
        ShortBuffer[] metas = new ShortBuffer[ebs.length];

        int cursor = 0;
        final int ebsLength = Constants.BLOCKS_PER_EBS * 2;

        // Parse NEID block data
        for (int i = 0; i < ebs.length; ++i) {
            if (ebs[i] != null && (!p_149269_1_ || !ebs[i].isEmpty()) && (p_149269_2_ & 1 << i) != 0) {
                blocks[i] = ByteBuffer.wrap(data, cursor, ebsLength).asShortBuffer();
                cursor += ebsLength;
            }
        }

        // Parse NEID metadata
        for (int i = 0; i < ebs.length; ++i) {
            if (ebs[i] != null && (!p_149269_1_ || !ebs[i].isEmpty()) && (p_149269_2_ & 1 << i) != 0) {
                metas[i] = ByteBuffer.wrap(data, cursor, ebsLength).asShortBuffer();
                cursor += ebsLength;
            }
        }

        int cx = p_149269_0_.xPosition * 16;
        int cz = p_149269_0_.zPosition * 16;

        // Transform blocks for client
        for (int i = 0; i < ebs.length; i++) {
            if (ebs[i] != null && (!p_149269_1_ || !ebs[i].isEmpty()) && (p_149269_2_ & 1 << i) != 0) {
                int ebsY = ebs[i].getYLocation();

                IntToLongFunction coord = blockIndex -> {
                    int x = blockIndex & 15;
                    int z = (blockIndex >> 4) & 15;
                    int y = (blockIndex >> 8) & 255;

                    return CoordinatePacker.pack(x + cx, y + ebsY, z + cz);
                };

                ClientBlockTransformerRegistry.transformBulk(p_149269_0_.worldObj, coord, blocks[i], metas[i]);
            }
        }
    }
}
