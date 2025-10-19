package com.gtnewhorizons.neid.mixins.early.minecraft.client;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnewhorizons.neid.Constants;
import com.gtnewhorizons.neid.mixins.interfaces.IExtendedBlockStorageMixin;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;

@Mixin(Chunk.class)
public class MixinChunk {

    private static final byte[] fakeByteArray = new byte[0];
    private static final NibbleArray fakeNibbleArray = new NibbleArray(0, 0);

    @Shadow
    private ExtendedBlockStorage[] storageArrays;

    @Redirect(
            method = "fillChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;getBlockLSBArray()[B"),
            require = 1)
    private byte[] neid$injectNewDataCopy(ExtendedBlockStorage ebs, @Local(ordinal = 0) byte[] thebytes,
            @Local(ordinal = 2) LocalIntRef offset) {
        System.out.println("[NEID CLIENT] fillChunk() reading LSB at offset=" + offset.get());
        IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;

        // Read LSB (4096 bytes) into lower 8 bits of block16BArray
        for (int i = 0; i < Constants.BLOCKS_PER_EBS; i++) {
            int lsb = thebytes[offset.get() + i] & 0xFF;
            ebsMixin.getBlock16BArray()[i] = (short) lsb;
        }

        offset.set(offset.get() + 4096);
        return fakeByteArray;
    }

    @Redirect(
            method = "fillChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;getMetadataArray()Lnet/minecraft/world/chunk/NibbleArray;"),
            require = 1)
    private NibbleArray neid$injectNewMetadataCopy(ExtendedBlockStorage ebs, @Local(ordinal = 0) byte[] thebytes,
            @Local(ordinal = 2) LocalIntRef offset) {
        System.out.println("[NEID CLIENT] fillChunk() reading metadata nibbles at offset=" + offset.get());
        IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;

        // Read metadata (2048 bytes = 4096 nibbles) into block16BMetaArray
        for (int i = 0; i < Constants.BLOCKS_PER_EBS; i++) {
            int byteIndex = i / 2;
            int nibble = (i & 1) == 0 ? (thebytes[offset.get() + byteIndex] & 0x0F)
                    : ((thebytes[offset.get() + byteIndex] >> 4) & 0x0F);
            ebsMixin.getBlock16BMetaArray()[i] = (short) nibble;
        }

        offset.set(offset.get() + 2048);
        return fakeNibbleArray;
    }

    @WrapWithCondition(
            method = "fillChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/System;arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V",
                    ordinal = 0),
            require = 1)
    private boolean neid$cancelLSBArrayCopy(Object a, int i, Object b, int j, int k) {
        return false;
    }

    @WrapWithCondition(
            method = "fillChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/System;arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V",
                    ordinal = 1),
            require = 1)
    private boolean neid$cancelMetadataArrayCopy(Object a, int i, Object b, int j, int k) {
        return false;
    }

    @ModifyConstant(method = "fillChunk", constant = @Constant(intValue = 0, ordinal = 10), require = 0)
    private int neid$AllowMSBForLoop(int i) {
        // Allow MSB loop to run - we need it to read high 4 bits from Ultramine packed format
        return 0;
    }

    /**
     * Intercept MSB reading to combine with LSB into 16-bit block IDs. Ultramine sends MSB as packed nibbles (2048
     * bytes).
     */
    @Redirect(
            method = "fillChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;getBlockMSBArray()Lnet/minecraft/world/chunk/NibbleArray;"),
            require = 0)
    private NibbleArray neid$injectMSBRead(ExtendedBlockStorage ebs, @Local(ordinal = 0) byte[] thebytes,
            @Local(ordinal = 2) LocalIntRef offset) {
        System.out.println("[NEID CLIENT] fillChunk() reading MSB nibbles at offset=" + offset.get());
        IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;

        // Read MSB (2048 bytes = 4096 nibbles) and combine with LSB
        for (int i = 0; i < Constants.BLOCKS_PER_EBS; i++) {
            int byteIndex = i / 2;
            int nibble = (i & 1) == 0 ? (thebytes[offset.get() + byteIndex] & 0x0F)
                    : ((thebytes[offset.get() + byteIndex] >> 4) & 0x0F);

            // Combine MSB (high 4 bits) with existing LSB (low 8 bits)
            int lsb = ebsMixin.getBlock16BArray()[i] & 0xFF;
            int blockId = lsb | (nibble << 8);
            ebsMixin.getBlock16BArray()[i] = (short) blockId;
        }

        // Count blocks for debug
        int nonAir = 0;
        int over255 = 0;
        for (short s : ebsMixin.getBlock16BArray()) {
            int blockId = s & 0xFFFF;
            if (blockId != 0) {
                nonAir++;
                if (blockId > 255) over255++;
            }
        }
        System.out.println("[NEID CLIENT] After MSB: nonAir=" + nonAir + ", over255=" + over255);

        offset.set(offset.get() + 2048);
        return fakeNibbleArray;
    }
}
