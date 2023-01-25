package ru.fewizz.idextender.asm;

import net.minecraft.launchwrapper.*;
import org.apache.logging.log4j.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.*;

public class IETransformer implements IClassTransformer {
    private static final boolean enablePreVerification = false;
    private static final boolean enablePostVerification = true;
    public static final Logger logger;
    public static boolean isObfuscated;
    private static Boolean isClient;

    public byte[] transform(final String name, final String transformedName, final byte[] bytes) {
        if (bytes == null) {
            return bytes;
        }
        final ClassEdit edit = ClassEdit.get(transformedName);
        if (edit == null) {
            return bytes;
        }
        IETransformer.logger.debug("Patching {} with {}...", new Object[] {transformedName, edit.getName()});
        final ClassNode cn = new ClassNode(Opcodes.ASM5);
        final ClassReader reader = new ClassReader(bytes);
        final int readFlags = 0;
        reader.accept((ClassVisitor) cn, 0);
        try {
            edit.getTransformer().transform(cn, IETransformer.isObfuscated);
        } catch (AsmTransformException t) {
            IETransformer.logger.error(
                    "Error transforming {} with {}: {}",
                    new Object[] {transformedName, edit.getName(), t.getMessage()});
            throw t;
        } catch (Throwable t2) {
            IETransformer.logger.error(
                    "Error transforming {} with {}: {}",
                    new Object[] {transformedName, edit.getName(), t2.getMessage()});
            throw new RuntimeException(t2);
        }
        final ClassWriter writer = new ClassWriter(0);
        try {
            final ClassVisitor check = (ClassVisitor) new CheckClassAdapter((ClassVisitor) writer);
            cn.accept(check);
        } catch (Throwable t3) {
            IETransformer.logger.error(
                    "Error verifying {} transformed with {}: {}",
                    new Object[] {transformedName, edit.getName(), t3.getMessage()});
            throw new RuntimeException(t3);
        }
        IETransformer.logger.debug("Patched {} successfully.", new Object[] {edit.getName()});
        return writer.toByteArray();
    }

    public static boolean isClient() {
        if (IETransformer.isClient == null) {
            IETransformer.isClient = (IETransformer.class.getResource("/net/minecraft/client/main/Main.class") != null);
        }
        return IETransformer.isClient;
    }

    static {
        logger = LogManager.getLogger("NEID");
        IETransformer.isClient = null;
    }
}
