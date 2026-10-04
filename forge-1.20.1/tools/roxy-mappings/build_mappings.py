"""Build RoxyForge's runtime mapping: Fabric intermediary 1.21.11 -> Forge 1.20.1 SRG members.

Voxy is compiled against Fabric intermediary names for 1.21.11. Forge 1.20.1 keeps Mojang's official
class names at runtime but renames every field/method to SRG (`m_7160_`). So the mapping we need is:

    class_2248                                -> net/minecraft/world/entity/Entity   (official 1.20.1)
    class_2248.method_5678(Lclass_2338;I)V     -> m_1234_(Lnet/minecraft/world/entity/Entity;I)V

Chain: intermediary(1.21.11) matches intermediary(1.20.1) by name (Fabric's intermediary namespace is
stable across versions), which gives official(1.20.1); Mojang's client.txt turns that into obfuscated
names, and Forge's joined.tsrg turns obfuscated into SRG.

Usage:
    python build_mappings.py --voxy path/to/voxy.jar [--out roxy-mappings] [--report report.txt]
"""
from __future__ import annotations

import argparse
import json
import re
import struct
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
DOWNLOADS = HERE / "downloads"

TAG_UTF8 = 1
TAG_CLASS = 7
TAG_FIELD = 9
TAG_METHOD = 10
TAG_NAME_AND_TYPE = 12


# --------------------------------------------------------------------------- tiny v1

class Tiny:
    """Fabric intermediary mappings (tiny v1: official <-> intermediary)."""

    def __init__(self) -> None:
        self.classes: dict[str, str] = {}          # intermediary -> official
        self.fields: dict[tuple[str, str, str], str] = {}
        self.methods: dict[tuple[str, str, str], str] = {}

    @classmethod
    def read(cls, jar: Path) -> "Tiny":
        with zipfile.ZipFile(jar) as zf:
            text = zf.read("mappings/mappings.tiny").decode("utf-8")
        return cls.parse(text)

    @classmethod
    def parse(cls, text: str) -> "Tiny":
        result = cls()
        intermediary_owner = None
        for line in text.splitlines():
            parts = line.split("\t")
            head = parts[0]
            if head.startswith("v1") or head.startswith("v2"):
                continue
            if head == "CLASS" and len(parts) >= 3:
                result.classes[parts[2]] = parts[1]
                intermediary_owner = parts[2]
                continue
            if len(parts) >= 5 and parts[0] in ("FIELD", "METHOD"):
                # tiny v1: <FIELD|METHOD> <owner> <descriptor> <name> <intermediaryName>
                owner, descriptor, name, intermediary = parts[1], parts[2], parts[3], parts[4]
                if intermediary_owner is None:
                    continue
                key = (intermediary_owner, intermediary, _to_intermediary_descriptor(descriptor, result))
                if parts[0] == "FIELD":
                    result.fields[key] = (owner, name, descriptor)
                else:
                    result.methods[key] = (owner, name, descriptor)
        return result


def _to_intermediary_descriptor(descriptor: str, tiny: Tiny) -> str:
    official_to_intermediary = {official: inter for inter, official in tiny.classes.items()}
    out = []
    index = 0
    while index < len(descriptor):
        char = descriptor[index]
        out.append(char)
        if char != "L":
            index += 1
            continue
        end = descriptor.index(";", index)
        internal = descriptor[index + 1:end]
        out.append(official_to_intermediary.get(internal, internal))
        out.append(";")
        index = end + 1
    return "".join(out)


# --------------------------------------------------------------------------- Mojang client.txt

