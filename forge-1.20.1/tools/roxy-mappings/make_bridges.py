"""Turn the link checker's unresolved members into bridge methods, and tell the remapper about them.

A bridge is a static method we own that stands in for a Minecraft call Voxy makes but 1.20.1 cannot
satisfy. The remapper rewrites `Owner.member(args)` into `RoxyBridge.mNNNN(owner, args)`, so Voxy links
and the body can be implemented against the 1.20.1 API later.

Bridges are **additive**: once a call site has been redirected its bridge has to stay, otherwise the next
round - which only knows about what is still unresolved - drops it and the count oscillates instead of
shrinking. So bridges.txt carries the arity of every entry, which is all that is needed to regenerate
RoxyBridge.java from the whole accumulated table.

    bridges.txt line: <M|F> <owner> <member> <bridge> <arity>
    the member is spelled the way the bytecode spells it (name and descriptor glued)

    make_bridges.py <link report> <mapping dir> <source dir>
"""
import re
import sys
from pathlib import Path

PRIMITIVES = {"V", "Z", "B", "C", "S", "I", "J", "F", "D"}
NAME_AND_DESCRIPTOR = re.compile(r"^([A-Za-z_$][\w$]*?)((?:\[[BCDFIJSZV]|L[\w$/]+;).*)$")


def java_type(descriptor: str) -> str:
    dims = 0
    while descriptor.startswith("["):
        dims += 1
        descriptor = descriptor[1:]
    if descriptor in PRIMITIVES:
        base = {"V": "void", "Z": "boolean", "B": "byte", "C": "char", "S": "short", "I": "int",
                "J": "long", "F": "float", "D": "double"}[descriptor]
    else:
        base = descriptor.lstrip("L").rstrip(";").replace("/", ".")
    return base + "[]" * dims


def split_parameters(descriptor: str) -> list[str]:
    inner = descriptor[1:descriptor.rindex(")")]
    parameters, index = [], 0
    while index < len(inner):
        start = index
        while index < len(inner) and inner[index] == "[":
            index += 1
        if index < len(inner) and inner[index] == "L":
            index = inner.index(";", index) + 1
        else:
            index += 1
        parameters.append(inner[start:index])
    return parameters


def split_field(entry: str) -> tuple[str, str]:
    """Split a field reference into name and descriptor.

    The link checker prints name:descriptor, but older reports concatenate the two, which is ambiguous
    (a field called field_60582F ends in a letter that looks like a primitive descriptor). Both forms are
    accepted so a stale report cannot silently produce broken keys.
    """
    name, separator, descriptor = entry.partition(":")
    if separator:
        return name, descriptor
    match = NAME_AND_DESCRIPTOR.match(entry)
    return (match.group(1), match.group(2)) if match else (entry, "")


