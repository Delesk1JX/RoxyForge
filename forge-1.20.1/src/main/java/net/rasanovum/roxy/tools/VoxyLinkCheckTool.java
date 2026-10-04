package net.rasanovum.roxy.tools;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarFile;

/**
 * M3c: static link check of the remapped Voxy jar against the jars the game really loads.
 *
 * Booting Minecraft to find out which of Voxy's references do not resolve is slow and noisy, so this
 * walks the bytecode instead and asks the real SRG-named Minecraft jar whether every referenced class,
 * method and field exists. The result is the work list for the structural patches.
 *
 *     VoxyLinkCheckTool &lt;remapped voxy jar&gt; &lt;mc srg jar&gt; [more jars...] [report file]
 */
public final class VoxyLinkCheckTool {
    private static final int ASM = Opcodes.ASM9;
    // The report is the input for make_bridges.py, so it must list every unresolved symbol, not a sample.
    private static final int SHOWN = 100_000;

    /** What the target jars declare. */
    private static final class Index {
        final Map<String, Set<String>> methods = new LinkedHashMap<>();
        final Map<String, Set<String>> fields = new LinkedHashMap<>();
        final Map<String, String> supers = new LinkedHashMap<>();
        final Map<String, List<String>> interfaces = new LinkedHashMap<>();
    }

    /** Every Minecraft reference the remapped Voxy bytecode makes. */
    private static final class References {
        final Set<String> classes = new LinkedHashSet<>();
        final Map<String, String> methods = new LinkedHashMap<>();
        final Map<String, String> fields = new LinkedHashMap<>();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: VoxyLinkCheckTool <remapped voxy jar> <jar> [more jars...] [report file]");
            System.exit(2);
        }
        Path voxyJar = Path.of(args[0]);
        List<Path> jars = new ArrayList<>();
        for (int at = 1; at < args.length; at++) {
            Path candidate = Path.of(args[at]);
            if (Files.isRegularFile(candidate)
                    && (candidate.toString().endsWith(".jar") || candidate.toString().endsWith(".txt"))) {
                jars.add(candidate);
            }
        }
        Path report = args[args.length - 1].endsWith(".jar") ? null : Path.of(args[args.length - 1]);

        Index index = new Index();
        for (Path jar : jars) {
            if (jar.toString().endsWith(".txt")) {
                indexTextFile(jar, index);
            } else {
                indexJar(jar, index);
            }
        }

        References references = new References();
        int classes = 0;
        try (JarFile jar = new JarFile(voxyJar.toFile())) {
            for (var entry : Collections.list(jar.entries())) {
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream input = jar.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                classes++;
                new ClassReader(bytes).accept(new ReferenceVisitor(references), ClassReader.SKIP_DEBUG);
            }
        }

        Map<String, Integer> missingClasses = new TreeMap<>();
        Map<String, Integer> missingMethods = new TreeMap<>();
        Map<String, Integer> missingFields = new TreeMap<>();
        for (String referenced : references.classes) {
            if (isMinecraft(referenced) && !index.methods.containsKey(referenced)) {
                missingClasses.merge(referenced, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, String> entry : references.methods.entrySet()) {
            String[] parts = entry.getKey().split("#", 2);
            if (!isMinecraft(parts[0])) {
                continue;
            }
            if (!declaresMethod(index, parts[0], parts[1], entry.getValue())) {
                missingMethods.merge(parts[0] + '.' + parts[1] + entry.getValue(), 1, Integer::sum);
            }
        }
        for (Map.Entry<String, String> entry : references.fields.entrySet()) {
            String[] parts = entry.getKey().split("#", 2);
            if (!isMinecraft(parts[0])) {
                continue;
            }
            if (!declaresField(index, parts[0], parts[1], entry.getValue())) {
                // ':' separates name from descriptor: a field called field_60582F ends in a letter that
                // looks like the start of a primitive descriptor, so concatenation is ambiguous.
                missingFields.merge(parts[0] + '.' + parts[1] + ':' + entry.getValue(), 1, Integer::sum);
            }
        }

        StringBuilder text = new StringBuilder();
        text.append("indexed jars: ").append(jars.size()).append('\n');
        for (Path jar : jars) {
            text.append("  ").append(jar.getFileName()).append('\n');
        }
        text.append("remapped classes walked: ").append(classes).append('\n');
        text.append("minecraft class references: ").append(references.classes.size()).append('\n');
        text.append("minecraft method references: ").append(references.methods.size()).append('\n');
        text.append("minecraft field references: ").append(references.fields.size()).append('\n');
        text.append('\n');
        text.append("UNRESOLVED classes: ").append(sum(missingClasses))
                .append(" (").append(missingClasses.size()).append(" distinct)\n");
        text.append("UNRESOLVED methods: ").append(sum(missingMethods))
                .append(" (").append(missingMethods.size()).append(" distinct)\n");
        text.append("UNRESOLVED fields: ").append(sum(missingFields))
                .append(" (").append(missingFields.size()).append(" distinct)\n");
        text.append("\n--- unresolved classes\n");
        appendTop(text, missingClasses);
        text.append("\n--- unresolved methods\n");
        appendTop(text, missingMethods);
        text.append("\n--- unresolved fields\n");
        appendTop(text, missingFields);

        System.out.print(text);
        if (report != null) {
            Files.writeString(report, text.toString(), StandardCharsets.UTF_8);
            System.out.println("wrote " + report);
        }
    }

    private static boolean isMinecraft(String internalName) {
        return internalName.startsWith("net/minecraft/");
    }

    private static boolean declaresMethod(Index index, String owner, String name, String descriptor) {
        String cursor = owner;
        for (int depth = 0; cursor != null && depth < 32; depth++) {
            Set<String> declared = index.methods.get(cursor);
            if (declared != null && declared.contains(name + descriptor)) {
                return true;
            }
            for (String itf : index.interfaces.getOrDefault(cursor, List.of())) {
                if (declaresMethod(index, itf, name, descriptor)) {
                    return true;
                }
            }
            cursor = index.supers.get(cursor);
        }
        return false;
    }

    private static boolean declaresField(Index index, String owner, String name, String descriptor) {
        String cursor = owner;
        for (int depth = 0; cursor != null && depth < 32; depth++) {
            Set<String> declared = index.fields.get(cursor);
            if (declared != null && declared.contains(name + descriptor)) {
                return true;
            }
            for (String itf : index.interfaces.getOrDefault(cursor, List.of())) {
                if (declaresField(index, itf, name, descriptor)) {
                    return true;
                }
            }
            cursor = index.supers.get(cursor);
        }
        return false;
    }

    private static int sum(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static void appendTop(StringBuilder text, Map<String, Integer> counts) {
        counts.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(SHOWN)
                .forEach(entry -> text.append("  ").append(entry.getValue()).append("  ")
                        .append(entry.getKey()).append('\n'));
    }

    private static void indexJar(Path jar, Index index) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            for (var entry : Collections.list(file.entries())) {
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream input = file.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                new ClassReader(bytes).accept(new IndexVisitor(index), ClassReader.SKIP_CODE);
            }
        }
    }

