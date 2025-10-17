package com.gtnewhorizons.neid;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkDataEvent;

import com.gtnewhorizons.neid.mixins.interfaces.IExtendedBlockStorageMixin;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Handles saving and loading of NEID chunk data (16-bit block IDs) via Forge events. This works on both vanilla Forge
 * and Ultramine Core without requiring core modifications.
 */
public class NEIDChunkDataHandler {

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new NEIDChunkDataHandler());
    }

    @SubscribeEvent
    public void onChunkSave(ChunkDataEvent.Save event) {
        Chunk chunk = event.getChunk();
        NBTTagCompound chunkNBT = event.getData();

        // Get the Level compound where chunk data is stored
        // Don't try to create or modify it - it already exists
        if (!chunkNBT.hasKey("Level")) {
            return; // No Level data, nothing to save
        }

        NBTTagCompound levelData = chunkNBT.getCompoundTag("Level");

        // Get the Sections list
        NBTTagList sectionsList = levelData.getTagList("Sections", 10);

        ExtendedBlockStorage[] storageArrays = chunk.getBlockStorageArray();

        for (int i = 0; i < sectionsList.tagCount(); i++) {
            NBTTagCompound sectionNBT = sectionsList.getCompoundTagAt(i);
            int y = sectionNBT.getByte("Y");

            if (y >= 0 && y < storageArrays.length && storageArrays[y] != null) {
                ExtendedBlockStorage storage = storageArrays[y];
                IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) storage;

                // CRITICAL: Sync data from Ultramine's MemSlot to our arrays first
                syncFromUltramineSlot(storage, ebsMixin);

                // Write NEID 16-bit format
                sectionNBT.setByteArray("Blocks16", ebsMixin.getBlockData());
                sectionNBT.setByteArray("Data16", ebsMixin.getBlockMeta());

                // Also write vanilla format for backwards compatibility if enabled
                if (NEIDConfig.PostNeidWorldsSupport) {
                    writeVanillaFormat(sectionNBT, ebsMixin.getBlock16BArray(), ebsMixin.getBlock16BMetaArray());
                }
            }
        }
    }

    /**
     * Syncs block data from Ultramine's MemSlot to NEID arrays. On Ultramine, blocks are stored in MemSlot; we need to
     * copy them to our arrays.
     */
    private void syncFromUltramineSlot(ExtendedBlockStorage storage, IExtendedBlockStorageMixin ebsMixin) {
        try {
            // Try to get Ultramine's slot via reflection
            Object slot = storage.getClass().getMethod("getSlot").invoke(storage);
            if (slot == null) return;

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
        } catch (Exception e) {
            // Not Ultramine or reflection failed - data is already in our arrays
        }
    }

    /**
     * Syncs block data from NEID arrays to Ultramine's MemSlot. After loading chunk from NBT, we need to populate
     * Ultramine's slot with our data.
     */
    private void syncToUltramineSlot(ExtendedBlockStorage storage, IExtendedBlockStorageMixin ebsMixin) {
        try {
            // Try to get Ultramine's slot via reflection
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
        } catch (Exception e) {
            // Not Ultramine or reflection failed - slot doesn't exist
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkDataEvent.Load event) {
        Chunk chunk = event.getChunk();
        NBTTagCompound chunkNBT = event.getData();

        // Get the Level compound
        if (!chunkNBT.hasKey("Level")) {
            return;
        }

        NBTTagCompound levelData = chunkNBT.getCompoundTag("Level");
        NBTTagList sectionsList = levelData.getTagList("Sections", 10);

        ExtendedBlockStorage[] storageArrays = chunk.getBlockStorageArray();

        for (int i = 0; i < sectionsList.tagCount(); i++) {
            NBTTagCompound sectionNBT = sectionsList.getCompoundTagAt(i);
            int y = sectionNBT.getByte("Y");

            if (y >= 0 && y < storageArrays.length && storageArrays[y] != null) {
                ExtendedBlockStorage storage = storageArrays[y];
                IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) storage;

                // Try to read NEID 16-bit format first
                if (sectionNBT.hasKey("Blocks16")) {
                    ebsMixin.setBlockData(sectionNBT.getByteArray("Blocks16"), 0);
                } else if (sectionNBT.hasKey("Blocks")) {
                    // Fallback to vanilla format
                    readVanillaFormat(sectionNBT, ebsMixin.getBlock16BArray());
                }

                if (sectionNBT.hasKey("Data16")) {
                    ebsMixin.setBlockMeta(sectionNBT.getByteArray("Data16"), 0);
                } else if (sectionNBT.hasKey("Data")) {
                    // Fallback to vanilla metadata format
                    readVanillaMetadata(sectionNBT, ebsMixin.getBlock16BMetaArray());
                }

                // CRITICAL: Sync data from NEID arrays to Ultramine's MemSlot after loading
                syncToUltramineSlot(storage, ebsMixin);
            }
        }
    }

    private void writeVanillaFormat(NBTTagCompound nbt, short[] blocks, short[] metadata) {
        byte[] lsbData = new byte[blocks.length];
        byte[] msbData = null;

        // Convert 16-bit blocks to LSB + MSB nibble format
        for (int i = 0; i < blocks.length; i++) {
            int id = blocks[i] & 0xFFFF;
            if (id <= 255) {
                lsbData[i] = (byte) id;
            } else if (id <= Constants.VANILLA_MAX_BLOCK_ID) {
                if (msbData == null) {
                    msbData = new byte[blocks.length / 2];
                }
                lsbData[i] = (byte) id;
                if (i % 2 == 0) {
                    msbData[i / 2] |= (byte) (id >>> 8 & 0xF);
                } else {
                    msbData[i / 2] |= (byte) (id >>> 4 & 0xF0);
                }
            }
        }

        nbt.setByteArray("Blocks", lsbData);
        if (msbData != null) {
            nbt.setByteArray("Add", msbData);
        }

        // Write metadata in vanilla nibble format
        byte[] metaData = new byte[metadata.length / 2];
        for (int i = 0; i < metadata.length; i += 2) {
            int meta1 = metadata[i] & 0xF;
            int meta2 = metadata[i + 1] & 0xF;
            metaData[i / 2] = (byte) (meta2 << 4 | meta1);
        }
        nbt.setByteArray("Data", metaData);
    }

    private void readVanillaFormat(NBTTagCompound nbt, short[] outBlocks) {
        byte[] lsbData = nbt.getByteArray("Blocks");

        if (nbt.hasKey("Add")) {
            // Has MSB data
            byte[] msbData = nbt.getByteArray("Add");
            for (int i = 0; i < outBlocks.length; i += 2) {
                byte msPart = msbData[i / 2];
                outBlocks[i] = (short) ((lsbData[i] & 0xFF) | (msPart & 0xF) << 8);
                outBlocks[i + 1] = (short) ((lsbData[i + 1] & 0xFF) | (msPart & 0xF0) << 4);
            }
        } else {
            // Only LSB
            for (int i = 0; i < outBlocks.length; i++) {
                outBlocks[i] = (short) (lsbData[i] & 0xFF);
            }
        }
    }

    private void readVanillaMetadata(NBTTagCompound nbt, short[] outMetadata) {
        byte[] metaData = nbt.getByteArray("Data");

        for (int i = 0; i < outMetadata.length; i += 2) {
            byte meta = metaData[i / 2];
            outMetadata[i] = (short) (meta & 0xF);
            outMetadata[i + 1] = (short) ((meta >> 4) & 0xF);
        }
    }
}
