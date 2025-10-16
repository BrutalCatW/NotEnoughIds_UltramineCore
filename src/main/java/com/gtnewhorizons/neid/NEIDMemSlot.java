package com.gtnewhorizons.neid;

import javax.annotation.Nonnull;

/**
 * NEID implementation of MemSlot that stores block data in on-heap short arrays instead of off-heap memory. This allows
 * for 16-bit block IDs and metadata while maintaining compatibility with Ultramine's new chunk storage architecture.
 *
 * This class implements the MemSlot interface at runtime through duck typing. It does not directly implement the
 * interface at compile time to avoid dependencies on Ultramine Core classes.
 */
public class NEIDMemSlot {

    private static Object allocService; // ChunkAllocService at runtime
    private static boolean allocServiceInitialized = false;

    private static synchronized Object getAllocService() {
        if (!allocServiceInitialized) {
            allocServiceInitialized = true;
            // Try to get Ultramine's ChunkAllocService on first use
            try {
                Class<?> serviceManagerClass = Class.forName("org.ultramine.core.service.ServiceManager");
                Object serviceManager = serviceManagerClass.getMethod("instance").invoke(null);
                Class<?> allocServiceClass = Class.forName("org.ultramine.server.chunk.alloc.ChunkAllocService");
                allocService = serviceManagerClass.getMethod("getService", Class.class)
                        .invoke(serviceManager, allocServiceClass);
            } catch (Exception e) {
                // Ultramine Core not present, allocService will be null
                allocService = null;
            }
        }
        return allocService;
    }

    // NEID uses 16-bit (short) arrays for block IDs and metadata
    private final short[] blocks; // 16-bit block IDs (4096 blocks)
    private final short[] metadata; // 16-bit metadata (4096 blocks)
    private final byte[] blockLight; // 4-bit block light (2048 bytes)
    private final byte[] skyLight; // 4-bit sky light (2048 bytes)

    private static final int BLOCKS_PER_EBS = Constants.BLOCKS_PER_EBS; // 4096

    public NEIDMemSlot() {
        this.blocks = new short[BLOCKS_PER_EBS];
        this.metadata = new short[BLOCKS_PER_EBS];
        this.blockLight = new byte[2048];
        this.skyLight = new byte[2048];
    }

    // Direct access to NEID arrays
    public short[] getBlocksArray() {
        return blocks;
    }

    public short[] getMetadataArray() {
        return metadata;
    }

    // MemSlot API implementation (duck-typed, not implementing interface directly)

    public void setLSB(byte[] arr, int start) {
        if (arr == null || arr.length - start < 4096) {
            throw new IllegalArgumentException("Invalid LSB array size");
        }
        // LSB contains lower 8 bits of block ID
        for (int i = 0; i < 4096; i++) {
            blocks[i] = (short) ((blocks[i] & 0xFF00) | (arr[start + i] & 0xFF));
        }
    }

    public void setMSB(byte[] arr, int start) {
        if (arr == null || arr.length - start < 2048) {
            throw new IllegalArgumentException("Invalid MSB array size");
        }
        // MSB contains upper 4 bits of block ID (nibble array)
        for (int i = 0; i < 2048; i++) {
            byte nibble = arr[start + i];
            int idx1 = i * 2;
            int idx2 = i * 2 + 1;
            blocks[idx1] = (short) ((blocks[idx1] & 0x00FF) | ((nibble & 0x0F) << 8));
            blocks[idx2] = (short) ((blocks[idx2] & 0x00FF) | ((nibble & 0xF0) << 4));
        }
    }

    public void setBlockMetadata(byte[] arr, int start) {
        if (arr == null || arr.length - start < 2048) {
            throw new IllegalArgumentException("Invalid metadata array size");
        }
        // Metadata is stored as nibble array (4-bit values)
        for (int i = 0; i < 2048; i++) {
            byte nibble = arr[start + i];
            int idx1 = i * 2;
            int idx2 = i * 2 + 1;
            metadata[idx1] = (short) (nibble & 0x0F);
            metadata[idx2] = (short) ((nibble >> 4) & 0x0F);
        }
    }