class MojangMappings:
    """ProGuard mappings: official (dotted) -> obfuscated."""

    def __init__(self) -> None:
        self.classes: dict[str, str] = {}              # dotted official -> obf internal
        self.fields: dict[tuple[str, str], str] = {}
        self.members: dict[tuple[str, str], str] = {}

    @classmethod
    def read(cls, path: Path) -> "MojangMappings":
        result = cls()
        owner = None
        for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
            if not raw.startswith(" "):
                if " -> " in raw and raw.rstrip().endswith(":"):
                    official = raw.split(" -> ", 1)[0].strip()
                    obf = raw.rsplit(" -> ", 1)[1].strip()[:-1]
                    owner = official.replace(".", "/")
                    result.classes[owner] = obf
                continue
            if owner is None or " -> " not in raw:
                continue
            body = raw.strip()
            # Mojang lines start with optional "<line>:<line>:" prefixes ("10:10:void foo() -> a").
            body = re.sub(r"^(?:\d+:\d+:)+", "", body)
            head, obf_name = body.rsplit(" -> ", 1)
            head = head.strip()
            obf_name = obf_name.strip()
            if "(" in head:
                name = head[head.rindex(" ") + 1:head.index("(")]
            elif " " in head:
                name = head[head.rindex(" ") + 1:]
            else:
                name = head
            result.members[(owner, name)] = obf_name
        return result


# --------------------------------------------------------------------------- Forge tsrg

class Tsrg:
    """Forge joined.tsrg: obfuscated -> SRG."""

    def __init__(self) -> None:
        self.classes: dict[str, str] = {}
        self.members: dict[tuple[str, str], str] = {}
        self.members_by_descriptor: dict[tuple[str, str, str], str] = {}

    @classmethod
    def parse(cls, text: str) -> "Tsrg":
        result = cls()
        obf_owner = None
        srg_owner = None
        for raw in text.splitlines():
            if not raw:
                continue
            if not raw.startswith("\t"):
                parts = raw.split(" ")
                if len(parts) >= 2 and not parts[0].startswith("tsrg"):
                    obf_owner, srg_owner = parts[0], parts[1]
                    result.classes[obf_owner] = srg_owner
                continue
            stripped = raw.lstrip("\t")
            depth = len(raw) - len(stripped)
            parts = stripped.split(" ")
            if parts[0] == "c":
                continue
            # tsrg2 members are "<obfName> [desc] <srgName> <id>"; methods carry the descriptor, and
            # overloads share an obfuscated name but not an SRG one, so keep a descriptor-keyed index too.
            if len(parts) >= 4:
                result.members[(obf_owner, parts[0])] = parts[2]
                result.members_by_descriptor[(obf_owner, parts[0], parts[1])] = parts[2]
            elif len(parts) == 3:
                result.members[(obf_owner, parts[0])] = parts[1]
        return result


# --------------------------------------------------------------------------- class files

