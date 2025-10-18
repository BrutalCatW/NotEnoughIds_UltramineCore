package com.gtnewhorizons.neid.mixins.early.minecraft;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.function.IntToLongFunction;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;
import com.gtnewhorizons.neid.ClientBlockTransformerRegistry;
import com.gtnewhorizons.neid.Constants;
import com.gtnewhorizons.neid.mixins.interfaces.IExtendedBlockStorageMixin;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;

@Mixin(targets = "net.minecraft.network.play.server.S21PacketChunkData")
public class MixinS21PacketChunkData {

    private static final byte[] fakeByteArray = new byte[0];
    private static final NibbleArray fakeNibbleArray = new NibbleArray(0, 0);

    // Флаг для определения, был ли вызван наш redirect (не Ultramine)
    private static final ThreadLocal<Boolean> redirectCalled = ThreadLocal.withInitial(() -> false);

    @ModifyConstant(
            method = "<clinit>",
            constant = @Constant(intValue = Constants.VANILLA_BYTES_PER_CHUNK),
            require = 0)
    private static int neid$OverrideBytesPerChunk1(int old) {
        return Constants.BYTES_PER_CHUNK;
    }

    @ModifyConstant(
            method = "func_149275_c()I",
            constant = @Constant(intValue = Constants.VANILLA_BYTES_PER_CHUNK),
            require = 0)
    private static int neid$OverrideBytesPerChunk2(int i) {
        return Constants.BYTES_PER_CHUNK;
    }

    @ModifyConstant(
            method = "readPacketData",
            constant = @Constant(intValue = Constants.VANILLA_BYTES_PER_EBS),
            require = 0)
    private static int neid$OverrideBytesPerEBS(int i) {
        return Constants.BYTES_PER_EBS;
    }

    @Redirect(
            method = "func_149269_a",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;getBlockLSBArray()[B"),
            require = 0)
    private static byte[] neid$injectNewDataCopy(ExtendedBlockStorage ebs, @Local(ordinal = 0) byte[] thebytes,
            @Local(ordinal = 1) LocalIntRef offset) {
        redirectCalled.set(true); // Помечаем что наш код выполняется
        IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;
        byte[] data = ebsMixin.getBlockData();
        System.arraycopy(data, 0, thebytes, offset.get(), Constants.BLOCKS_PER_EBS * 2);
        offset.set(offset.get() + (Constants.BLOCKS_PER_EBS * 2));
        return fakeByteArray;
    }

    @WrapWithCondition(
            method = "func_149269_a",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/System;arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V",
                    ordinal = 0),
            require = 0)
    private static boolean neid$cancelLSBArrayCopy(Object a, int i, Object b, int j, int k) {
        return false;
    }

    @Redirect(
            method = "func_149269_a",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;getMetadataArray()Lnet/minecraft/world/chunk/NibbleArray;"),
            require = 0)
    private static NibbleArray neid$injectNewMetadataCopy(ExtendedBlockStorage ebs, @Local(ordinal = 0) byte[] thebytes,
            @Local(ordinal = 1) LocalIntRef offset) {
        IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;
        byte[] meta = ebsMixin.getBlockMeta();
        System.arraycopy(meta, 0, thebytes, offset.get(), Constants.BLOCKS_PER_EBS * 2);
        offset.set(offset.get() + (Constants.BLOCKS_PER_EBS * 2));
        return fakeNibbleArray;
    }

    @WrapWithCondition(
            method = "func_149269_a",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/System;arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V",
                    ordinal = 1),
            require = 0)
    private static boolean neid$cancelMetadataArrayCopy(Object a, int i, Object b, int j, int k) {
        return false;
    }

    @Redirect(
            method = "func_149269_a",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;getBlockMSBArray()Lnet/minecraft/world/chunk/NibbleArray;",
                    ordinal = 0),
            require = 0)
    private static NibbleArray neid$nukeMSBLoop(ExtendedBlockStorage ebs) {
        return null;
    }

    @Inject(method = "func_149269_a", at = @At("TAIL"), require = 0)
    private static void neid$modifyChunkData(Chunk chunk, boolean firstSync, int flags, CallbackInfoReturnable<?> cir)
            throws Exception {
        // Если redirect не вызывался (Ultramine master), не выполняем этот код
        if (!redirectCalled.get()) {
            return;
        }
        redirectCalled.set(false); // Сбрасываем флаг

        // Get return value via reflection to avoid compile dependency on S21PacketChunkData.Extracted
        Object dataObj = cir.getReturnValue();
        if (dataObj == null) return;

        java.lang.reflect.Field field150282a = dataObj.getClass().getDeclaredField("field_150282_a");
        field150282a.setAccessible(true);
        byte[] dataBytes = (byte[]) field150282a.get(dataObj);

        ExtendedBlockStorage[] ebs = chunk.getBlockStorageArray();

        ShortBuffer[] blocks = new ShortBuffer[ebs.length];
        ShortBuffer[] metas = new ShortBuffer[ebs.length];

        int cursor = 0;

        final int ebsLength = Constants.BLOCKS_PER_EBS * 2;

        for (int i = 0; i < ebs.length; ++i) {
            if (ebs[i] != null && (!firstSync || !ebs[i].isEmpty()) && (flags & 1 << i) != 0) {
                blocks[i] = ByteBuffer.wrap(dataBytes, cursor, ebsLength).asShortBuffer();
                cursor += ebsLength;
            }
        }

        for (int i = 0; i < ebs.length; ++i) {
            if (ebs[i] != null && (!firstSync || !ebs[i].isEmpty()) && (flags & 1 << i) != 0) {
                metas[i] = ByteBuffer.wrap(dataBytes, cursor, ebsLength).asShortBuffer();
                cursor += ebsLength;
            }
        }

        int cx = chunk.xPosition * 16;
        int cz = chunk.zPosition * 16;

        for (int i = 0; i < ebs.length; i++) {
            if (ebs[i] != null && (!firstSync || !ebs[i].isEmpty()) && (flags & 1 << i) != 0) {
                int ebsY = ebs[i].getYLocation();

                IntToLongFunction coord = blockIndex -> {
                    int x = blockIndex & 15;
                    int z = (blockIndex >> 4) & 15;
                    int y = (blockIndex >> 8) & 255;

                    return CoordinatePacker.pack(x + cx, y + ebsY, z + cz);
                };

                ClientBlockTransformerRegistry.transformBulk(chunk.worldObj, coord, blocks[i], metas[i]);
            }
        }
    }
}
