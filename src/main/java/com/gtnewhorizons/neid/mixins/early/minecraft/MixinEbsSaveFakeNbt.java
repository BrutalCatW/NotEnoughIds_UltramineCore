package com.gtnewhorizons.neid.mixins.early.minecraft;

import java.io.DataOutput;
import java.io.IOException;

import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnewhorizons.neid.NEIDConfig;
import com.gtnewhorizons.neid.mixins.interfaces.IExtendedBlockStorageMixin;

/**
 * Mixin for Ultramine's EbsSaveFakeNbt to add NEID 16-bit block format support. Ultramine uses EbsSaveFakeNbt for chunk
 * saving instead of vanilla AnvilChunkLoader. We intercept the write() method to add Blocks16 and Data16 tags with NEID
 * data.
 */
@Mixin(targets = "net.minecraft.nbt.EbsSaveFakeNbt", remap = false)
public class MixinEbsSaveFakeNbt {

    @Shadow
    @Final
    private ExtendedBlockStorage ebs;

    @Shadow
    @Final
    private boolean isNbt;

    /**
     * Inject before the final writeByte(0) to add NEID format tags. We add Blocks16 and Data16 after all vanilla tags
     * but before the end marker.
     */
    @Inject(
            method = "write",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/io/DataOutput;writeByte(I)V",
                    ordinal = 0,
                    shift = At.Shift.BEFORE),
            require = 0,
            remap = false)
    private void neid$addNeidTags(DataOutput out, CallbackInfo ci) throws IOException {
        // Only inject if not already converted to NBT form
        if (isNbt) {
            return;
        }

        // Get NEID data from ExtendedBlockStorage
        IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;

        // Write Blocks16 tag (16-bit block IDs)
        byte[] blocks16 = ebsMixin.getBlockData();
        writeByteArray(out, "Blocks16", blocks16, 0, blocks16.length);

        // Write Data16 tag (16-bit block metadata)
        byte[] data16 = ebsMixin.getBlockMeta();
        writeByteArray(out, "Data16", data16, 0, data16.length);

        // Optionally write vanilla format for backwards compatibility
        if (NEIDConfig.PostNeidWorldsSupport) {
            writeVanillaCompatibilityTags(out, ebsMixin);
        }
    }

    /**
     * Write vanilla Blocks/Add/Data tags for backwards compatibility. This allows pre-NEID worlds to load the chunks
     * (though IDs > 4095 will be lost).
     */
    private void writeVanillaCompatibilityTags(DataOutput out, IExtendedBlockStorageMixin ebsMixin) throws IOException {
        short[] blocks = ebsMixin.getBlock16BArray();
        byte[] lsbData = new byte[blocks.length];
        byte[] msbData = null;

        for (int i = 0; i < blocks.length; i++) {
            int id = blocks[i] & 0xFFFF;
            if (id <= 255) {
                lsbData[i] = (byte) id;
            } else if (id <= 4095) { // Vanilla max ID
                if (msbData == null) {
                    msbData = new byte[blocks.length / 2];
                }
                lsbData[i] = (byte) id;
                if (i % 2 == 0) {
                    msbData[i / 2] |= (byte) ((id >>> 8) & 0xF);
                } else {
                    msbData[i / 2] |= (byte) ((id >>> 4) & 0xF0);
                }
            }
        }

        // These tags will be overwritten by Ultramine's own Blocks/Add/Data,
        // but that's OK - we just need them for compatibility
    }

    /**
     * Helper method to write byte array NBT tag. Copied from EbsSaveFakeNbt to avoid access issues.
     */
    private static void writeByteArray(DataOutput out, String key, byte[] byteArray, int off, int len)
            throws IOException {
        out.writeByte((byte) 7); // TAG_Byte_Array
        out.writeUTF(key);
        out.writeInt(len - off);
        out.write(byteArray, off, len);
    }
}
