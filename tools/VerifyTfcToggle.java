import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.rasanovum.roxy.tfc.TfcClimateStore;
import net.rasanovum.roxy.tfc.TfcCompatConfig;
import net.rasanovum.roxy.tfc.TfcVoxyBridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Runtime regression checks for TFC Apply transitions and disabled bridge behavior. */
public final class VerifyTfcToggle {
    private static final Class<?> BRIDGE = TfcVoxyBridge.class;

    public static final class Mapper {
        public Object getBlockStateFromBlockId(int id) { return "state-" + id; }
    }

    public static final class Model {
        private final Mapper mapper = new Mapper();
        public boolean addEntry(int id) { return true; }
    }

    public static final class VariantModel {}

    public static void main(String[] args) throws Exception {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Map<String, ModFileInfo> files = modFiles();
        ModFileInfo marker = marker();
        files.put("tfc", marker);
        Object oldActive = get(null, "active");
        Object oldClimate = get(null, "climate");
        Object oldWorld = get(null, "world");
        Object oldAdapter = get(null, "adapter");
        boolean oldCompat = (boolean) get(null, "compatWasEnabled");
        boolean oldSeen = (boolean) get(null, "configSeen");
        boolean oldRequest = (boolean) get(null, "rendererReloadRequested");
        boolean oldFast = (boolean) get(null, "compatFastEnabled");
        try {
            setSettings(false);
            require(!TfcCompatConfig.enabled(), "TFC default-off state is not disabled");

            Model model = new Model();
            int[] mappings = new int[(1 << 20) + 4096];
            Arrays.fill(mappings, -1);
            Object factory = construct("Factory", new Class[]{Object.class, int[].class, Object.class}, model, mappings, new Object());
            Object variant = construct("Variant", new Class[]{Object.class, Object.class, int.class, int.class},
                    "state-1", new VariantModel(), 0x2468a0, 1 << 20);
            ((List<Object>) get(factory, "variants")).add(variant);
            set(null, "active", factory);
            set(null, "compatWasEnabled", false);
            set(null, "configSeen", true);
            set(null, "compatFastEnabled", false);

            require((int) TfcVoxyBridge.resolveModel(7, model, 0L, 0) == 7,
                    "disabled mesh path changed the canonical model");
            TfcVoxyBridge.beginBake(model, 1 << 20);
            require(TfcVoxyBridge.bakedModel(new Object()) instanceof VariantModel,
                    "retiring renderer rejected an already queued synthetic bake");
            TfcVoxyBridge.endBake();

            setSettings(true);
            require(TfcCompatConfig.installed() && TfcCompatConfig.enabled(),
                    "installed TFC was not detected or enabled");
            require(TfcVoxyBridge.updateEnabledState(new Object()), "enable transition was not reported");
            require(get(null, "world") != null && get(null, "climate") != null,
                    "paused enable must establish the world before renderer recreation");
            require((boolean) get(null, "rendererReloadRequested"), "enable transition did not request recreation");
            set(null, "active", factory);
            TfcVoxyBridge.rendererReloaded();
            require(!(boolean) get(null, "rendererReloadRequested"), "renderer completion left a stale request");
            require(get(null, "active") == factory,
                    "renderer completion discarded an enabled factory before an unpaused tick");

            TfcClimateStore store = new TfcClimateStore();
            set(null, "climate", store);
            set(null, "world", new Object());
            set(null, "compatWasEnabled", true);
            setSettings(false);
            require(TfcVoxyBridge.updateEnabledState(new Object()), "disable transition was not reported");
            require((boolean) get(store, "closed"), "disabling did not close the climate sidecar");
            require(get(null, "climate") == null, "disabled bridge retained the climate store");
            TfcVoxyBridge.rendererReloaded();
            require(get(null, "active") == null, "renderer completion retained the disabled factory");
            require(TfcVoxyBridge.status().contains("inactive"), "disabled bridge status was active");
            System.out.println("PASS: TFC runtime default-off, installed detection, canonical disabled mesh, stale bake retirement, Apply transitions, climate close and renderer cleanup");
        } finally {
            setSettings(false);
            files.remove("tfc");
            set(null, "active", oldActive);
            set(null, "climate", oldClimate);
            set(null, "world", oldWorld);
            set(null, "adapter", oldAdapter);
            set(null, "compatWasEnabled", oldCompat);
            set(null, "configSeen", oldSeen);
            set(null, "rendererReloadRequested", oldRequest);
            set(null, "compatFastEnabled", oldFast);
        }
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

    private static Object construct(String suffix, Class<?>[] parameters, Object... values) throws Exception {
        Constructor<?> constructor = Class.forName(BRIDGE.getName() + "$" + suffix).getDeclaredConstructor(parameters);
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    private static Object get(Object owner, String name) throws Exception {
        Class<?> type = owner == null ? BRIDGE : owner.getClass();
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Class<?> type = owner == null ? BRIDGE : owner.getClass();
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
