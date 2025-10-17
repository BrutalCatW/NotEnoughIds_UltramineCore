package com.gtnewhorizons.neid;

import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@Mod(
        modid = "neid",
        name = "NotEnoughIDs",
        version = Tags.VERSION,
        dependencies = "after:battlegear2@[1.3.0,);" + " required-after:gtnhlib@[0.6.18,);")
public class NEID {

    // Force early loading of NEIDChunkAllocService to register with Ultramine
    static {
        // Just reference the class to trigger its static initializer
        Class<?> unused = NEIDChunkAllocService.class;
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        try {
            ConfigurationManager.registerConfig(NEIDConfig.class);
        } catch (ConfigException e) {
            throw new RuntimeException("Failed to register NotEnoughIDs config!");
        }
        // NEIDChunkAllocService is registered automatically via static initializer
    }

}