    /**
     * Reads the index the mapping generator writes: what Minecraft 1.20.1 declares, with the obfuscated
     * server jar already deobfuscated. One line per record: C class, S class super, I class interface,
     * M class name+descriptor, F class name+descriptor.
     */
    private static void indexTextFile(Path file, Index index) throws IOException {
        for (String line : Files.readAllLines(file)) {
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t");
            switch (parts[0]) {
                case "C" -> {
                    index.methods.computeIfAbsent(parts[1], ignored -> new LinkedHashSet<>());
                    index.fields.computeIfAbsent(parts[1], ignored -> new LinkedHashSet<>());
                }
                case "S" -> index.supers.put(parts[1], parts[2]);
                case "I" -> index.interfaces.computeIfAbsent(parts[1], ignored -> new ArrayList<>()).add(parts[2]);
                case "M" -> index.methods.get(parts[1]).add(parts[2]);
                case "F" -> index.fields.get(parts[1]).add(parts[2]);
                default -> { }
            }
        }
    }

    private static final class IndexVisitor extends ClassVisitor {
        private final Index index;
        private String owner;

        IndexVisitor(Index index) {
            super(ASM);
            this.index = index;
        }

        @Override
        public void visit(int version, int access, String internalName, String signature,
                          String superName, String[] interfaces) {
            owner = internalName;
            index.methods.computeIfAbsent(internalName, ignored -> new LinkedHashSet<>());
            index.fields.computeIfAbsent(internalName, ignored -> new LinkedHashSet<>());
            if (superName != null) {
                index.supers.put(internalName, superName);
            }
            if (interfaces != null && interfaces.length > 0) {
                index.interfaces.put(internalName, new ArrayList<>(List.of(interfaces)));
            }
        }

        @Override
        public FieldVisitor visitField(int access, String fieldName, String descriptor,
                                       String signature, Object value) {
            index.fields.get(owner).add(fieldName + descriptor);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                         String signature, String[] exceptions) {
            index.methods.get(owner).add(methodName + descriptor);
            return null;
        }
    }

    private static final class ReferenceVisitor extends ClassVisitor {
        private final References references;

        ReferenceVisitor(References references) {
            super(ASM);
            this.references = references;
        }

        @Override
        public void visit(int version, int access, String internalName, String signature,
                          String superName, String[] interfaces) {
            if (superName != null) {
                references.classes.add(superName);
            }
            if (interfaces != null) {
                for (String itf : interfaces) {
                    references.classes.add(itf);
                }
            }
        }

        @Override
        public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                         String signature, String[] exceptions) {
            return new BodyVisitor(references);
        }
    }

    /** Instruction-level references: these are what decide whether Voxy can link on 1.20.1. */
    private static final class BodyVisitor extends MethodVisitor {
        private final References references;

        BodyVisitor(References references) {
            super(ASM);
            this.references = references;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            references.classes.add(type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String fieldName, String descriptor) {
            references.classes.add(owner);
            references.fields.put(owner + '#' + fieldName, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String methodName,
                                    String descriptor, boolean isInterface) {
            references.classes.add(owner);
            references.methods.put(owner + '#' + methodName, descriptor);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                                           Object... bootstrapMethodArguments) {
            collectHandle(bootstrapMethodHandle);
            for (Object argument : bootstrapMethodArguments) {
                if (argument instanceof Handle handle) {
                    collectHandle(handle);
                } else if (argument instanceof Type type) {
                    references.classes.add(type.getInternalName());
                }
            }
        }

        private void collectHandle(Handle handle) {
            references.classes.add(handle.getOwner());
            references.methods.put(handle.getOwner() + '#' + handle.getName(), handle.getDesc());
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
            references.classes.add(Type.getType(descriptor).getElementType().getInternalName());
        }

        @Override
        public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end,
                                       org.objectweb.asm.Label handler, String type) {
            if (type != null) {
                references.classes.add(type);
            }
        }
    }
}