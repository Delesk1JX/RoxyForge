import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import net.rasanovum.roxy.loader.RoxyBytecodeRemapper;
import net.rasanovum.roxy.loader.RoxyMappings;
import net.rasanovum.roxy.shader.RoxyModelTintShader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public final class VerifyTfcMerge {
    public static void main(String[] args) throws Exception {
        RoxyMappings mappings = RoxyMappings.load();
        try (ZipFile voxy = new ZipFile(Path.of(args[0]).toFile())) {
            boolean ice = false, tfc = false;
            for (String type : List.of("model/ModelFactory", "model/ModelBakerySubsystem",
                    "rendering/building/RenderDataFactory")) {
                String path = "me/cortex/voxy/client/core/" + type + ".class";
                byte[] transformed = RoxyBytecodeRemapper.remap(voxy.getInputStream(voxy.getEntry(path)).readAllBytes(), mappings);
                ClassNode node = new ClassNode();
                new ClassReader(transformed).accept(node, 0);
                for (var method : node.methods) {
                    new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
                    if (!method.name.equals("processTextureBakeResult")) continue;
                    boolean sawIce = false;
                    for (var instruction : method.instructions) {
                        if (!(instruction instanceof MethodInsnNode call)) continue;
                        if (call.owner.equals("net/rasanovum/roxy/bridge/RoxyIceModelBridge")
                                && call.name.equals("addIceBackfaceFlag")) {
                            sawIce = true;
                            ice = true;
                        }
                        if (call.owner.equals("net/rasanovum/roxy/tfc/TfcModelLighting") && call.name.equals("flags")) {
                            require(sawIce, "TFC AO hook must preserve main's ice flag hook");
                            tfc = true;
                        }
                    }
                }
            }
            require(ice && tfc, "Both model flag patches must be present");
            String path = "assets/voxy/shaders/lod/quad_util.glsl";
            String shader = new String(voxy.getInputStream(voxy.getEntry(path)).readAllBytes(), StandardCharsets.UTF_8);
            String patched = RoxyModelTintShader.patch("/" + path, shader);
            require(patched.contains("bool hasAO = isShaded || (model.flagsA & 32u) != 0u;"),
                    "TFC AO must use bit 32, leaving main's ice bit 16 alone");
            require(!patched.contains("bool hasAO = isShaded || (model.flagsA & 16u)"),
                    "Ice must not force TFC ambient occlusion");
        }
        System.out.println("PASS: transformed model/bakery/mesh bytecode, combined ice/TFC flags and independent AO shader bit");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
