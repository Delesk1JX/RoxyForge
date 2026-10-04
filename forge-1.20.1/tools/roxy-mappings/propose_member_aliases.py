"""Propose 1.20.1 members for the Voxy references the link checker could not resolve.

For each unresolved member it looks through the 1.20.1 jar for members of the same owner (or, when the
owner itself changed name, of a class with the same simple name) and ranks them by how well the
signature lines up: identical descriptor, same shape, same arity, then just same return type. The
output is meant to be read and curated into member-aliases.txt, not trusted blindly.

    propose_member_aliases.py <link report> <mc 1.20.1 srg jar> <intermediary 1.21.11>
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from build_mappings import Tiny, MojangMappings, DOWNLOADS  # noqa: E402
from mcindex import JarIndex  # noqa: E402

DESCRIPTOR = re.compile(r"^(?:\[[BCDFIJSZV]|L[\w$/]+;).*$")


def shape(descriptor: str) -> str:
    return re.sub(r"L[\w$/]+;", "L;", descriptor)


def return_type(descriptor: str) -> str:
    return descriptor[descriptor.rindex(")") + 1:]


def arity(descriptor: str) -> int:
    inner = descriptor[1:descriptor.rindex(")")]
    count, index = 0, 0
    while index < len(inner):
        if inner[index] == "L":
            index = inner.index(";", index) + 1
        elif inner[index] == "[":
            pass
        else:
            count += 1
        index += 1
    return count


def split_name_and_descriptor(entry: str) -> tuple[str, str]:
    match = re.match(r"^([A-Za-z_$][\w$]*?)((?:\[[BCDFIJSZV]|L[\w$/]+;).*)$", entry)
    return (match.group(1), match.group(2)) if match else (entry, "")


def main() -> None:
    report = Path(sys.argv[1]).read_text(encoding="utf-8")
    minecraft = JarIndex.read(Path(sys.argv[2]))
    intermediary = Tiny.read(DOWNLOADS / "intermediary-1.21.11.jar")
    new_by_obf = {obf: official for official, obf in
                  MojangMappings.read(DOWNLOADS / "client-1.21.11.txt").classes.items()}

    def official_of(intermediary_name: str) -> str:
        obfuscated = intermediary.classes.get(intermediary_name, "")
        return new_by_obf.get(obfuscated, intermediary_name)

    sections = report.split("--- unresolved methods")[1]
    methods_text, fields_text = sections.split("--- unresolved fields")
    fields_text = fields_text.split("--- unresolved")[0]

    def members_of(owner: str) -> list[tuple[str, str]]:
        facts = minecraft.classes.get(owner)
        if not facts:
            return []
        return sorted((entry[: -len(shape_of(entry))], entry) for entry in facts.methods | facts.fields)

    def shape_of(entry: str) -> str:
        index = entry.index("(") if "(" in entry else entry.rindex("L")
        return entry[index:]

    rows = []
    for text, is_method in ((methods_text, True), (fields_text, False)):
        for line in text.splitlines():
            if not line.strip():
                continue
            entry = line.split(None, 1)[1].strip()
            owner, _, member = entry.partition(".")
            if "(" in member:
                name = member.split("(")[0]
                descriptor = "(" + member.split("(", 1)[1]
            else:
                name, descriptor = split_name_and_descriptor(member)
            rows.append((owner, name, descriptor, is_method))

    proposed = 0
    for owner, name, descriptor, is_method in rows:
        candidates = minecraft.classes.get(owner)
        if candidates is None:
            # The owner may have been renamed; look for a 1.20.1 class with the same simple name.
            simple = owner.rsplit("/", 1)[-1]
            aliases = [cls for cls in minecraft.classes if cls.rsplit("/", 1)[-1] == simple]
            candidates = minecraft.classes.get(aliases[0]) if len(aliases) == 1 else None
        if candidates is None:
            print(f"\n{owner}.{name}{descriptor}  -> owner class not found in 1.20.1")
            continue
        pool = candidates.methods if is_method else candidates.fields
        scored = []
        for member_entry in pool:
            member_descriptor = shape_of(member_entry)
            if member_descriptor == descriptor:
                score = 0
            elif shape(member_descriptor) == shape(descriptor):
                score = 1
            elif arity(member_descriptor) == arity(descriptor):
                score = 2
            elif return_type(member_descriptor) == return_type(descriptor):
                score = 3
            else:
                continue
            scored.append((score, member_entry))
        scored.sort()
        print(f"\n{owner}.{name}{descriptor}")
        print(f"   1.20.1 owner: {official_of(owner)} -> {owner} ({len(pool)} members)")
        for score, member_entry in scored[:4]:
            print(f"   {'exact' if score == 0 else 'shape' if score == 1 else 'arity' if score == 2 else 'return'}"
                  f"   {member_entry}")
        if scored:
            proposed += 1
    print(f"\n{len(rows)} unresolved members, {proposed} with at least one candidate")


if __name__ == "__main__":
    main()