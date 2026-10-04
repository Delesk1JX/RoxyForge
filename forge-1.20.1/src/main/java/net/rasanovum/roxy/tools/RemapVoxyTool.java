package net.rasanovum.roxy.tools;

import net.rasanovum.roxy.loader.RoxyForgeMappings;
import net.rasanovum.roxy.loader.RoxyForgeRemapper;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Offline remap pass: {@code um-remap} style entry point used by the Gradle task `remapVoxy`.
 *
 *     remapVoxy <mapping file> <voxy jar> <output jar> [report file]
 *
 * The report is the M3 measurement: how much of Voxy's Minecraft surface the generated mapping covers
 * and exactly which classes and members it could not map.
 */
public final class RemapVoxyTool {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: RemapVoxyTool <mapping file> <voxy jar> <output jar> [report file]");
            System.exit(2);
        }
        Path mappingFile = Path.of(args[0]);
        Path voxyJar = Path.of(args[1]);
        Path outputJar = Path.of(args[2]);
        Path reportFile = args.length > 3 ? Path.of(args[3]) : null;

        RoxyForgeMappings mappings = RoxyForgeMappings.load(mappingFile);
        System.out.println("mapping: " + mappings.classCount() + " classes, " + mappings.memberCount() + " members");

        RoxyForgeRemapper remapper = new RoxyForgeRemapper(mappings);
        RoxyForgeRemapper.Report report = remapper.remapJar(voxyJar, outputJar);

        StringBuilder text = new StringBuilder();
        text.append("classes in: ").append(report.classesIn()).append('\n');
        text.append("classes out: ").append(report.classesOut()).append('\n');
        text.append("member renames: ").append(report.memberRenames()).append('\n');
        text.append("distinct unmapped classes: ").append(report.unknownClasses().size()).append('\n');
        text.append("distinct unmapped members: ").append(report.unknownMembers().size()).append('\n');
        text.append('\n').append("unmapped classes\n");
        report.unknownClasses().forEach(line -> text.append("  ").append(line).append('\n'));
        text.append('\n').append("unmapped members\n");
        report.unknownMembers().forEach(line -> text.append("  ").append(line).append('\n'));

        System.out.print(text);
        System.out.println("wrote " + outputJar + " (" + Files.size(outputJar) + " bytes)");
        if (reportFile != null) {
            Files.writeString(reportFile, text.toString());
            System.out.println("wrote " + reportFile);
        }
    }
}