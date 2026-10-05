"""Assemble the runtime copy of Voxy that Forge 1.20.1 will load as a mod.

The remap itself is done by `gradlew remapVoxy`; this adds what Forge needs to treat the jar as a mod:
a `META-INF/mods.toml` and a `@Mod`-annotated entrypoint class (FML only builds a mod container from
classes inside the mod file). The result is a normal mod - no loader magic at game start, which is why
this happens at build time instead of inside an IDependencyLocator.

    make_voxy_copy.py <remapped voxy jar> <entrypoint class> <output jar>
"""
import sys
import zipfile
from pathlib import Path

MODS_TOML = """modLoader = "javafml"
loaderVersion = "[47,)"
license = "All Rights Reserved (Voxy is not redistributed by RoxyForge)"

[[mods]]
modId = "voxy"
version = "{version}"
displayName = "Voxy (loaded by RoxyForge)"

[[dependencies.voxy]]
modId = "minecraft"
mandatory = true
versionRange = "[1.20.1,1.20.2)"
ordering = "AFTER"
side = "CLIENT"
"""


def main() -> None:
    source = Path(sys.argv[1])
    entrypoint = Path(sys.argv[2])
    target = Path(sys.argv[3])

    version = "0.0.0"
    with zipfile.ZipFile(source) as probe:
        if "fabric.mod.json" in probe.namelist():
            import json
            version = str(json.loads(probe.read("fabric.mod.json")).get("version", version))

    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(source) as src, \
            zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as out:
        for item in src.infolist():
            if item.filename.upper() in ("META-INF/MANIFEST.MF", "META-INF/MODS.TOML"):
                continue
            out.writestr(item, src.read(item.filename))
        out.writestr("META-INF/mods.toml", MODS_TOML.format(version=version))
        out.writestr("net/voxy/Voxy.class", entrypoint.read_bytes())

    with zipfile.ZipFile(target) as check:
        names = check.namelist()
    print(f"wrote {target} ({target.stat().st_size} bytes, v{version})")
    print(f"  mods.toml: {'META-INF/mods.toml' in names}"
          f"  entrypoint: {'net/voxy/Voxy.class' in names}"
          f"  classes: {len([n for n in names if n.endswith('.class')])}")


if __name__ == "__main__":
    main()