    public void setBlocklight(byte[] arr, int start) {
        if (arr == null || arr.length - start < 2048) {
            throw new IllegalArgumentException("Invalid block light array size");
        }
        System.arraycopy(arr, start, blockLight, 0, 2048);
    }

    public void setSkylight(byte[] arr, int start) {
        if (arr == null || arr.length - start < 2048) {
            throw new IllegalArgumentException("Invalid sky light array size");
        }
        System.arraycopy(arr, start, skyLight, 0, 2048);
    }

    public void setData(@Nonnull byte[] lsb, @javax.annotation.Nullable byte[] msb, @Nonnull byte[] meta,
            @Nonnull byte[] blockLight, @javax.annotation.Nullable byte[] skyLight) {
        // Optimized setData for NEID - directly combines LSB and MSB into 16-bit blocks
        if (lsb == null || lsb.length < 4096) {
            throw new IllegalArgumentException("Invalid LSB array size");
        }
        if (meta == null || meta.length < 2048) {
            throw new IllegalArgumentException("Invalid metadata array size");
        }
        if (blockLight == null || blockLight.length < 2048) {
            throw new IllegalArgumentException("Invalid block light array size");
        }

        // Combine LSB and MSB into 16-bit block IDs
        if (msb != null && msb.length >= 2048) {
            for (int i = 0; i < 4096; i++) {
                int lsbByte = lsb[i] & 0xFF;
                int msbNibble;
                if ((i & 1) == 0) {
                    msbNibble = (msb[i >> 1] & 0x0F) << 8;
                } else {
                    msbNibble = (msb[i >> 1] & 0xF0) << 4;
                }
                blocks[i] = (short) (lsbByte | msbNibble);
            }
        } else {
            // No MSB data, just use LSB
            for (int i = 0; i < 4096; i++) {
                blocks[i] = (short) (lsb[i] & 0xFF);
            }
        }

        // Unpack metadata nibbles into 16-bit array
        for (int i = 0; i < 2048; i++) {
            byte metaByte = meta[i];
            metadata[i * 2] = (short) (metaByte & 0x0F);
            metadata[i * 2 + 1] = (short) ((metaByte >> 4) & 0x0F);
        }

        // Copy light data
        System.arraycopy(blockLight, 0, this.blockLight, 0, 2048);
        if (skyLight != null && skyLight.length >= 2048) {
            System.arraycopy(skyLight, 0, this.skyLight, 0, 2048);
        } else {
            zerofillSkylight();
        }
    }

    public void copyLSB(byte[] arr, int start) {
        if (arr == null || arr.length - start < 4096) {
            throw new IllegalArgumentException("Invalid LSB array size");
        }
        // Copy lower 8 bits of each block ID
        for (int i = 0; i < 4096; i++) {
            arr[start + i] = (byte) (blocks[i] & 0xFF);
        }
    }

    public void copyMSB(byte[] arr, int start) {
        if (arr == null || arr.length - start < 2048) {
            throw new IllegalArgumentException("Invalid MSB array size");
        }
        // Copy upper 4 bits of each block ID as nibble array
        for (int i = 0; i < 2048; i++) {
            int idx1 = i * 2;
            int idx2 = i * 2 + 1;
            byte msb1 = (byte) ((blocks[idx1] >> 8) & 0x0F);
            byte msb2 = (byte) ((blocks[idx2] >> 8) & 0x0F);
            arr[start + i] = (byte) (msb1 | (msb2 << 4));
        }
    }

    public void copyBlockMetadata(byte[] arr, int start) {
        if (arr == null || arr.length - start < 2048) {
            throw new IllegalArgumentException("Invalid metadata array size");
        }
        // Pack metadata as nibble array
        for (int i = 0; i < 2048; i++) {
            int idx1 = i * 2;
            int idx2 = i * 2 + 1;
            byte meta1 = (byte) (metadata[idx1] & 0x0F);
            byte meta2 = (byte) (metadata[idx2] & 0x0F);
            arr[start + i] = (byte) (meta1 | (meta2 << 4));
        }
    }

