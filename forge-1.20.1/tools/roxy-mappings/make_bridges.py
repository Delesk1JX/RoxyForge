"""Turn the link checker's unresolved members into bridge methods, and tell the remapper about them.

A bridge is a static method we own that stands in for a Minecraft call Voxy makes but 1.20.1 cannot
satisfy. The remapper rewrites `Owner.member(args)` into `RoxyBridge.member_NNN(Owner self, args)`, so
Voxy links and we implement the body against the 1.20.1 API later.

Outputs:
    roxy-mappings/bridges.txt                       M|F <owner> <name><descriptor> <bridge name>
    src/main/java/net/rasanovum/roxy/bridge/RoxyBridge.java

    make_bridges.py <link report> <mapping dir> <source dir>
"""
import re
import sys
from pathlib import Path

JAVA_KEYWORDS = {"abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
                 "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
                 "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
                 "interface", "long", "native", "new", "package", "private", "protected", "public",
                 "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
                 "throw", "throws", "transient", "try", "void", "volatile", "while"}

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


def safe(name: str) -> str:
    return f"`{name}`" if name in JAVA_KEYWORDS else name


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
            if "(" in member:
                rows.append(("M", owner, member, True))
            else:
                name, descriptor = split_field(member)
                rows.append(("F", owner, name + descriptor, False))

    # The link checker reports names *after* remapping, but the remapper sees the original intermediary
    # names. Members that had no mapping keep their intermediary name, so the source key only needs the
    # owner and the descriptor classes mapped back.
    inverse_class: dict[str, str] = {}
    mapping_file = mapping_dir / "intermediary-1.21.11-to-srg-1.20.1.txt"
    if mapping_file.exists():
        for line in mapping_file.read_text(encoding="utf-8").splitlines():
            if line.startswith("C\t"):
                parts = line.split("\t")
                if len(parts) >= 3:
                    inverse_class[parts[2]] = parts[1]

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
        # Shim classes (net/minecraft/class_XXXX) have no class mapping, so they arrive with the owner
        # already in the intermediary namespace and only the descriptor needs mapping back.
        source_owner = inverse_class.get(owner, owner)
        if is_method:
            name = member.split("(")[0]
            descriptor = de_remap(member[member.index("("):])
            return f"{source_owner}.{name}{descriptor}"
        name, descriptor = split_field(member)
        return f"{source_owner}.{name}{de_remap(descriptor)}"

    mapping_dir.mkdir(parents=True, exist_ok=True)
    bridge_lines = []
    methods: list[str] = []
    counter = 0
    for kind, owner, member, is_method in rows:
        counter += 1
        bridge = f"m{counter:04d}"
        # The bridge list is keyed the way the bytecode spells the member: name and descriptor glued
        # together, which is what the redirect builds its lookup key from.
        if is_method:
            key_member = member
        else:
            key_member = "".join(split_field(member))
        bridge_lines.append(f"{kind}\t{owner}\t{key_member}\t{bridge}")
        source = source_key(owner, member, is_method)
        if source:
            name, _, rest = source.partition(".")
            bridge_lines.append(f"{kind}\t{name}\t{rest}\t{bridge}")

        owner_type = owner.replace("/", ".")
        if is_method:
            name = member.split("(")[0]
            descriptor = member[member.index("("):]
            count = len(split_parameters(descriptor))
            # Object-based signatures on purpose: several of the types involved are package-private in
            # Minecraft (ClientChunkCache$Storage and friends) and cannot be named from our package. The
            # real types stay in the javadoc and in bridges.txt for whoever implements the body.
            arguments = ["Object self"] + [f"Object argument{position}" for position in range(count)]
            methods.append(
                f"    /** Voxy calls {owner}.{name}{descriptor}, which 1.20.1 does not have.\n"
                f"     *  Owner type: {owner_type}, returns "
                f"{java_type(descriptor[descriptor.rindex(')') + 1:])}. */\n"
                f"    public static Object {bridge}({', '.join(arguments)}) {{\n"
                f"        throw new UnsupportedOperationException(\"RoxyForge bridge {bridge}: "
                f"{owner}.{name}\");\n"
                f"    }}\n"
                f"    public static Object {bridge}Static({', '.join(arguments[1:]) or ''}) {{\n"
                f"        throw new UnsupportedOperationException(\"RoxyForge bridge {bridge} (static): "
                f"{owner}.{name}\");\n"
                f"    }}\n"
                f"    // TODO: implement {bridge} and {bridge}Static against the 1.20.1 API")
        else:
            name, descriptor = split_field(member)
            field_type = java_type(descriptor)
            methods.append(
                f"    /** Voxy reads {owner}.{name} ({descriptor}), which 1.20.1 does not have.\n"
                f"     *  Type: {field_type}. */\n"
                f"    public static Object {bridge}(Object self) {{\n"
                f"        throw new UnsupportedOperationException(\"RoxyForge bridge {bridge}: "
                f"{owner}.{name}\");\n"
                f"    }}\n"
                f"    public static void {bridge}Set(Object self, Object value) {{\n"
                f"        throw new UnsupportedOperationException(\"RoxyForge bridge {bridge} (set): "
                f"{owner}.{name}\");\n"
                f"    }}\n"
                f"    public static Object {bridge}StaticGet() {{\n"
                f"        throw new UnsupportedOperationException(\"RoxyForge bridge {bridge} "
                f"(static get): {owner}.{name}\");\n"
                f"    }}\n"
                f"    public static void {bridge}StaticSet(Object value) {{\n"
                f"        throw new UnsupportedOperationException(\"RoxyForge bridge {bridge} "
                f"(static set): {owner}.{name}\");\n"
                f"    }}\n"
                f"    // TODO: implement the {bridge} accessors against the 1.20.1 API")

    (mapping_dir / "bridges.txt").write_text("\n".join(bridge_lines) + "\n", encoding="utf-8")

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
        " * Every body throws on purpose. This milestone is \"Voxy links on 1.20.1\"; implementing the\n"
        " * bodies against the 1.20.1 API is the next one, and the game run is what shows which of these\n"
        " * calls Voxy actually needs at runtime.\n"
        " */\n"
        "public final class RoxyBridge {\n"
        "    private RoxyBridge() {\n"
        "    }\n"
        "\n" + "\n".join(methods) + "\n}\n", encoding="utf-8")

    methods_count = sum(1 for row in rows if row[3])
    print(f"{len(rows)} bridges ({methods_count} methods, {len(rows) - methods_count} fields)")
    print(f"wrote {mapping_dir / 'bridges.txt'} and {java_dir / 'RoxyBridge.java'}")


if __name__ == "__main__":
    main()