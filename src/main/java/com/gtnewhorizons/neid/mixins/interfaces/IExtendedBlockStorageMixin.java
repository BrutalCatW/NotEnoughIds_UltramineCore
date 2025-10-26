package com.gtnewhorizons.neid.mixins.interfaces;

public interface IExtendedBlockStorageMixin {

    short[] getBlock16BArray();

    short[] getBlock16BMetaArray();

    byte[] getBlockData();

    byte[] getBlockMeta();

    void setBlockData(byte[] data, int offset);

    void setBlockMeta(byte[] data, int offset);

    // Vanilla-compatible data format for Ultramine (4-bit metadata, 12-bit block IDs)
    byte[] getVanillaMetadata();

    byte[] getVanillaBlocks();

    byte[] getVanillaMSB();

}
