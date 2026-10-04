"""For every Minecraft class Voxy needs that 1.20.1 lacks: does a same-named class exist, what are the
closest existing classes, and which members does Voxy actually call on it?

    plan_shims.py <link-report> <client-1.21.11.txt> <mc 1.20.1 srg jar> <intermediary 1.21.11>
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from build_mappings import Tiny, MojangMappings, DOWNLOADS  # noqa: E402
from mcindex import JarIndex  # noqa: E402


def main() -> None:
    report = Path(sys.argv[1]).read_text(encoding="utf-8")
    new_by_obf = {obf: official for official, obf in MojangMappings.read(Path(sys.argv[2])).classes.items()}
    minecraft = JarIndex.read(Path(sys.argv[3]))
    intermediary = Tiny.read(DOWNLOADS / "intermediary-1.21.11.jar")

    section = report.split("--- unresolved classes")[1].split("--- unresolved methods")[0]
    missing = re.findall(r"\s+(\d+)\s+(net/minecraft/class_\d+)", section)
    methods_section = report.split("--- unresolved methods")[1].split("--- unresolved fields")[0]
    fields_section = report.split("--- unresolved fields")[1]
    unresolved_members = methods_section + fields_section

    for _, name in missing:
        official = new_by_obf.get(intermediary.classes.get(name, ""), "?")
        official_internal = official.replace(".", "/")
        tail = official_internal.rsplit("/", 1)[-1]
        same_name = minecraft.has_class(official_internal)
        near = sorted({cls for cls in minecraft.classes
                       if cls.rsplit("/", 1)[-1].lower() == tail.lower()})[:3]
        near_fields = sorted({cls for cls in minecraft.classes
                              if tail.lower() in cls.rsplit("/", 1)[-1].lower()})[:3]
        print(f"\n{name} = {official_internal}   same name in 1.20.1: {same_name}")
        if near or near_fields:
            print(f"   similar classes: {(near + near_fields)[:4]}")
        used = [line.split(None, 1)[1] for line in unresolved_members.splitlines()
                if line.strip().startswith(official_internal + ".")]
        for member in used[:8]:
            print(f"   Voxy calls: {member[len(official_internal) + 1:]}")
        if len(used) > 8:
            print(f"   ... and {len(used) - 8} more")


if __name__ == "__main__":
    main()