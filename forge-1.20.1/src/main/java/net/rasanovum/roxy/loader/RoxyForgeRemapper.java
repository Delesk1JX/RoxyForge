package net.rasanovum.roxy.loader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Rewrites Voxy's intermediary bytecode into Forge 1.20.1 names.
 *
 * Everything is a straight name rewrite: class names go to official, members to SRG. References that the
 * mapping does not know are left as they are and counted, because they are the 1.21-only API that needs
 * a structural patch later - that report is the point of this tool.
 */
public final class RoxyForgeRemapper {
    /** What a remap pass did, so the caller can print the numbers that matter. */
    public record Report(int classesIn, int classesOut, int classRenames, int memberRenames,
                         List<String> unknownClasses, List<String> unknownMembers) {
    }

    private final RoxyForgeMappings mappings;
    private final Path bridgeList;

    public RoxyForgeRemapper(RoxyForgeMappings mappings) {
        this(mappings, Path.of("tools", "roxy-mappings", "roxy-mappings", "bridges.txt"));
    }

    public RoxyForgeRemapper(RoxyForgeMappings mappings, Path bridgeList) {
        this.mappings = mappings;
        this.bridgeList = bridgeList;
    }

    public Report remapJar(Path source, Path target) throws IOException {
        int classesIn = 0;
        int classesOut = 0;
        int classRenames = 0;
        Map<String, Integer> memberRenames = new LinkedHashMap<>();
        Map<String, Integer> unknownClasses = new LinkedHashMap<>();
        Map<String, Integer> unknownMembers = new LinkedHashMap<>();

        Files.createDirectories(target.getParent());
        try (ZipFile zip = new ZipFile(source.toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                if (!entry.getName().endsWith(".class")) {
                    out.putNextEntry(new JarEntry(entry.getName()));
                    try (InputStream input = zip.getInputStream(entry)) {
                        input.transferTo(out);
                    }
                    out.closeEntry();
                    continue;
                }
                classesIn++;
                byte[] original;
                try (InputStream input = zip.getInputStream(entry)) {
                    original = input.readAllBytes();
                }
                byte[] remapped = remapClass(entry.getName(), original, memberRenames, unknownClasses, unknownMembers);
                if (remapped != null) {
                    classesOut++;
                    if (!new String(original).equals(new String(remapped))) {
                        classRenames++;
                    }
                    out.putNextEntry(new JarEntry(entry.getName()));
                    out.write(remapped);
                    out.closeEntry();
                }
            }
        }
        List<String> unknownClassList = top(unknownClasses);
        List<String> unknownMemberList = top(unknownMembers);
        int renames = memberRenames.values().stream().mapToInt(Integer::intValue).sum();
        return new Report(classesIn, classesOut, classRenames, renames, unknownClassList, unknownMemberList);
    }

    private byte[] remapClass(String internalName, byte[] original, Map<String, Integer> memberRenames,
                              Map<String, Integer> unknownClasses, Map<String, Integer> unknownMembers) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                String mapped = mappings.mapClass(internalName);
                if (!mapped.equals(internalName)) {
                    return mapped;
                }
                if (internalName.startsWith("net/minecraft/")) {
                    unknownClasses.merge(internalName, 1, Integer::sum);
                }
                return internalName;
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                if (!owner.startsWith("net/minecraft/")) {
                    return name;
                }
                if (!mappings.knowsMember(owner, name, descriptor)) {
                    unknownMembers.merge(owner + '.' + name, 1, Integer::sum);
                    return name;
                }
                memberRenames.merge("field", 1, Integer::sum);
                return mappings.mapMember(owner, name, descriptor);
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                if (!owner.startsWith("net/minecraft/")) {
                    return name;
                }
                if (!mappings.knowsMember(owner, name, descriptor)) {
                    unknownMembers.merge(owner + '.' + name, 1, Integer::sum);
                    return name;
                }
                memberRenames.merge("method", 1, Integer::sum);
                return mappings.mapMember(owner, name, descriptor);
            }

            @Override
            public String mapRecordComponentName(String owner, String name, String descriptor) {
                return name;
            }

            @Override
            public String mapInvokeDynamicMethodName(String name, String descriptor) {
                return name;
            }
        };
        reader.accept(new RoxyBridgeRedirect(new ClassRemapper(writer, remapper), bridgeList), 0);
        return writer.toByteArray();
    }

    private static List<String> top(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
        List<String> result = new ArrayList<>();
        for (int index = 0; index < Math.min(entries.size(), 40); index++) {
            Map.Entry<String, Integer> entry = entries.get(index);
            result.add(entry.getValue() + "  " + entry.getKey());
        }
        return result;
    }
}