def main() -> None:
    report = Path(sys.argv[1]).read_text(encoding="utf-8")
    mapping_dir = Path(sys.argv[2])
    source_dir = Path(sys.argv[3])

    sections = report.split("--- unresolved methods")[1]
    methods_text, fields_text = sections.split("--- unresolved fields")
    fields_text = fields_text.split("--- unresolved")[0]

    rows: list[tuple[str, str, str, bool]] = []
    for text, is_method in ((methods_text, True), (fields_text, False)):
        for line in text.splitlines():
            if not line.strip():
                continue
            entry = line.split(None, 1)[1].strip()
            owner, _, member = entry.partition(".")
            rows.append(("M" if is_method else "F", owner, member, is_method))

    # The link checker reports names *after* remapping, while the remapper sees the original intermediary
    # names. A member the remapper already renamed has to be inverted as well, or the bridge never fires.
    inverse_class: dict[str, str] = {}
    member_inverse: dict[tuple[str, str], tuple[str, str, str]] = {}
    mapping_file = mapping_dir / "intermediary-1.21.11-to-srg-1.20.1.txt"
    if mapping_file.exists():
        for line in mapping_file.read_text(encoding="utf-8").splitlines():
            if line.startswith("C\t"):
                parts = line.split("\t")
                if len(parts) >= 3:
                    inverse_class[parts[2]] = parts[1]
                continue
            if not line or line[0] == "#":
                continue
            parts = line.split("\t")
            if len(parts) >= 7:
                # Keyed by owner, target name *and* target descriptor. Without the descriptor, overloads
                # collide: every overload of one obfuscated name shares the source name but not the SRG
                # one, so a lookup without it can return a completely different method.
                key = (parts[4], parts[5], parts[6])
                if key not in member_inverse:
                    member_inverse[key] = (parts[1], parts[2], parts[3])

    def de_remap(descriptor: str) -> str:
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
            internal = descriptor[index + 1:end]
            out.append("L" + inverse_class.get(internal, internal))
            out.append(";")
            index = end + 1
        return "".join(out)

    def source_key(owner: str, member: str, is_method: bool) -> str | None:
        if is_method:
            name = member.split("(")[0]
            descriptor = member[member.index("("):]
        else:
            name, descriptor = split_field(member)
        exact = member_inverse.get((owner, name, descriptor))
        if exact is None:
            # The repair stage can settle on a different overload, so fall back to the same name whose
            # signature has the same shape.
            wanted = re.sub(r"L[\w$/]+;", "L;", descriptor)
            for (candidate_owner, candidate_name, candidate_descriptor), value in member_inverse.items():
                if candidate_owner == owner and candidate_name == name \
                        and re.sub(r"L[\w$/]+;", "L;", candidate_descriptor) == wanted:
                    exact = value
                    break
        if exact:
            source_owner, source_name, source_descriptor = exact
            return f"{source_owner}.{source_name}{source_descriptor}"
        # No mapping for this member: it stayed in the intermediary namespace, so only the owner and the
        # descriptor classes need mapping back. Shim classes (net/minecraft/class_XXXX) have no class
        # mapping at all and arrive with the owner already in the right namespace.
        source_owner = inverse_class.get(owner, owner)
        return f"{source_owner}.{name}{de_remap(descriptor)}"

    mapping_dir.mkdir(parents=True, exist_ok=True)
    bridge_file = mapping_dir / "bridges.txt"

    # kind, key -> (bridge, arity) and bridge -> (kind, arity, doc)
    entries: dict[tuple[str, str], tuple[str, int]] = {}
    bridges: dict[str, tuple[str, int, str]] = {}
    if bridge_file.exists():
        for line in bridge_file.read_text(encoding="utf-8").splitlines():
            parts = line.split("\t")
            if len(parts) < 5:
                continue
            kind, owner, member, bridge, arity = parts[0], parts[1], parts[2], parts[3], int(parts[4])
            entries[(kind, f"{owner}.{member}")] = (bridge, arity)
            bridges.setdefault(bridge, (kind, arity, f"{owner}.{member}"))

    next_index = 1 + max((int(name[1:]) for name in bridges), default=0)

    def note(kind: str, owner: str, member: str, bridge: str, arity: int) -> None:
        entries[(kind, f"{owner}.{member}")] = (bridge, arity)

    fresh = 0
    for kind, owner, member, is_method in rows:
        key_member = member if is_method else "".join(split_field(member))
        source = source_key(owner, member, is_method)
        known = entries.get((kind, f"{owner}.{key_member}"))
        if known is None and source:
            known = entries.get((kind, source))
        if known is not None:
            note(kind, owner, key_member, known[0], known[1])
            if source:
                source_owner, _, rest = source.partition(".")
                note(kind, source_owner, rest, known[0], known[1])
            continue

        bridge = f"m{next_index:04d}"
        next_index += 1
        fresh += 1
        if is_method:
            descriptor = member[member.index("("):]
            arity = len(split_parameters(descriptor))
        else:
            arity = 1
        note(kind, owner, key_member, bridge, arity)
        if source:
            source_owner, _, rest = source.partition(".")
            note(kind, source_owner, rest, bridge, arity)
        bridges[bridge] = (kind, arity, f"{owner}.{member}")

    bridge_lines = [f"{kind}\t{key.partition('.')[0]}\t{key.partition('.')[2]}\t{bridge}\t{arity}"
                    for (kind, key), (bridge, arity) in sorted(entries.items(), key=lambda item: item[1][0])]
    # Name-only aliases: the redirect sees the descriptor before remapping, which does not match the
    # target-space key the report produced, so every bridge also gets a bare owner.name key.
    alias_lines = []
    for (kind, key), (bridge, arity) in sorted(entries.items(), key=lambda item: item[1][0]):
        owner, _, member = key.partition(".")
        simple = member.split("(")[0] if "(" in member else split_field(member)[0]
        alias_lines.append(f"K\t{owner}\t{simple}\t{bridge}\t{arity}")
    bridge_file.write_text("\n".join(bridge_lines + alias_lines) + "\n", encoding="utf-8")

    methods: list[str] = []
    for bridge, (kind, arity, doc) in sorted(bridges.items()):
        owner, _, member = doc.partition(".")
        if kind == "M":
            name = member.split("(")[0]
            descriptor = member[member.index("("):]
            returns = java_type(descriptor[descriptor.rindex(")") + 1:])
            instance_args = ", ".join(["Object self"] + [f"Object argument{position}"
                                                       for position in range(arity)])
            static_args = ", ".join(f"Object argument{position}" for position in range(arity))
            methods.append(
                f"    /** Voxy calls {doc}, which 1.20.1 does not have.\n"
                f"     *  Returns {returns}. */\n"
                f"    public static Object {bridge}({instance_args}) {{\n"
                f"        throw new UnsupportedOperationException(\"RoxyForge bridge {bridge}: {doc}\");\n"
                f"    }}\n"
                f"    public static Object {bridge}Static({static_args}) {{\n"
                f"        throw new UnsupportedOperationException(\"RoxyForge bridge {bridge} (static): "
                f"{doc}\");\n"
                f"    }}")
        else:
            name, descriptor = split_field(member)
            methods.append(
                f"    /** Voxy reads {doc}, which 1.20.1 does not have.\n"
                f"     *  Type: {java_type(descriptor)}. */\n"
                f"    public static Object {bridge}(Object self) {{\n"
                f"        throw new UnsupportedOperationException(\"RoxyForge bridge {bridge}: {doc}\");\n"
                f"    }}\n"
                f"    public static void {bridge}Set(Object self, Object value) {{\n"
                f"        throw new UnsupportedOperationException(\"RoxyForge bridge {bridge} (set): "
                f"{doc}\");\n"
                f"    }}\n"
                f"    public static Object {bridge}StaticGet() {{\n"
                f"        throw new UnsupportedOperationException(\"RoxyForge bridge {bridge} "
                f"(static get): {doc}\");\n"
                f"    }}\n"
                f"    public static void {bridge}StaticSet(Object value) {{\n"
                f"        throw new UnsupportedOperationException(\"RoxyForge bridge {bridge} "
                f"(static set): {doc}\");\n"
                f"    }}")

    java_dir = source_dir / "src" / "main" / "java" / "net" / "rasanovum" / "roxy" / "bridge"
    java_dir.mkdir(parents=True, exist_ok=True)
    (java_dir / "RoxyBridge.java").write_text(
        "package net.rasanovum.roxy.bridge;\n"
        "\n"
        "/**\n"
        " * Stands in for the Minecraft calls Voxy makes that Minecraft 1.20.1 cannot satisfy.\n"
        " *\n"
        " * Generated by tools/roxy-mappings/make_bridges.py from the unresolved references the link\n"
        " * checker reported, and wired up by RoxyForgeRemapper: a call to\n"
        " * {@code Owner.member(args)} is rewritten into {@code RoxyBridge.mNNNN(owner, args)}.\n"
        " *\n"
        " * Signatures are Object based because several of the types involved are package-private in\n"
        " * Minecraft and cannot be named from our package; the real types are in the javadoc and in\n"
        " * bridges.txt. Bodies throw on purpose: this milestone is \"Voxy links on 1.20.1\", and the game\n"
        " * run is what shows which of these calls Voxy actually needs at runtime.\n"
        " */\n"
        "public final class RoxyBridge {\n"
        "    private RoxyBridge() {\n"
        "    }\n"
        "\n" + "\n".join(methods) + "\n}\n", encoding="utf-8")

    print(f"report rows: {len(rows)}  bridges: {len(bridges)}  new this round: {fresh}")
    print(f"wrote {bridge_file} and {java_dir / 'RoxyBridge.java'}")


if __name__ == "__main__":
    main()