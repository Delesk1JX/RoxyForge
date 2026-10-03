import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.builder.ConfigBuilderImpl;
import net.caffeinemc.mods.sodium.client.config.structure.Config;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.rasanovum.roxy.fog.RoxyFogConfig;
import net.rasanovum.roxy.tfc.TfcCompatConfig;
import net.rasanovum.roxyhost.RoxyFogOptions;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/** Exercises TFC Sodium option state against the actual Sodium builder/config implementation. */
public final class VerifyTfcOptions {
    private static final ResourceLocation TFC_OPTION = id("roxy:tfc_day_cycle_lod_updates");
    private static final ResourceLocation FOG_OPTION = id("roxy:fog_automatic");

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Path game = Files.createTempDirectory("roxy-tfc-options-");
        FMLPaths.loadAbsolutePaths(game);
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
        Map<String, ModFileInfo> files = modFiles();
        ModFileInfo marker = marker();
        setSettings(false);
        try {
            verifyJsonRoundTrip(game);

            Config absent = buildWithPrivateOptions();
            require(absent.getOption(TFC_OPTION) != null, "TFC option missing without TFC");
            require(!absent.getOption(TFC_OPTION).isEnabled(), "TFC option was enabled without TFC");
            require(!TfcCompatConfig.installed() && !TfcCompatConfig.enabled(),
                    "missing TFC incorrectly reported as installed or enabled");

            files.put("tfc", marker);
            TfcCompatConfig.get().enabled = false;
            Config installed = buildWithPrivateOptions();
            StatefulOption<Boolean> option = option(installed);
            require(option.isEnabled(), "TFC option stayed grey when TFC was installed");
            require(!option.getAppliedValue() && !TfcCompatConfig.enabled(), "TFC option default is not false");
            option.modifyValue(true);
            require(!TfcCompatConfig.enabled(), "pending TFC edit changed runtime before Apply");
            installed.applyAllOptions();
            require(TfcCompatConfig.enabled(), "Apply did not enable TFC compatibility");
            require(TfcCompatConfig.read(game.resolve("config/roxy-tfc.json")).enabled,
                    "Apply did not persist the TFC setting");
            option.modifyValue(false);
            installed.resetAllOptionsFromBindings();
            require(TfcCompatConfig.enabled(), "Cancel changed the applied TFC setting");
            option.modifyValue(false);
            installed.applyAllOptions();
            require(!TfcCompatConfig.enabled(), "Apply did not disable TFC compatibility");

            writeIrisFixture(true);
            RoxyFogConfig.Settings fog = new RoxyFogConfig.Settings();
            setFogSettings(fog);
            Config shaders = buildViaRegister();
            require(shaders.getOption(TFC_OPTION) != null, "shader-active registration hid the TFC option");
            require(shaders.getOption(TFC_OPTION).isEnabled(),
                    "shader-active registration greyed an installed TFC option");
            require(shaders.getOption(FOG_OPTION) == null,
                    "shader-active registration exposed fog controls that were previously hidden");
            System.out.println("PASS: TFC option presence, installed gating, default, Apply/Cancel, JSON persistence and shader-active registration");
        } finally {
            files.remove("tfc");
            deleteTree(game);
        }
    }

    private static Config buildWithPrivateOptions() throws Exception {
        ConfigBuilderImpl builder = builder();
        Method add = RoxyFogOptions.class.getDeclaredMethod("addOptions",
                net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder.class,
                RoxyFogConfig.Settings.class,
                net.caffeinemc.mods.sodium.api.config.StorageEventHandler.class);
        add.setAccessible(true);
        add.invoke(null, builder, new RoxyFogConfig.Settings(), (net.caffeinemc.mods.sodium.api.config.StorageEventHandler) () -> {});
        return new Config(List.copyOf(builder.build()));
    }

    private static Config buildViaRegister() {
        ConfigBuilderImpl builder = builder();
        RoxyFogOptions.register(builder);
        return new Config(List.copyOf(builder.build()));
    }

    private static ConfigBuilderImpl builder() {
        return new ConfigBuilderImpl(name -> new ConfigManager.ModMetadata(name, "test"), "voxy");
    }

    @SuppressWarnings("unchecked")
    private static StatefulOption<Boolean> option(Config config) {
        return (StatefulOption<Boolean>) config.getOption(TFC_OPTION);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ModFileInfo> modFiles() throws Exception {
        Field field = LoadingModList.class.getDeclaredField("fileById");
        field.setAccessible(true);
        return (Map<String, ModFileInfo>) field.get(LoadingModList.get());
    }

    private static ModFileInfo marker() throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        return (ModFileInfo) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(ModFileInfo.class);
    }

    private static void setSettings(boolean enabled) throws Exception {
        Field field = TfcCompatConfig.class.getDeclaredField("settings");
        field.setAccessible(true);
        TfcCompatConfig.Settings settings = new TfcCompatConfig.Settings();
        settings.enabled = enabled;
        field.set(null, settings);
    }

    private static void setFogSettings(RoxyFogConfig.Settings settings) throws Exception {
        Field field = RoxyFogConfig.class.getDeclaredField("settings");
        field.setAccessible(true);
        field.set(null, settings);
    }

    private static void verifyJsonRoundTrip(Path game) throws Exception {
        Path path = game.resolve("roundtrip/roxy-tfc.json");
        TfcCompatConfig.Settings settings = new TfcCompatConfig.Settings();
        settings.enabled = true;
        TfcCompatConfig.write(path, settings);
        require(TfcCompatConfig.read(path).enabled, "TFC JSON roundtrip lost enabled state");
        settings.enabled = false;
        TfcCompatConfig.write(path, settings);
        require(!TfcCompatConfig.read(path).enabled, "TFC JSON roundtrip lost disabled state");
        Files.deleteIfExists(path);
        Files.deleteIfExists(path.getParent());
    }

    private static void writeIrisFixture(boolean active) throws Exception {
        String name = "net/irisshaders/iris/api/v0/IrisApi";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "active", "Z", null, active).visitEnd();
        MethodVisitorSupport.constructor(writer, name);
        MethodVisitorSupport.instance(writer, name);
        MethodVisitorSupport.shaderActive(writer, name);
        writer.visitEnd();
        Path root = Path.of(VerifyTfcOptions.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path target = root.resolve(name + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, writer.toByteArray());
    }

    private static ResourceLocation id(String value) { return ResourceLocation.parse(value); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private static final class MethodVisitorSupport {
        private static void constructor(ClassWriter writer, String name) {
            var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            method.visitCode();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(1, 1);
            method.visitEnd();
        }

        private static void instance(ClassWriter writer, String name) {
            var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getInstance", "()L" + name + ";", null, null);
            method.visitCode();
            method.visitTypeInsn(Opcodes.NEW, name);
            method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "()V", false);
            method.visitInsn(Opcodes.ARETURN);
            method.visitMaxs(2, 0);
            method.visitEnd();
        }

        private static void shaderActive(ClassWriter writer, String name) {
            var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "isShaderPackInUse", "()Z", null, null);
            method.visitCode();
            method.visitFieldInsn(Opcodes.GETSTATIC, name, "active", "Z");
            method.visitInsn(Opcodes.IRETURN);
            method.visitMaxs(1, 1);
            method.visitEnd();
        }
    }
}
