package net.rasanovum.roxy.loader;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Rewrites the Minecraft calls Voxy makes that Minecraft 1.20.1 cannot satisfy into static calls on
 * {@code net.rasanovum.roxy.bridge.RoxyBridge}.
 *
 * A method call {@code Owner.member(args)} becomes
 * {@code RoxyBridge.mNNNN(owner, args)}, and a field read becomes {@code RoxyBridge.mNNNN(owner)}.
 * Static calls and static fields have no receiver on the stack, so they use the {@code ...Static}
 * variants. The bridge list comes from tools/roxy-mappings/make_bridges.py, which reads the link
 * checker, so what needs a bridge is always measured rather than guessed.
 *
 * Bridge parameters are {@code Object} because several of the types involved are package-private in
 * Minecraft and cannot be named from our package; the real types stay in the javadoc and in bridges.txt.
 */
public final class RoxyBridgeRedirect extends ClassVisitor {
    private static final String BRIDGE_OWNER = "net/rasanovum/roxy/bridge/RoxyBridge";
    private static final String OBJECT = "Ljava/lang/Object;";

    private final Map<String, String> methodBridges = new HashMap<>();
    private final Map<String, String> fieldBridges = new HashMap<>();
    private final RoxyForgeMappings mappings;

    RoxyBridgeRedirect(ClassVisitor next, Path bridgeList, RoxyForgeMappings mappings) {
        super(Opcodes.ASM9, next);
        this.mappings = mappings;
        if (bridgeList != null && Files.exists(bridgeList)) {
            load(bridgeList);
        }
    }

    /**
     * Bridges are keyed by the names the link checker saw, which are the *target* names. The remapper works
     * on the original intermediary names, so a lookup can miss; this resolves both spellings.
     */
    private String lookup(Map<String, String> bridges, String owner, String member, String simpleName,
                          String descriptor) {
        String direct = bridges.get(owner + '.' + member);
        if (direct != null || mappings == null) {
            return direct;
        }
        // The bridge list is keyed by target names, the remapper sees intermediary ones. Ask the mapping
        // for the target name of this exact owner/name/descriptor, then use the name-only alias: overloads
        // share an intermediary name but not an SRG one, so the descriptor has to be part of the lookup.
        String targetOwner = mappings.mapClass(owner);
        String targetName = mappings.mapMember(owner, simpleName, descriptor);
        String viaAlias = bridges.get(targetOwner + '.' + targetName);
        if (viaAlias != null) {
            return viaAlias;
        }
        String full = bridges.get(targetOwner + '.' + targetName + descriptor);
        return full != null ? full : bridges.get(targetOwner + '.' + member);
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
                } else if (parts[0].equals("F")) {
                    fieldBridges.put(key, parts[3]);
                } else {
                    // "K" is a name-only alias, usable for both a method and a field.
                    methodBridges.putIfAbsent(key, parts[3]);
                    fieldBridges.putIfAbsent(key, parts[3]);
                }
            }
        } catch (IOException problem) {
            throw new IllegalStateException("cannot read the bridge list " + file, problem);
        }
    }

    int size() {
        return methodBridges.size() + fieldBridges.size();
    }
    private static int redirects;

    /** How many call sites were rewritten in this pass - the number that says whether a bridge works. */
    public static int totalRedirects() {
        return redirects;
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
            String bridge = lookup(methodBridges, owner, name + descriptor, name, descriptor);
            if (bridge == null) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                return;
            }
            boolean isStatic = opcode == Opcodes.INVOKESTATIC;
            redirects++;
            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER,
                    isStatic ? bridge + "Static" : bridge,
                    descriptorFor(arity(descriptor), !isStatic), false);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            String bridge = lookup(fieldBridges, owner, name + descriptor, name, descriptor);
            if (bridge == null) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            switch (opcode) {
                case Opcodes.GETFIELD -> super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER,
                        bridge, descriptorFor(0, true), false);
                case Opcodes.PUTFIELD -> super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER,
                        bridge + "Set", descriptorFor(1, true), false);
                case Opcodes.GETSTATIC -> super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER,
                        bridge + "StaticGet", descriptorFor(0, false), false);
                case Opcodes.PUTSTATIC -> super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER,
                        bridge + "StaticSet", descriptorFor(1, false), false);
                default -> super.visitFieldInsn(opcode, owner, name, descriptor);
            }
        }
    }

    /** (Object self?, Object argument...) -> Object, matching what the generator emitted. */
    private static String descriptorFor(int arity, boolean withSelf) {
        StringBuilder descriptor = new StringBuilder("(");
        if (withSelf) {
            descriptor.append(OBJECT);
        }
        for (int position = 0; position < arity; position++) {
            descriptor.append(OBJECT);
        }
        return descriptor.append(')').append(OBJECT).toString();
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