def class_references(data: bytes) -> tuple[set[str], set[tuple[str, str, str]], set[tuple[str, str, str]]]:
    """(classes, fields, methods) referenced by a class file, from its constant pool."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return set(), set(), set()
    count = struct.unpack(">H", data[8:10])[0]
    offset = 10
    utf8: list[str | None] = [None] * count
    class_index: dict[int, str] = {}
    name_type: dict[int, tuple[str, str]] = {}
    refs: list[tuple[int, int, int]] = []

    index = 1
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == TAG_UTF8:
            length = struct.unpack(">H", data[offset:offset + 2])[0]
            utf8[index] = data[offset + 2:offset + 2 + length].decode("utf-8", "replace")
            offset += 2 + length
        elif tag in (3, 4):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            index += 1
        elif tag == TAG_CLASS:
            class_index[index] = struct.unpack(">H", data[offset:offset + 2])[0]
            offset += 2
        elif tag in (7, 8, 16, 19, 20):
            offset += 2
        elif tag in (TAG_FIELD, TAG_METHOD):
            refs.append((struct.unpack(">H", data[offset:offset + 2])[0],
                         struct.unpack(">H", data[offset + 2:offset + 4])[0]))
            offset += 4
        elif tag == TAG_NAME_AND_TYPE:
            name_type[index] = (struct.unpack(">H", data[offset:offset + 2])[0],
                                struct.unpack(">H", data[offset + 2:offset + 4])[0])
            offset += 4
        elif tag in (11, 12, 17, 18):
            offset += 4
        elif tag == 15:
            offset += 3
        else:
            break
        index += 1

    classes = {utf8[class_index[i]] for i in class_index if utf8[class_index[i]]}
    fields: set[tuple[str, str, str]] = set()
    methods: set[tuple[str, str, str]] = set()
    for class_ref, name_ref in refs:
        owner = utf8[class_index.get(class_ref, 0)] if class_ref in class_index else None
        if owner is None or name_ref not in name_type:
            continue
        name, descriptor = name_type[name_ref]
        entry = (owner, utf8[name], utf8[descriptor])
        if name_ref and (class_ref, name_ref) and entry not in methods:
            pass
        methods.add(entry)
    return classes, fields, methods


def split_field_or_method(data: bytes) -> tuple[set[str], set[tuple[str, str, str]], set[tuple[str, str, str]]]:
    classes, _, members = class_references(data)
    # The constant pool does not keep the field/method tag per ref, so classify by descriptor.
    fields = {m for m in members if "(" not in m[2]}
    methods = {m for m in members if "(" in m[2]}
    return classes, fields, methods


def _descriptor_shape(descriptor: str) -> str:
    """Descriptor with every reference type erased, so overloads can be compared across versions."""
    out = []
    index = 0
    while index < len(descriptor):
        char = descriptor[index]
        if char == "L":
            end = descriptor.find(";", index)
            if end < 0:
                break
            out.append("L;")
            index = end + 1
            continue
        out.append(char)
        index += 1
    return "".join(out)


def _arity(descriptor: str) -> int:
    depth = 0
    count = 0
    index = descriptor.find("(")
    if index < 0:
        return 0
    index += 1
    start = index
    while index < len(descriptor):
        char = descriptor[index]
        if char == "L":
            end = descriptor.find(";", index)
            index = end + 1 if end > 0 else index
        elif char == "[":
            pass
        elif char == ")":
            if index > start:
                count += 1
            return count
        index += 1
    return count


def _to_target_descriptor(descriptor: str, official_by_intermediary: dict[str, str]) -> str:
    """Rewrite a descriptor's class names into the target (official 1.20.1) namespace."""
    out = []
    index = 0
    while index < len(descriptor):
        char = descriptor[index]
        if char != "L":
            out.append(char)
            index += 1
            continue
        end = descriptor.find(";", index)
        if end < 0:
            break
        out.append("L" + official_by_intermediary.get(descriptor[index + 1:end], descriptor[index + 1:end]))
        out.append(";")
        index = end + 1
    return "".join(out)


def _primitives(descriptor: str) -> str:
    """Primitives and array depth only, which survive version changes more often than class names."""
    out = []
    index = descriptor.find("(")
    index = 0 if index < 0 else index
    while index < len(descriptor):
        char = descriptor[index]
        if char == "L":
            end = descriptor.find(";", index)
            if end < 0:
                break
            out.append("L")
            index = end + 1
            continue
        if char == ")":
            break
        out.append(char)
        index += 1
    return "".join(out)


# --------------------------------------------------------------------------- build

