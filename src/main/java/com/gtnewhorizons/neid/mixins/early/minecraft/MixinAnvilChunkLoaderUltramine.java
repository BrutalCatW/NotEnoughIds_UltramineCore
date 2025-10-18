package com.gtnewhorizons.neid.mixins.early.minecraft;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import com.gtnewhorizons.neid.mixins.interfaces.IExtendedBlockStorageMixin;

/**
 * Mixin for Ultramine's AnvilChunkLoader to handle NEID 16-bit block format loading. Intercepts chunk loading to read
 * Blocks16/Data16 tags if present.
 */
@Mixin(value = AnvilChunkLoader.class, remap = false)
public class MixinAnvilChunkLoaderUltramine {

    /**
     * CRITICAL: Inject into EbsSaveFakeNbt loading path (when chunk loaded from memory, not disk). This is called when
     * Ultramine detects instanceof EbsSaveFakeNbt and tries to copy the EBS directly. We need to load NEID tags from
     * the EbsSaveFakeNbt's tagMap BEFORE it calls getEbs().copy().
     */
    @Inject(
            method = "readChunkFromNBT",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/nbt/EbsSaveFakeNbt;getEbs()Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;",
                    shift = At.Shift.BEFORE),
            locals = LocalCapture.CAPTURE_FAILHARD,
            require = 0,
            remap = false)
    private void neid$loadFromEbsSaveFakeNbt(World par1World, NBTTagCompound par2NBTTagCompound,
            CallbackInfoReturnable<Chunk> cir, int i, int j, Chunk chunk, NBTTagList nbttaglist, int b0,
            ExtendedBlockStorage[] aextendedblockstorage, boolean flag, int k, NBTTagCompound nbttagcompound1) {

        System.out.println("[NEID] Loading EbsSaveFakeNbt from memory - checking for NEID tags in tagMap");

        // Check if NEID format exists in EbsSaveFakeNbt's tagMap
        if (nbttagcompound1.hasKey("Blocks16") && nbttagcompound1.hasKey("Data16")) {
            System.out.println("[NEID] Found Blocks16/Data16 in EbsSaveFakeNbt tagMap!");

            try {
                // Get the ExtendedBlockStorage from EbsSaveFakeNbt
                Object ebsSaveFakeNbt = nbttagcompound1;
                ExtendedBlockStorage ebs = (ExtendedBlockStorage) ebsSaveFakeNbt.getClass().getMethod("getEbs")
                        .invoke(ebsSaveFakeNbt);

                IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;

                // Load NEID 16-bit format from tagMap
                byte[] blocks16 = nbttagcompound1.getByteArray("Blocks16");
                byte[] data16 = nbttagcompound1.getByteArray("Data16");

                System.out.println(
                        "[NEID] Loaded " + blocks16.length + " bytes Blocks16, " + data16.length + " bytes Data16");

                ebsMixin.setBlockData(blocks16, 0);
                ebsMixin.setBlockMeta(data16, 0);

                // Sync to Ultramine slot
                syncNeidToUltramineSlot(ebs, ebsMixin);

                System.out.println("[NEID] Successfully loaded NEID data from EbsSaveFakeNbt!");
            } catch (Exception e) {
                System.err.println("[NEID] Failed to load from EbsSaveFakeNbt: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("[NEID] No NEID tags in EbsSaveFakeNbt");
        }
    }

    /**
     * Inject after ExtendedBlockStorage is created but before slot.setData() is called. We check if NEID format
     * (Blocks16/Data16) exists and load it into our arrays.
     */
    @Inject(
            method = "readChunkFromNBT",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ultramine/server/chunk/alloc/MemSlot;setData([B[B[B[B[B)V",
                    shift = At.Shift.AFTER),
            locals = LocalCapture.CAPTURE_FAILHARD,
            require = 0,
            remap = false)
    private void neid$loadNeidFormat(World par1World, NBTTagCompound par2NBTTagCompound,
            CallbackInfoReturnable<Chunk> cir, int i, int j, Chunk chunk, NBTTagList nbttaglist, int b0,
            ExtendedBlockStorage[] aextendedblockstorage, boolean flag, int k, NBTTagCompound nbttagcompound1, byte b1,
            ExtendedBlockStorage extendedblockstorage) {

        System.out.println("[NEID] Loading chunk section, NBT class: " + nbttagcompound1.getClass().getName());

        // Skip if this is an EbsSaveFakeNbt (already loaded) - check via class name to avoid compile dependency
        if (nbttagcompound1.getClass().getName().equals("net.minecraft.nbt.EbsSaveFakeNbt")) {
            System.out.println("[NEID] Skipping EbsSaveFakeNbt - already loaded from memory");
            return;
        }

        // Check if NEID format exists
        if (nbttagcompound1.hasKey("Blocks16") && nbttagcompound1.hasKey("Data16")) {
            System.out.println("[NEID] Found Blocks16/Data16 tags, loading NEID format");
            IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) extendedblockstorage;

            // Load NEID 16-bit format
            byte[] blocks16 = nbttagcompound1.getByteArray("Blocks16");
            byte[] data16 = nbttagcompound1.getByteArray("Data16");

            ebsMixin.setBlockData(blocks16, 0);
            ebsMixin.setBlockMeta(data16, 0);

            // CRITICAL: Sync NEID arrays to Ultramine slot
            // The slot.setData() was already called with vanilla format,
            // but we need to overwrite it with NEID data
            syncNeidToUltramineSlot(extendedblockstorage, ebsMixin);
        } else {
            System.out.println("[NEID] No Blocks16/Data16 tags found, using vanilla format");
        }
    }

    /**
     * Synchronize NEID arrays to Ultramine's MemSlot after loading. This ensures Ultramine's off-heap storage has the
     * correct 16-bit block IDs.
     */
    private void syncNeidToUltramineSlot(ExtendedBlockStorage storage, IExtendedBlockStorageMixin ebsMixin) {
        try {
            Object slot = storage.getClass().getMethod("getSlot").invoke(storage);
            if (slot == null) return;

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
            System.out.println("[NEID] Successfully synced " + (blocks.length) + " blocks to Ultramine slot");
        } catch (Exception e) {
            // Ultramine slot not available or reflection failed
            System.err.println("[NEID] Failed to sync to Ultramine slot during load: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
