package net.rasanovum.roxy.loader;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites the Minecraft calls Voxy makes that Minecraft 1.20.1 cannot satisfy into static calls on
 * {@code net.rasanovum.roxy.bridge.RoxyBridge}.
 *
 * A method call {@code Owner.member(args)Ret} becomes
 * {@code RoxyBridge.mNNNN(Owner, args)Ret}, and a field read becomes {@code RoxyBridge.mNNNN(Owner)}.
 * The bridge list is produced by tools/roxy-mappings/make_bridges.py from the link checker, so the
 * decision of what needs a bridge is always measured rather than guessed.
 */
final class RoxyBridgeRedirect extends ClassVisitor {
    private static final String BRIDGE_OWNER = "net/rasanovum/roxy/bridge/RoxyBridge";

    private final Map<String, String> methodBridges = new HashMap<>();
    private final Map<String, String> fieldBridges = new HashMap<>();
    private final Set<String> known = new HashSet<>();

    RoxyBridgeRedirect(ClassVisitor next, Path bridgeList) {
        super(Opcodes.ASM9, next);
        if (bridgeList != null && Files.exists(bridgeList)) {
            load(bridgeList);
        }
    }

    private void load(Path file) {
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t");
                String key = parts[1] + '.' + parts[2];
                if (parts[0].equals("M")) {
                    methodBridges.put(key, parts[3]);
                } else {
                    fieldBridges.put(key, parts[3]);
                }
                known.add(key);
            }
        } catch (IOException problem) {
            throw new IllegalStateException("cannot read the bridge list " + file, problem);
        }
    }

    int size() {
        return methodBridges.size() + fieldBridges.size();
    }

    private boolean isMinecraft(String owner) {
        return owner.startsWith("net/minecraft/") || owner.startsWith("com/mojang/");
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                     String[] exceptions) {
        MethodVisitor target = super.visitMethod(access, name, descriptor, signature, exceptions);
        return target == null ? null : new Body(target);
    }

    private final class Body extends MethodVisitor {
        Body(MethodVisitor target) {
            super(Opcodes.ASM9, target);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            if (!isMinecraft(owner)) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                return;
            }
            String bridge = methodBridges.get(owner + '.' + name + descriptor);
            if (bridge == null) {
                bridge = methodBridges.get(owner + '.' + name);
            }
            if (bridge == null) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                return;
            }
            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER, bridge,
                    bridgeDescriptor(owner, arity(descriptor)), false);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (!isMinecraft(owner)) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            String bridge = fieldBridges.get(owner + '.' + name + descriptor);
            if (bridge == null || opcode == Opcodes.PUTSTATIC) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            if (opcode == Opcodes.GETFIELD) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER, bridge,
                        bridgeDescriptor(owner, 0), false);
            } else {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER, bridge + "Set",
                        bridgeDescriptor(owner, 1), false);
            }
        }
    }

    /**
     * Bridge methods take Object, because several of the types involved are package-private in Minecraft
     * and cannot be named from our package. Arity still has to match the call site.
     */
    private static String bridgeDescriptor(String owner, int arity) {
        StringBuilder descriptor = new StringBuilder("(Ljava/lang/Object;");
        for (int position = 0; position < arity; position++) {
            descriptor.append("Ljava/lang/Object;");
        }
        return descriptor.append(")Ljava/lang/Object;").toString();
    }

    private static int arity(String descriptor) {
        int close = descriptor.lastIndexOf(')');
        String parameters = descriptor.substring(1, close);
        int count = 0;
        int index = 0;
        while (index < parameters.length()) {
            char character = parameters.charAt(index);
            if (character == '[') {
                index++;
            } else if (character == 'L') {
                int end = parameters.indexOf(';', index);
                if (end < 0) {
                    break;
                }
                index = end + 1;
            } else {
                count++;
                index++;
            }
        }
        return count;
    }
}