def build(voxy_jar: Path | None, out_dir: Path, report_path: Path, mc_jar: Path | None = None) -> None:
    # Fabric's 1.21.11 tiny maps official <-> intermediary; its 1.20.1 tiny maps obfuscated <-> intermediary,
    # because Mojang mappings did not exist for 1.20.1. Matching the intermediary namespace across the two is
    # what ties 1.21.11 code to 1.20.1 classes.
    intermediary_new = Tiny.read(DOWNLOADS / "intermediary-1.21.11.jar")
    intermediary_old = Tiny.read(DOWNLOADS / "intermediary-1.20.1.jar")
    mojang = MojangMappings.read(DOWNLOADS / "client-1.20.1.txt")
    with zipfile.ZipFile(DOWNLOADS / "mcp_config-1.20.1.zip") as zf:
        tsrg = Tsrg.parse(zf.read("config/joined.tsrg").decode("utf-8", "replace"))

    # intermediary -> obfuscated(1.20.1) -> official(1.20.1). Forge 1.20.1 runs with official class names.
    obf_old_to_official = {obf: official for official, obf in mojang.classes.items()}

    # Curated renames for 1.21-only classes that exist in 1.20.1 under another name. Every alias target
    # is validated against the Minecraft jar below, so a wrong line is dropped instead of silently
    # producing NoClassDefFoundError at runtime.
    aliases: dict[str, str] = {}
    alias_file = Path(__file__).resolve().parent / "aliases.txt"
    if alias_file.exists():
        for line in alias_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) == 2:
                aliases[parts[0]] = parts[1]

    # 1.21.11 obfuscated -> mojmap, needed only so aliases can be written in readable class names.
    new_by_obf: dict[str, str] = {}
    new_mojmap = DOWNLOADS / "client-1.21.11.txt"
    if new_mojmap.exists() and aliases:
        new_by_obf = {obf: official for official, obf in MojangMappings.read(new_mojmap).classes.items()}
        print(f"aliases loaded: {len(aliases)} (1.21.11 mojmap: {'yes' if new_by_obf else 'no'})")
    elif aliases:
        print(f"aliases loaded: {len(aliases)} but client-1.21.11.txt is missing, they will not apply")
        aliases = {}

    def target_class(intermediary_name: str) -> str | None:
        # Preferred path: name the class in 1.21.11 mojmap terms, then apply a curated alias. This is the
        # only way to reach classes that 1.20.1 does not have at all.
        if new_by_obf:
            obfuscated_new = intermediary_new.classes.get(intermediary_name)
            official_new = new_by_obf.get(obfuscated_new) if obfuscated_new else None
            if official_new:
                internal_new = official_new.replace(".", "/")
                if internal_new in aliases:
                    return aliases[internal_new]
        obf = intermediary_old.classes.get(intermediary_name)
        if obf is None:
            return None
        return obf_old_to_official.get(obf)

    def target_member(obf_owner: str, obf_name: str, obf_descriptor: str | None) -> str | None:
        if obf_descriptor is not None:
            exact = tsrg.members_by_descriptor.get((obf_owner, obf_name, obf_descriptor))
            if exact:
                return exact
        return tsrg.members.get((obf_owner, obf_name))

    out_dir.mkdir(parents=True, exist_ok=True)

    class_lines = []
    for intermediary in sorted(intermediary_new.classes):
        official = target_class(intermediary)
        if official:
            class_lines.append(f"C\t{intermediary}\t{official}")

    # intermediary member names are stable across versions, descriptors are not, so match on the name
    # and use the descriptor only to pick between overloads.
    def index_members(table: dict) -> dict[tuple[str, str], list[tuple[str, str, str]]]:
        index: dict[tuple[str, str], list[tuple[str, str, str]]] = {}
        for (owner, name, descriptor), (obf_owner, obf_name, _) in table.items():
            index.setdefault((owner, name), []).append((descriptor, obf_owner, obf_name))
        return index

    old_methods = index_members(intermediary_old.methods)
    old_fields = index_members(intermediary_old.fields)

    def match_member(index, owner, name, descriptor):
        """Best 1.20.1 candidate for one 1.21.11 member, or None when the choice would be a guess."""
        candidates = index.get((owner, name))
        if not candidates:
            return None, "missing"
        for entry in candidates:
            if entry[0] == descriptor:
                return entry, "exact"
        if len(candidates) == 1:
            return candidates[0], "name-only"
        wanted = _descriptor_shape(descriptor)
        same_shape = [entry for entry in candidates if _descriptor_shape(entry[0]) == wanted]
        if len(same_shape) == 1:
            return same_shape[0], "shape"
        # Last resort: same number of arguments and same primitive/array positions.
        same_arity = [entry for entry in candidates
                      if _arity(entry[0]) == _arity(descriptor) and
                      _primitives(entry[0]) == _primitives(descriptor)]
        if len(same_arity) == 1:
            return same_arity[0], "arity"
        return None, "ambiguous"

    member_lines = []
    quality = {"exact": 0, "name-only": 0, "shape": 0, "arity": 0}
    ambiguous: list[str] = []
    minecraft = None
    if mc_jar and Path(mc_jar).exists():
        from mcindex import JarIndex
        minecraft = JarIndex.read(Path(mc_jar))

    def resolve(entry, official_owner):
        """(srg name, target descriptor) for a 1.20.1 candidate, or (None, None) when it cannot link."""
        obf_descriptor, obf_owner, obf_name = entry
        srg = target_member(obf_owner, obf_name, obf_descriptor)
        if not srg or not official_owner:
            return None, None
        target_descriptor = _to_target_descriptor(obf_descriptor, obf_old_to_official)
        return srg, target_descriptor

    def exists(kind, official_owner, srg, target_descriptor):
        """Does 1.20.1 really declare this? Without the jar we cannot tell, so we trust the mapping."""
        if minecraft is None:
            return True
        if not minecraft.has_class(official_owner):
            return False
        if kind == "M":
            return minecraft.has_method(official_owner, srg, target_descriptor)
        return minecraft.has_field(official_owner, srg, target_descriptor)

    repaired = 0
    for kind, table, index in (("M", intermediary_new.methods, old_methods),
                               ("F", intermediary_new.fields, old_fields)):
        for (owner, name, descriptor) in table:
            entry, how = match_member(index, owner, name, descriptor)
            official_owner = target_class(owner)
            if official_owner is None:
                continue
            chosen = None
            if entry is not None:
                srg, target_descriptor = resolve(entry, official_owner)
                if srg and exists(kind, official_owner, srg, target_descriptor):
                    chosen = (how, srg, target_descriptor)
            if chosen is None:
                # The overload we guessed at does not exist in 1.20.1; try every other overload of the
                # same intermediary name before giving up - one of them is the right one.
                for other in index.get((owner, name), []):
                    srg, target_descriptor = resolve(other, official_owner)
                    if srg and exists(kind, official_owner, srg, target_descriptor):
                        chosen = ("repaired", srg, target_descriptor)
                        repaired += 1
                        break
            if chosen is None:
                if entry is not None and how == "ambiguous":
                    ambiguous.append(f"{kind} {owner}.{name}{descriptor}")
                continue
            how, srg, target_descriptor = chosen
            quality[how if how in quality else "exact"] += 1
            member_lines.append(f"{kind}\t{owner}\t{name}\t{descriptor}\t{official_owner}\t{srg}\t{target_descriptor}")

    target = out_dir / "intermediary-1.21.11-to-srg-1.20.1.txt"

    # Validation against the real Minecraft jar: an entry is only worth keeping if the target name
    # actually exists in 1.20.1. A wrong SRG name is worse than no mapping at all, because it turns a
    # resolvable name into a NoSuchMethodError at runtime.
    dropped_classes = 0
    dropped_members = 0
    if mc_jar and Path(mc_jar).exists():
        from mcindex import JarIndex
        minecraft = JarIndex.read(Path(mc_jar))
        official_by_intermediary = dict(line.split("\t")[1:] for line in class_lines)

        kept_classes = []
        for line in class_lines:
            if minecraft.has_class(line.split("\t")[2]):
                kept_classes.append(line)
            else:
                dropped_classes += 1
        class_lines = kept_classes

        kept_members = []
        for line in member_lines:
            parts = line.split("\t")
            kind, target_owner, target_name = parts[0], parts[4], parts[5]
            descriptor = parts[6] if len(parts) > 6 else _to_target_descriptor(parts[3], official_by_intermediary)
            if not minecraft.has_class(target_owner):
                dropped_members += 1
            elif kind == "M" and not minecraft.has_method(target_owner, target_name, descriptor):
                dropped_members += 1
            elif kind == "F" and not minecraft.has_field(target_owner, target_name, descriptor):
                dropped_members += 1
            else:
                kept_members.append(line)
        member_lines = kept_members
        print(f"validated against {Path(mc_jar).name}: dropped {dropped_classes} classes, "
              f"{dropped_members} members that 1.20.1 does not have")

    with target.open("w", encoding="utf-8") as handle:
        handle.write("# RoxyForge runtime mapping: Fabric intermediary 1.21.11 -> Forge 1.20.1\n")
        handle.write("# Forge 1.20.1 runs with official class names and SRG members (m_/f_).\n")
        handle.write("# C <intermediary class> <official 1.20.1 class>\n")
        handle.write("# M <owner> <name> <descriptor> <official owner> <srg name>\n")
        handle.write("# F <owner> <name> <descriptor> <official owner> <srg name>\n")
        for line in class_lines:
            handle.write(line + "\n")
        for line in member_lines:
            handle.write(line + "\n")

    stats = {
        "classes_total": len(intermediary_new.classes),
        "classes_mapped": len(class_lines),
        "methods_total": len(intermediary_new.methods),
        "methods_mapped": sum(1 for line in member_lines if line.startswith("M")),
        "fields_total": len(intermediary_new.fields),
        "fields_mapped": sum(1 for line in member_lines if line.startswith("F")),
        "tsrg_classes": len(tsrg.classes),
        "match_exact": quality["exact"],
        "match_name_only": quality["name-only"],
        "match_shape": quality["shape"],
        "ambiguous": len(ambiguous),
    }

    if voxy_jar and voxy_jar.exists():
        classes_mapped = {parts[1]: parts[2] for parts in (line.split("\t") for line in class_lines)}
        member_index = {}
        for line in member_lines:
            parts = line.split("\t")
            member_index[(parts[1], parts[2], parts[3])] = (parts[4], parts[5])

        total_classes = hit_classes = 0
        total_members = hit_members = 0
        missing: dict[str, int] = {}
        with zipfile.ZipFile(voxy_jar) as zf:
            for entry in zf.namelist():
                if not entry.endswith(".class"):
                    continue
                classes, fields, methods = split_field_or_method(zf.read(entry))
                for referenced in classes:
                    # Only Minecraft's own names need remapping; JDK, Sodium and Voxy's own classes do not.
                    if not referenced.startswith("net/minecraft"):
                        continue
                    total_classes += 1
                    if referenced in classes_mapped:
                        hit_classes += 1
                    else:
                        missing[f"class {referenced}"] = missing.get(f"class {referenced}", 0) + 1
                for member in fields | methods:
                    if not member[0].startswith("net/minecraft"):
                        continue
                    total_members += 1
                    if member in member_index:
                        hit_members += 1
                    else:
                        missing[f"member {member[0]}.{member[1]}{member[2]}"] = \
                            missing.get(f"member {member[0]}.{member[1]}{member[2]}", 0) + 1
        stats.update(voxy_classes_total=total_classes, voxy_classes_mapped=hit_classes,
                     voxy_members_total=total_members, voxy_members_mapped=hit_members)
        stats["voxy_class_coverage"] = round(100.0 * hit_classes / total_classes, 2) if total_classes else 0.0
        stats["voxy_member_coverage"] = round(100.0 * hit_members / total_members, 2) if total_members else 0.0
        top = sorted(missing.items(), key=lambda item: -item[1])[:40]
        stats["top_unmapped"] = top

    report_path.write_text(json.dumps(stats, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in stats.items() if k != "top_unmapped"}, indent=2))
    print(f"mapping file: {target}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--voxy", type=Path, help="Voxy jar to measure coverage against")
    parser.add_argument("--out", type=Path, default=HERE / "roxy-mappings")
    parser.add_argument("--report", type=Path, default=HERE / "coverage-report.json")
    parser.add_argument("--mc-jar", type=Path, help="Minecraft 1.20.1 SRG jar to validate the mapping against")
    args = parser.parse_args()
    build(args.voxy, args.out, args.report, args.mc_jar)


if __name__ == "__main__":
    main()