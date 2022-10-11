package ru.fewizz.idextender.asm.transformer;

import ru.fewizz.idextender.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

public class VanillaNetHandlerPlayClient implements IClassNodeTransformer
{
    @Override
    public void transform(final ClassNode cn, final boolean obfuscated) {
        final MethodNode method = AsmUtil.findMethod(cn, Name.nhpc_handleMultiBlockChange);
        final InsnList code = method.instructions;
        int part = 0;
        final ListIterator<AbstractInsnNode> iterator = (ListIterator<AbstractInsnNode>)code.iterator();
        while (iterator.hasNext()) {
            AbstractInsnNode insn = iterator.next();
            if (part == 0) {
                if (insn.getOpcode() != 25 || ((VarInsnNode)insn).var != 4) {
                    continue;
                }
                ++part;
            }
            else if (part == 1) {
                if (insn.getOpcode() != 25 || ((VarInsnNode)insn).var != 4) {
                    continue;
                }
                iterator.remove();
                insn = iterator.next();
                iterator.set((AbstractInsnNode)new InsnNode(3));
                ++part;
            }
            else if (part == 2) {
                if (insn.getOpcode() != 21 || ((VarInsnNode)insn).var != 7) {
                    continue;
                }
                iterator.set((AbstractInsnNode)new VarInsnNode(25, 4));
                iterator.add((AbstractInsnNode)new MethodInsnNode(182, "java/io/DataInputStream", "readShort", "()S", false));
                ++part;
            }
            else if (part == 3) {
                if (insn.getOpcode() == 54) {
                    ++part;
                }
                else {
                    iterator.remove();
                }
            }
            else if (part == 4) {
                if (insn.getOpcode() != 21 || ((VarInsnNode)insn).var != 7) {
                    continue;
                }
                iterator.set((AbstractInsnNode)new VarInsnNode(25, 4));
                iterator.add((AbstractInsnNode)new MethodInsnNode(182, "java/io/DataInputStream", "readByte", "()B", false));
                iterator.add((AbstractInsnNode)new IntInsnNode(16, 15));
                iterator.add((AbstractInsnNode)new InsnNode(126));
                ++part;
            }
            else {
                if (part != 5) {
                    continue;
                }
                if (insn.getOpcode() == 54) {
                    return;
                }
                iterator.remove();
            }
        }
        throw new AsmTransformException("no match for part " + part);
    }
}
