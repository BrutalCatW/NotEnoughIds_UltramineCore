package com.gtnewhorizons.neid.mixins.early.minecraft;

import java.io.DataOutput;
import java.io.IOException;

import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
     * Inject NEID tags into NBT after convertToNbt() completes. This is called when chunk is about to be saved to disk.
     */
    @Inject(method = "convertToNbt", at = @At("RETURN"), require = 0, remap = false)
    private void neid$addNeidTags(CallbackInfo ci) {
        // System.out.println("[NEID] EbsSaveFakeNbt.convertToNbt() RETURN - adding NEID tags");

        try {
            IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;
            net.minecraft.nbt.NBTTagCompound nbt = (net.minecraft.nbt.NBTTagCompound) (Object) this;

            // CRITICAL: Add vanilla tags FIRST for Ultramine compatibility!
            // Ultramine's AnvilChunkLoader expects these tags when loading chunks.

            // Add vanilla "Blocks" (LSB, 4096 bytes)
            byte[] vanillaBlocks = ebsMixin.getVanillaBlocks();
            nbt.setByteArray("Blocks", vanillaBlocks);

            // Add vanilla "Add" (MSB, 2048 bytes, optional)
            byte[] vanillaMSB = ebsMixin.getVanillaMSB();
            if (vanillaMSB != null) {
                nbt.setByteArray("Add", vanillaMSB);
            }

            // Add vanilla "Data" (metadata, 2048 bytes)
            byte[] vanillaData = ebsMixin.getVanillaMetadata();
            nbt.setByteArray("Data", vanillaData);

            // Add NEID Blocks16 tag (full 16-bit format)
            byte[] blocks16 = ebsMixin.getBlockData();
            // System.out.println("[NEID] Adding Blocks16 tag: " + blocks16.length + " bytes");
            nbt.setByteArray("Blocks16", blocks16);

            // Add NEID Data16 tag (full 16-bit metadata)
            byte[] data16 = ebsMixin.getBlockMeta();
            // System.out.println("[NEID] Adding Data16 tag: " + data16.length + " bytes");
            nbt.setByteArray("Data16", data16);

            // System.out.println("[NEID] All NBT tags added successfully");
        } catch (Exception e) {
            System.err.println("[NEID] Failed to add NEID tags: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Test injection - log when copy is called
     */
    @Inject(method = "copy", at = @At("HEAD"), require = 0, remap = false)
    private void neid$logCopy(CallbackInfoReturnable<?> cir) {
        // System.out.println("[NEID] EbsSaveFakeNbt.copy() called, isNbt=" + isNbt);
    }

    /**
     * CRITICAL: Add NEID tags to tagMap immediately in constructor! Since EbsSaveFakeNbt extends NBTTagCompound, we can
     * add tags directly to internal tagMap.
     */
    @Inject(method = "<init>", at = @At("RETURN"), require = 0, remap = false)
    private void neid$addNeidTagsInConstructor(ExtendedBlockStorage ebs, boolean hasNoSky, CallbackInfo ci) {
        // System.out.println("[NEID] EbsSaveFakeNbt constructed for Y=" + (ebs.getYLocation() >> 4));

        // Sync data FROM Ultramine slot TO NEID arrays
        IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;
        syncFromUltramineSlot(ebs, ebsMixin);
        // System.out.println("[NEID] Synced data from Ultramine slot");

        // Add NEID tags DIRECTLY to this NBTTagCompound's tagMap!
        try {
            byte[] blocks16 = ebsMixin.getBlockData();
            byte[] data16 = ebsMixin.getBlockMeta();

            // System.out.println("[NEID] Adding Blocks16 (" + blocks16.length + " bytes) to tagMap");
            // System.out.println("[NEID] Adding Data16 (" + data16.length + " bytes) to tagMap");

            // CRITICAL FIX: EbsSaveFakeNbt is created with Collections.emptyMap()!
            // We MUST call createMap() first to make tagMap mutable!
            try {
                java.lang.reflect.Method createMapMethod = net.minecraft.nbt.NBTTagCompound.class
                        .getDeclaredMethod("createMap", int.class);
                createMapMethod.setAccessible(true);
                createMapMethod.invoke(this, 0); // Create mutable map
                // System.out.println("[NEID] Created mutable tagMap via reflection");
            } catch (Exception createMapEx) {
                System.err.println("[NEID] Failed to create mutable map: " + createMapEx.getMessage());
                createMapEx.printStackTrace();
                return; // Can't continue without mutable map
            }

            // Now add our tags
            net.minecraft.nbt.NBTTagCompound nbt = (net.minecraft.nbt.NBTTagCompound) (Object) this;
            nbt.setByteArray("Blocks16", blocks16);
            nbt.setByteArray("Data16", data16);

            // System.out.println("[NEID] NEID tags added to tagMap successfully!");

            // CRITICAL: Now sync BACK to Ultramine slot for client!
            // This ensures ChunkSnapshot reads correct 16-bit IDs when sending to client
            syncNeidToUltramineSlot(ebs, ebsMixin);
            // System.out.println("[NEID] Synced NEID arrays back to MemSlot for client");
        } catch (Exception e) {
            System.err.println("[NEID] FAILED to add NEID tags: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Redirect the final writeByte(0) to inject NEID tags before it
     */
    @Redirect(
            method = "write",
            at = @At(value = "INVOKE", target = "Ljava/io/DataOutput;writeByte(I)V"),
            require = 0,
            remap = false)
    private void neid$writeNeidBeforeEnd(DataOutput out, int endMarker) throws IOException {
        // System.out.println("[NEID] Redirecting writeByte(0) to add NEID tags");

        // Only add NEID tags if NOT in NBT form (meaning write() is serializing from slot)
        if (!isNbt) {
            IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;

            // CRITICAL: Write vanilla tags FIRST for Ultramine compatibility!
            // Ultramine's AnvilChunkLoader expects these tags in specific format.
            // If missing or wrong size, getByteArray() returns empty array and slot.setData() crashes!

            // Write vanilla "Blocks" (LSB, 4096 bytes)
            byte[] vanillaBlocks = ebsMixin.getVanillaBlocks();
            // System.out.println("[NEID] Writing vanilla Blocks: " + vanillaBlocks.length + " bytes");
            writeByteArray(out, "Blocks", vanillaBlocks, 0, vanillaBlocks.length);

            // Write vanilla "Add" (MSB, 2048 bytes, optional)
            byte[] vanillaMSB = ebsMixin.getVanillaMSB();
            if (vanillaMSB != null) {
                // System.out.println("[NEID] Writing vanilla Add: " + vanillaMSB.length + " bytes");
                writeByteArray(out, "Add", vanillaMSB, 0, vanillaMSB.length);
            }

            // Write vanilla "Data" (metadata, 2048 bytes)
            byte[] vanillaData = ebsMixin.getVanillaMetadata();
            // System.out.println("[NEID] Writing vanilla Data: " + vanillaData.length + " bytes");
            writeByteArray(out, "Data", vanillaData, 0, vanillaData.length);

            // Write NEID Blocks16 (full 16-bit block IDs)
            byte[] blocks16 = ebsMixin.getBlockData();
            // System.out.println("[NEID] Writing Blocks16: " + blocks16.length + " bytes");
            writeByteArray(out, "Blocks16", blocks16, 0, blocks16.length);

            // Write NEID Data16 (full 16-bit metadata)
            byte[] data16 = ebsMixin.getBlockMeta();
            writeByteArray(out, "Data16", data16, 0, data16.length);
        }

        // Write the end marker
        out.writeByte(endMarker);
        // System.out.println("[NEID] Wrote end marker");
    }

    /**
     * Log when write() is called
     */
    @Inject(method = "write", at = @At("HEAD"), require = 0, remap = false)
    private void neid$logWrite(DataOutput out, CallbackInfo ci) {
        // System.out
        // .println("[NEID] EbsSaveFakeNbt.write() called - isNbt=" + isNbt + ", Y=" + (ebs.getYLocation() >> 4));
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
                // System.out.println("[NEID] No Ultramine slot found, skipping sync");
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
            // System.out.println("[NEID] Successfully synced FROM Ultramine slot to NEID arrays");
        } catch (Exception e) {
            // Not Ultramine or reflection failed - data is already in our arrays
            // System.out.println("[NEID] Sync from slot failed (probably not Ultramine): " + e.getMessage());
        }
    }

    /**
     * Sync NEID arrays TO Ultramine MemSlot. This is critical for client synchronization - the client receives chunk
     * data from MemSlot via ChunkSnapshot, so we must ensure MemSlot has the correct 16-bit block IDs from NEID arrays.
     */
    private void syncNeidToUltramineSlot(ExtendedBlockStorage storage, IExtendedBlockStorageMixin ebsMixin) {
        try {
            Object slot = storage.getClass().getMethod("getSlot").invoke(storage);
            if (slot == null) {
                // System.out.println("[NEID] No Ultramine slot found, skipping reverse sync");
                return;
            }

            short[] blocks = ebsMixin.getBlock16BArray();
            short[] metadata = ebsMixin.getBlock16BMetaArray();

            // Write all block IDs and metadata to Ultramine slot
            Class<?> slotClass = slot.getClass();
            java.lang.reflect.Method setBlockId = slotClass
                    .getMethod("setBlockId", int.class, int.class, int.class, int.class);
            java.lang.reflect.Method setMeta = slotClass
                    .getMethod("setMeta", int.class, int.class, int.class, int.class);

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        int index = y << 8 | z << 4 | x;
                        int blockId = blocks[index] & 0xFFFF;
                        int meta = metadata[index] & 0xFFFF;
                        setBlockId.invoke(slot, x, y, z, blockId);
                        setMeta.invoke(slot, x, y, z, meta);
                    }
                }
            }
            // System.out.println("[NEID] Successfully synced " + blocks.length + " blocks to Ultramine slot");
        } catch (Exception e) {
            System.err.println("[NEID] Failed to sync NEID to slot: " + e.getMessage());
            e.printStackTrace();
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
