package com.gtnewhorizons.neid.mixins.early.minecraft.client;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnewhorizons.neid.Constants;
import com.gtnewhorizons.neid.mixins.interfaces.IExtendedBlockStorageMixin;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;

@Mixin(Chunk.class)
public class MixinChunk {

    private static final byte[] fakeByteArray = new byte[0];
    private static final NibbleArray fakeNibbleArray = new NibbleArray(0, 0);

    // Track Ultramine grouped format: [all LSB][all Meta][all BL][all SL][all MSB][biome]
    private static final ThreadLocal<Integer> ultramineEbsCount = new ThreadLocal<>();
    private static final ThreadLocal<Integer> ultramineCurrentLsbIndex = new ThreadLocal<>();
    private static final ThreadLocal<Integer> ultramineCurrentMetaIndex = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> ultramineHasSkylight = new ThreadLocal<>();

    @Shadow
    private ExtendedBlockStorage[] storageArrays;

    @Inject(method = "fillChunk", at = @At("HEAD"))
    private void neid$initUltramineTracking(byte[] data, int mask, int additionalMask, boolean skylight,
            CallbackInfo ci) {
        int ebsCount = Integer.bitCount(mask);
        ultramineEbsCount.set(ebsCount);
        ultramineCurrentLsbIndex.set(0);
        ultramineCurrentMetaIndex.set(0);
        ultramineHasSkylight.set(skylight);
    }

    @Redirect(
            method = "fillChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;getBlockLSBArray()[B"),
            require = 1)
    private byte[] neid$injectNewDataCopy(ExtendedBlockStorage ebs, @Local(ordinal = 0) byte[] thebytes,
            @Local(ordinal = 2) LocalIntRef offset) {
        IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;
        int currentIndex = ultramineCurrentLsbIndex.get();

        // Ultramine groups all LSB together at start of packet
        int lsbOffset = currentIndex * 4096;

        // Read LSB (4096 bytes) from Ultramine grouped position
        for (int i = 0; i < Constants.BLOCKS_PER_EBS; i++) {
            int lsb = thebytes[lsbOffset + i] & 0xFF;
            ebsMixin.getBlock16BArray()[i] = (short) lsb;
        }

        // Increment index for next EBS
        ultramineCurrentLsbIndex.set(currentIndex + 1);

        // CRITICAL: Advance vanilla offset so blocklight/skylight/biome read from correct positions
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
        IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;
        int currentIndex = ultramineCurrentMetaIndex.get();
        int ebsCount = ultramineEbsCount.get();

        // Ultramine groups all Metadata after all LSB
        int metaOffset = (ebsCount * 4096) + (currentIndex * 2048);

        // Read metadata (2048 bytes = 4096 nibbles) from Ultramine grouped position
        for (int i = 0; i < Constants.BLOCKS_PER_EBS; i++) {
            int byteIndex = i / 2;
            int nibble = (i & 1) == 0 ? (thebytes[metaOffset + byteIndex] & 0x0F)
                    : ((thebytes[metaOffset + byteIndex] >> 4) & 0x0F);
            ebsMixin.getBlock16BMetaArray()[i] = (short) nibble;
        }

        // Increment index for next EBS
        ultramineCurrentMetaIndex.set(currentIndex + 1);

        // CRITICAL: Advance vanilla offset so blocklight/skylight/biome read from correct positions
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
        // Allow MSB loop to run + reset index for MSB reading
        ultramineCurrentLsbIndex.set(0);
        return 0;
    }

    @Redirect(
            method = "fillChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;getBlockMSBArray()Lnet/minecraft/world/chunk/NibbleArray;"),
            require = 0)
    private NibbleArray neid$injectMSBRead(ExtendedBlockStorage ebs, @Local(ordinal = 0) byte[] thebytes,
            @Local(ordinal = 2) LocalIntRef offset) {
        IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;
        int currentIndex = ultramineCurrentLsbIndex.get();
        int ebsCount = ultramineEbsCount.get();
        boolean hasSkylight = ultramineHasSkylight.get();

        // Ultramine groups all MSB after [LSB][Meta][Blocklight][Skylight]
        int msbBaseOffset = (ebsCount * 4096) + (ebsCount * 2048) + (ebsCount * 2048);
        if (hasSkylight) {
            msbBaseOffset += (ebsCount * 2048);
        }
        int msbOffset = msbBaseOffset + (currentIndex * 2048);

        // Read MSB from Ultramine grouped position and combine with LSB
        for (int i = 0; i < Constants.BLOCKS_PER_EBS; i++) {
            int byteIndex = i / 2;
            int nibble = (i & 1) == 0 ? (thebytes[msbOffset + byteIndex] & 0x0F)
                    : ((thebytes[msbOffset + byteIndex] >> 4) & 0x0F);

            int lsb = ebsMixin.getBlock16BArray()[i] & 0xFF;
            int blockId = lsb | (nibble << 8);
            ebsMixin.getBlock16BArray()[i] = (short) blockId;
        }

        // Increment index for next EBS
        ultramineCurrentLsbIndex.set(currentIndex + 1);

        // CRITICAL: Advance vanilla offset so biome data reads from correct position
        offset.set(offset.get() + 2048);
        return fakeNibbleArray;
    }
}