    public void copyBlocklight(byte[] arr, int start) {
        if (arr == null || arr.length - start < 2048) {
            throw new IllegalArgumentException("Invalid block light array size");
        }
        System.arraycopy(blockLight, 0, arr, start, 2048);
    }

    public void copySkylight(byte[] arr, int start) {
        if (arr == null || arr.length - start < 2048) {
            throw new IllegalArgumentException("Invalid sky light array size");
        }
        System.arraycopy(skyLight, 0, arr, start, 2048);
    }

    public void zerofillMSB() {
        // Zero out upper 8 bits of all block IDs
        for (int i = 0; i < BLOCKS_PER_EBS; i++) {
            blocks[i] = (short) (blocks[i] & 0x00FF);
        }
    }

    public void zerofillSkylight() {
        for (int i = 0; i < 2048; i++) {
            skyLight[i] = 0;
        }
    }

    public void zerofillAll() {
        for (int i = 0; i < BLOCKS_PER_EBS; i++) {
            blocks[i] = 0;
            metadata[i] = 0;
        }
        for (int i = 0; i < 2048; i++) {
            blockLight[i] = 0;
            skyLight[i] = 0;
        }
    }

    public int getBlockId(int x, int y, int z) {
        return blocks[y << 8 | z << 4 | x] & 0xFFFF;
    }

    public void setBlockId(int x, int y, int z, int id) {
        blocks[y << 8 | z << 4 | x] = (short) id;
    }

    public int getMeta(int x, int y, int z) {
        return metadata[y << 8 | z << 4 | x] & 0xFFFF;
    }

    public void setMeta(int x, int y, int z, int meta) {
        metadata[y << 8 | z << 4 | x] = (short) meta;
    }

    public int getBlocklight(int x, int y, int z) {
        return get4bits(blockLight, x, y, z);
    }

    public void setBlocklight(int x, int y, int z, int val) {
        set4bits(blockLight, x, y, z, val);
    }

    public int getSkylight(int x, int y, int z) {
        return get4bits(skyLight, x, y, z);
    }

    public void setSkylight(int x, int y, int z, int val) {
        set4bits(skyLight, x, y, z, val);
    }

    private int get4bits(byte[] arr, int x, int y, int z) {
        int ind = y << 8 | z << 4 | x;
        byte data = arr[ind >> 1];
        return (ind & 1) == 0 ? data & 15 : data >> 4 & 15;
    }

    private void set4bits(byte[] arr, int x, int y, int z, int val) {
        int ind = y << 8 | z << 4 | x;
        int off = ind >> 1;
        if ((ind & 1) == 0) {
            arr[off] = (byte) ((arr[off] & 0xF0) | (val & 0x0F));
        } else {
            arr[off] = (byte) ((arr[off] & 0x0F) | ((val & 0x0F) << 4));
        }
    }

    // MemSlot interface methods (called via duck typing at runtime)

    public Object getAlloc() {
        return getAllocService();
    }

    public void copyFrom(@Nonnull Object src) {
        if (!(src instanceof NEIDMemSlot)) {
            throw new IllegalArgumentException("Can only copy from another NEIDMemSlot");
        }
        NEIDMemSlot other = (NEIDMemSlot) src;
        System.arraycopy(other.blocks, 0, this.blocks, 0, BLOCKS_PER_EBS);
        System.arraycopy(other.metadata, 0, this.metadata, 0, BLOCKS_PER_EBS);
        System.arraycopy(other.blockLight, 0, this.blockLight, 0, 2048);
        System.arraycopy(other.skyLight, 0, this.skyLight, 0, 2048);
    }

    @Nonnull
    public Object copy() {
        NEIDMemSlot copy = new NEIDMemSlot();
        copy.copyFrom(this);
        return copy;
    }

    public void release() {
        // NEID uses on-heap storage, so no off-heap memory to release
        // Arrays will be garbage collected automatically
    }
}
