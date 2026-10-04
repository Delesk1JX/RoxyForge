"""Name the Minecraft classes Voxy references that 1.20.1 does not have.

Fabric's intermediary-1.21.11 jar labels its left column "official" but actually holds obfuscated
names, so naming those classes needs Mojang's own 1.21.11 client.txt (obfuscated -> mojmap).

    name_missing.py <link-report> <client-1.21.11.txt> [client-1.20.1.txt]
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from build_mappings import Tiny, MojangMappings, DOWNLOADS  # noqa: E402


def main() -> None:
    report = Path(sys.argv[1]).read_text(encoding="utf-8")
    new_by_obf = {obf: official for official, obf in MojangMappings.read(Path(sys.argv[2])).classes.items()}
    old_official = set()
    if len(sys.argv) > 3:
        old_official = {official for official in MojangMappings.read(Path(sys.argv[3])).classes}

    intermediary_new = Tiny.read(DOWNLOADS / "intermediary-1.21.11.jar")
    section = report.split("--- unresolved classes")[1].split("--- unresolved methods")[0]
    missing = re.findall(r"\s+(\d+)\s+(net/minecraft/class_\d+)", section)

    print(f"{'intermediary':34} {'refs':>4}  {'1.21.11 mojmap':38}  1.20.1 counterpart")
    total = 0
    for count, name in sorted(missing, key=lambda item: -int(item[0])):
        obfuscated = intermediary_new.classes.get(name)
        official = new_by_obf.get(obfuscated, "?") if obfuscated else "?"
        counterpart = "same name" if official in old_official else ""
        total += int(count)
        print(f"{name:34} {count:>4}  {official.replace('.', '/'):38}  {counterpart or '-'}")
    print(f"\n{len(missing)} classes, {total} references")


if __name__ == "__main__":
    main()