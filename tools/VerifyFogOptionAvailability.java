import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.builder.ConfigBuilderImpl;
import net.caffeinemc.mods.sodium.client.config.structure.Config;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.rasanovum.roxy.fog.RoxyFogConfig;
import net.rasanovum.roxy.fog.RoxyVoxyFogPatch;
import net.rasanovum.roxyhost.RoxyFogOptions;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public final class VerifyFogOptionAvailability {
    private static final ResourceLocation ENABLED = id("voxy:enabled");
    private static final ResourceLocation RENDERING = id("voxy:rendering");
    private static final ResourceLocation ENVIRONMENTAL = id("voxy:eviromental_fog");
    private static final ResourceLocation AUTOMATIC = id("roxy:fog_automatic");
    private static final ResourceLocation START = id("roxy:fog_start");
    private static final ResourceLocation ROLL_IN = id("roxy:weather_fog_roll_in");
    public static final RuntimeConfig RUNTIME = new RuntimeConfig();

    public static final class RuntimeConfig {
        public boolean enabled = true;
        public boolean enableRendering = true;
        public boolean useEnvironmentalFog = false;
        public float sectionRenderDistance = 16;
        public Object pipeline;
        public boolean isRenderingEnabled() { return enabled && enableRendering; }
    }

    public static Object getNullable() { return null; }

    public static void main(String[] args) throws Exception {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        installRuntimeFixture();
        writeAvailabilityFixture();
        writeShaderFixture();
        var available = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon").getField("available");
        var shaders = Class.forName("net.irisshaders.iris.api.v0.IrisApi").getField("active");
        available.setBoolean(null, true);
        shaders.setBoolean(null, false);
        var filesField = LoadingModList.class.getDeclaredField("fileById");
        filesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ModFileInfo> files = (Map<String, ModFileInfo>) filesField.get(LoadingModList.get());
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        // Only mod-presence lookup is used; no No Man's Land internals are simulated.
        ModFileInfo marker = (ModFileInfo) unsafe.allocateInstance(ModFileInfo.class);
        Path path = Files.createTempDirectory("roxy-fog-ui-").resolve("roxy-fog.json");
        try {
            files.put("nomansland", marker);
            var settings = new RoxyFogConfig.Settings();
            RUNTIME.useEnvironmentalFog = true;
            Config config = build(settings, () -> RoxyFogConfig.write(path, settings), true);
            check(config, false, false, false, "initial environmental fog makes controls legitimately unavailable");
            modify(config, ENVIRONMENTAL, false);
            check(config, true, true, true, "controls recover immediately when environmental fog is turned off");
            config.applyAllOptions();
            check(config, true, true, true, "controls recover after Apply when initially cached as disabled");

            modify(config, ENVIRONMENTAL, true);
            check(config, false, false, false, "pending environmental fog on");
            config.applyAllOptions();
            check(config, false, false, false, "applied environmental fog on");
            modify(config, ENVIRONMENTAL, false);
            check(config, true, true, true, "pending environmental fog off while saved value is still on");
            require(RUNTIME.useEnvironmentalFog, "pending menu value unexpectedly wrote runtime config");
            config.applyAllOptions();
            check(config, true, true, true, "applied environmental fog off recovers controls");

            modify(config, RENDERING, false);
            check(config, false, false, false, "pending rendering off");
            config.resetAllOptionsFromBindings();
            check(config, true, true, true, "Cancel restores rendering dependency");
            modify(config, ENABLED, false);
            check(config, false, false, false, "pending Voxy disabled");
            modify(config, ENABLED, true);
            check(config, true, true, true, "pending Voxy re-enabled");

            modify(config, AUTOMATIC, false);
            check(config, true, true, false, "weather toggle disables only roll-in");
            modify(config, AUTOMATIC, true);
            check(config, true, true, true, "weather toggle immediately restores roll-in");
            modify(config, START, 32);
            modify(config, ROLL_IN, 75);
            config.applyAllOptions();
            check(config, true, true, true, "editing and applying Roxy controls keeps them accessible");
            var saved = RoxyFogConfig.read(path);
            require(saved.start == 512 && saved.weatherRollIn == 75 && saved.automatic,
                    "fog menu edits were not persisted");
            for (int i = 0; i < 3; i++) {
                modify(config, ENVIRONMENTAL, true);
                config.applyAllOptions();
                modify(config, ENVIRONMENTAL, false);
                config.applyAllOptions();
                check(config, true, true, true, "repeated apply cycle " + i);
            }
            shaders.setBoolean(null, true);
            config.invalidateGlobalRebuildDependents();
            check(config, false, false, false, "shader activation disables controls on screen rebuild");
            shaders.setBoolean(null, false);
            config.invalidateGlobalRebuildDependents();
            check(config, true, true, true, "shader deactivation restores controls on screen rebuild");
            available.setBoolean(null, false);
            RUNTIME.enableRendering = false;
            Config missingOptions = build(new RoxyFogConfig.Settings(), () -> {}, false);
            check(missingOptions, false, false, false, "unavailable Voxy options do not create invalid dependencies");
            available.setBoolean(null, true);
            RUNTIME.enableRendering = true;
            files.remove("nomansland");
            check(build(new RoxyFogConfig.Settings(), () -> {}, true), false, false, false,
                    "unsupported-mod environment remains disabled");
            System.out.println("PASS: actual Sodium Config cache/dependency updates across edit, Apply, Cancel, recovery, persistence and missing-option fallback");
        } finally {
            files.remove("nomansland");
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        }
    }

    private static Config build(RoxyFogConfig.Settings settings, StorageEventHandler storage, boolean includeVoxy) throws Exception {
        var builder = new ConfigBuilderImpl(name -> new ConfigManager.ModMetadata(name, "test"), "voxy");
        if (includeVoxy) {
            StorageEventHandler noStorage = () -> {};
            var enabled = builder.createBooleanOption(ENABLED).setName(Component.literal("Voxy"))
                    .setTooltip(Component.literal("Test Voxy enable state"))
                    .setDefaultValue(true).setStorageHandler(noStorage)
                    .setBinding(value -> RUNTIME.enabled = value, () -> RUNTIME.enabled);
            var rendering = builder.createBooleanOption(RENDERING).setName(Component.literal("Rendering"))
                    .setTooltip(Component.literal("Test Voxy rendering state"))
                    .setDefaultValue(true).setStorageHandler(noStorage)
                    .setBinding(value -> RUNTIME.enableRendering = value, () -> RUNTIME.enableRendering);
            var environmental = builder.createBooleanOption(ENVIRONMENTAL).setName(Component.literal("Environmental fog"))
                    .setTooltip(Component.literal("Test Voxy environmental fog state"))
                    .setDefaultValue(false).setStorageHandler(noStorage)
                    .setBinding(value -> RUNTIME.useEnvironmentalFog = value, () -> RUNTIME.useEnvironmentalFog);
            builder.registerModOptions("voxy").addPage(builder.createOptionPage().setName(Component.literal("Rendering"))
                    .addOption(enabled).addOption(rendering).addOption(environmental));
        }
        Method add = RoxyFogOptions.class.getDeclaredMethod("addOptions", ConfigBuilder.class,
                RoxyFogConfig.Settings.class, StorageEventHandler.class);
        add.setAccessible(true);
        add.invoke(null, builder, settings, storage);
        return new Config(List.copyOf(builder.build()));
    }

    private static void installRuntimeFixture() throws Exception {
        Class<?> accessors = Class.forName("net.rasanovum.roxy.fog.RoxyVoxyFogPatch$Accessors");
        var constructor = accessors.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var fixture = constructor.newInstance(VerifyFogOptionAvailability.class.getMethod("getNullable"),
                VerifyFogOptionAvailability.class.getField("RUNTIME"), RuntimeConfig.class.getMethod("isRenderingEnabled"),
                RuntimeConfig.class.getField("useEnvironmentalFog"), RuntimeConfig.class.getField("sectionRenderDistance"),
                RuntimeConfig.class.getField("pipeline"));
        var field = RoxyVoxyFogPatch.class.getDeclaredField("accessors");
        field.setAccessible(true);
        field.set(null, fixture);
    }

    private static void writeAvailabilityFixture() throws Exception {
        ClassWriter writer = new ClassWriter(0);
        String name = "me/cortex/voxy/commonImpl/VoxyCommon";
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "available", "Z", null, null).visitEnd();
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isAvailable", "()Z", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, name, "available", "Z");
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        Path root = Path.of(VerifyFogOptionAvailability.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path target = root.resolve(name + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, writer.toByteArray());
    }

    private static void writeShaderFixture() throws Exception {
        String name = "net/irisshaders/iris/api/v0/IrisApi";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "active", "Z", null, null).visitEnd();
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        var instance = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getInstance", "()L" + name + ";", null, null);
        instance.visitCode();
        instance.visitTypeInsn(Opcodes.NEW, name);
        instance.visitInsn(Opcodes.DUP);
        instance.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "()V", false);
        instance.visitInsn(Opcodes.ARETURN);
        instance.visitMaxs(2, 0);
        instance.visitEnd();
        var active = writer.visitMethod(Opcodes.ACC_PUBLIC, "isShaderPackInUse", "()Z", null, null);
        active.visitCode();
        active.visitFieldInsn(Opcodes.GETSTATIC, name, "active", "Z");
        active.visitInsn(Opcodes.IRETURN);
        active.visitMaxs(1, 1);
        active.visitEnd();
        writer.visitEnd();
        Path root = Path.of(VerifyFogOptionAvailability.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path target = root.resolve(name + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, writer.toByteArray());
    }

    @SuppressWarnings("unchecked")
    private static <T> void modify(Config config, ResourceLocation id, T value) {
        ((StatefulOption<T>) config.getOption(id)).modifyValue(value);
    }

    private static void check(Config config, boolean automatic, boolean start, boolean rollIn, String scene) {
        require(config.getOption(AUTOMATIC).getEnabled().get(config) == automatic, scene + ": automatic availability");
        require(config.getOption(START).getEnabled().get(config) == start, scene + ": fog-start availability");
        require(config.getOption(ROLL_IN).getEnabled().get(config) == rollIn, scene + ": weather roll-in availability");
    }

    private static ResourceLocation id(String id) { return ResourceLocation.parse(id); }
    private static void require(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
