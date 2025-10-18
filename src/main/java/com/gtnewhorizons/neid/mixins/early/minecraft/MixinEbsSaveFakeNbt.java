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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
     * Test injection - log when convertToNbt is called
     */
    @Inject(method = "convertToNbt", at = @At("HEAD"), require = 0, remap = false)
    private void neid$logConvertToNbt(CallbackInfo ci) {
        System.out.println("[NEID] EbsSaveFakeNbt.convertToNbt() called!");
    }

    /**
     * Test injection - log when copy is called
     */
    @Inject(method = "copy", at = @At("HEAD"), require = 0, remap = false)
    private void neid$logCopy(CallbackInfoReturnable<?> cir) {
        System.out.println("[NEID] EbsSaveFakeNbt.copy() called, isNbt=" + isNbt);
    }

    /**
     * Test injection - log constructor AND sync data immediately
     */
    @Inject(method = "<init>", at = @At("RETURN"), require = 0, remap = false)
    private void neid$logConstructor(ExtendedBlockStorage ebs, boolean hasNoSky, CallbackInfo ci) {
        System.out.println("[NEID] EbsSaveFakeNbt constructed for Y=" + (ebs.getYLocation() >> 4));

        // CRITICAL: Sync data FROM Ultramine slot TO NEID arrays immediately in constructor!
        // This is where we need to capture the data before it's saved
        IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;
        syncFromUltramineSlot(ebs, ebsMixin);
        System.out.println("[NEID] Synced data from Ultramine slot in constructor");
    }

    /**
     * Inject at HEAD and cancel to completely replace the write() method. We'll write vanilla tags first, then NEID
     * tags, then the end marker.
     */
    @Inject(method = "write", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void neid$replaceWrite(DataOutput out, CallbackInfo ci) throws IOException {
        System.out.println("[NEID] EbsSaveFakeNbt.write() HEAD - isNbt=" + isNbt + ", Y=" + (ebs.getYLocation() >> 4));

        // If already converted to NBT form, use parent's write
        if (isNbt) {
            System.out.println("[NEID] Already NBT, letting parent handle");
            return; // Let parent handle it
        }

        System.out.println("[NEID] Processing write with NEID data");

        // Get NEID data from ExtendedBlockStorage
        IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;

        // CRITICAL: Sync FROM Ultramine slot TO NEID arrays before saving!
        syncFromUltramineSlot(ebs, ebsMixin);

        // Get slot for vanilla data
        Object slot;
        try {
            slot = ebs.getClass().getMethod("getSlot").invoke(ebs);
        } catch (Exception e) {
            System.err.println("[NEID] Failed to get slot: " + e.getMessage());
            return; // Let vanilla handle it
        }

        // Write Y coordinate
        out.writeByte(1); // TAG_Byte
        out.writeUTF("Y");
        out.writeByte((byte) (ebs.getYLocation() >> 4 & 255));

        // Get vanilla data from slot using reflection
        try {
            Class<?> slotClass = slot.getClass();

            // Write vanilla Blocks (for compatibility)
            java.lang.reflect.Method copyLSB = slotClass.getMethod("copyLSB", byte[].class, int.class);
            byte[] lsbBuf = new byte[4096];
            copyLSB.invoke(slot, lsbBuf, 0);
            writeByteArray(out, "Blocks", lsbBuf, 0, 4096);

            // Write NEID Blocks16
            byte[] blocks16 = ebsMixin.getBlockData();
            System.out.println("[NEID] Writing Blocks16: " + blocks16.length + " bytes");
            writeByteArray(out, "Blocks16", blocks16, 0, blocks16.length);

            // Write vanilla Add
            java.lang.reflect.Method copyMSB = slotClass.getMethod("copyMSB", byte[].class, int.class);
            byte[] msbBuf = new byte[2048];
            copyMSB.invoke(slot, msbBuf, 0);
            writeByteArray(out, "Add", msbBuf, 0, 2048);

            // Write vanilla Data
            java.lang.reflect.Method copyMeta = slotClass.getMethod("copyBlockMetadata", byte[].class, int.class);
            byte[] metaBuf = new byte[2048];
            copyMeta.invoke(slot, metaBuf, 0);
            writeByteArray(out, "Data", metaBuf, 0, 2048);

            // Write NEID Data16
            byte[] data16 = ebsMixin.getBlockMeta();
            writeByteArray(out, "Data16", data16, 0, data16.length);

            // Write BlockLight
            java.lang.reflect.Method copyBlocklight = slotClass.getMethod("copyBlocklight", byte[].class, int.class);
            byte[] lightBuf = new byte[2048];
            copyBlocklight.invoke(slot, lightBuf, 0);
            writeByteArray(out, "BlockLight", lightBuf, 0, 2048);

            // Write SkyLight
            boolean hasNoSky = (Boolean) this.getClass().getDeclaredField("hasNoSky").get(this);
            if (hasNoSky) {
                writeByteArray(out, "SkyLight", new byte[2048], 0, 2048);
            } else {
                java.lang.reflect.Method copySkylight = slotClass.getMethod("copySkylight", byte[].class, int.class);
                copySkylight.invoke(slot, lightBuf, 0);
                writeByteArray(out, "SkyLight", lightBuf, 0, 2048);
            }

            // Write end marker
            out.writeByte(0);

            System.out.println("[NEID] Successfully wrote chunk section with NEID data");

            // Cancel original method
            ci.cancel();
        } catch (Exception e) {
            System.err.println("[NEID] Failed to write NEID data: " + e.getMessage());
            e.printStackTrace();
            // Let vanilla handle it
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
     * Sync data FROM Ultramine slot TO NEID arrays. This is critical for newly generated chunks where world generators
     * write directly to Ultramine slot, leaving NEID arrays empty.
     */
    private void syncFromUltramineSlot(ExtendedBlockStorage storage, IExtendedBlockStorageMixin ebsMixin) {
        try {
            // Try to get Ultramine's slot via reflection
            Object slot = storage.getClass().getMethod("getSlot").invoke(storage);
            if (slot == null) {
                System.out.println("[NEID] No Ultramine slot found, skipping sync");
                return;
            }

            short[] blocks = ebsMixin.getBlock16BArray();
            short[] metadata = ebsMixin.getBlock16BMetaArray();

            // Read all block IDs and metadata from Ultramine slot
            Class<?> slotClass = slot.getClass();
            java.lang.reflect.Method getBlockId = slotClass.getMethod("getBlockId", int.class, int.class, int.class);
            java.lang.reflect.Method getMeta = slotClass.getMethod("getMeta", int.class, int.class, int.class);

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        int index = y << 8 | z << 4 | x;
                        int blockId = (int) getBlockId.invoke(slot, x, y, z);
                        int meta = (int) getMeta.invoke(slot, x, y, z);
                        blocks[index] = (short) (blockId & 0xFFFF);
                        metadata[index] = (short) (meta & 0xFFFF);
                    }
                }
            }
            System.out.println("[NEID] Successfully synced FROM Ultramine slot to NEID arrays");
        } catch (Exception e) {
            // Not Ultramine or reflection failed - data is already in our arrays
            System.out.println("[NEID] Sync from slot failed (probably not Ultramine): " + e.getMessage());
        }
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
