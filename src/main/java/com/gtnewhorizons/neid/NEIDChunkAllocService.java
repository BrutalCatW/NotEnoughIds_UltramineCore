package com.gtnewhorizons.neid;

import javax.annotation.Nonnull;

/**
 * NEID implementation of ChunkAllocService that allocates NEIDMemSlot instead of Ultramine's Unsafe7MemSlot. This
 * service is registered at runtime to replace Ultramine's default allocator.
 */
public class NEIDChunkAllocService {

    static {
        // Register service ASAP when class is loaded
        registerService();
    }

    private static void registerService() {
        try {
            // Try to access Ultramine's ServiceManager
            Class<?> serviceManagerClass = Class.forName("org.ultramine.core.service.ServiceManager");
            Object serviceManager = serviceManagerClass.getMethod("instance").invoke(null);

            // Get ChunkAllocService class
            Class<?> allocServiceClass = Class.forName("org.ultramine.server.chunk.alloc.ChunkAllocService");

            // Register our NEIDChunkAllocService with priority 1000 (much higher than Ultramine's 0)
            serviceManagerClass.getMethod("register", Class.class, Object.class, int.class)
                    .invoke(serviceManager, allocServiceClass, new NEIDChunkAllocService(), 1000);

            System.out.println("[NEID] Successfully registered NEIDChunkAllocService with priority 1000");
        } catch (ClassNotFoundException e) {
            // Not running on Ultramine - this is fine
            System.out.println("[NEID] Running on standard Forge (Ultramine not detected)");
        } catch (Exception e) {
            System.err.println("[NEID] Failed to register Ultramine service: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Allocates a new NEIDMemSlot for chunk storage. This method is called by Ultramine's ExtendedBlockStorage
     * constructor.
     */
    @Nonnull
    public Object allocateSlot() {
        return new NEIDMemSlot();
    }

    /**
     * Returns total off-heap memory used. NEID uses on-heap memory, so this returns 0.
     */
    public long getOffHeapTotalMemory() {
        return 0; // NEID uses on-heap storage
    }

    /**
     * Returns used off-heap memory. NEID uses on-heap memory, so this returns 0.
     */
    public long getOffHeapUsedMemory() {
        return 0; // NEID uses on-heap storage
    }
}
