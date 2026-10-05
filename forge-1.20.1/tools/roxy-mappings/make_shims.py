"""Generate shim classes for the Minecraft 1.21 classes Voxy needs that 1.20.1 does not have.

The shims live in **our own package** and the generated mapping points Voxy's references at them. That
matters: injecting them into the Voxy copy instead would put package net.minecraft into two modules of
the same layer, and building that layer fails with a clean exit and nothing in the log.

Method and field names are kept exactly as Voxy calls them (method_1234), so no member mapping is
needed - only the owner changes - and signatures use the *target* descriptor, which is what the call site
looks like after remapping.

Outputs:
    src/main/java/net/rasanovum/roxy/shim/ShimNNNN.java
    shim-aliases.txt            net/minecraft/class_10868  net/rasanovum/roxy/shim/Shim10868

    make_shims.py <link report> <source dir> <mapping dir>
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
PACKAGE = "net.rasanovum.roxy.shim"


def java_type(descriptor: str, shims: dict[str, str] | None = None) -> str:
    dims = 0
    while descriptor.startswith("["):
        dims += 1
        descriptor = descriptor[1:]
    if descriptor in PRIMITIVES:
        base = {"V": "void", "Z": "boolean", "B": "byte", "C": "char", "S": "short", "I": "int",
                "J": "long", "F": "float", "D": "double"}[descriptor]
    else:
        internal = descriptor.lstrip("L").rstrip(";")
        # A sibling 1.21-only class is referenced by its intermediary name in the report; after remapping
        # it becomes the sibling's shim, so the signature has to say that too.
        base = (shims or {}).get(internal, internal).replace("/", ".")
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
    name, separator, descriptor = entry.partition(":")
    if separator:
        return name, descriptor
    match = NAME_AND_DESCRIPTOR.match(entry)
    return (match.group(1), match.group(2)) if match else (entry, "")


def safe(name: str) -> str:
    return f"`{name}`" if name in JAVA_KEYWORDS else name


def main() -> None:
    report = Path(sys.argv[1]).read_text(encoding="utf-8")
    source_dir = Path(sys.argv[2])
    mapping_dir = Path(sys.argv[3]) if len(sys.argv) > 3 else Path(__file__).resolve().parent / "roxy-mappings"

    sections = report.split("--- unresolved classes")[1].split("--- unresolved methods")[0]
    missing = re.findall(r"\s+(\d+)\s+(net/minecraft/class_\d+)", sections)
    methods_section = report.split("--- unresolved methods")[1]
    methods_text, fields_text = methods_section.split("--- unresolved fields")

    methods_by_owner: dict[str, set[str]] = {}
    for line in methods_text.splitlines():
        if line.strip():
            owner, _, member = line.split(None, 1)[1].strip().partition(".")
            methods_by_owner.setdefault(owner, set()).add(member)
    fields_by_owner: dict[str, set[str]] = {}
    for line in fields_text.splitlines():
        if line.strip():
            owner, _, member = line.split(None, 1)[1].strip().partition(".")
            fields_by_owner.setdefault(owner, set()).add(member)

    # Every missing class is a shim; siblings reference each other, so resolve them all up front.
    shim_names = {internal: f"{PACKAGE}.Shim{internal.rsplit('_', 1)[-1]}"
                  for _, internal in missing}

    java_dir = source_dir / "src" / "main" / "java" / Path(PACKAGE.replace(".", "/"))
    java_dir.mkdir(parents=True, exist_ok=True)
    for stale in java_dir.glob("Shim*.java"):
        stale.unlink()

    aliases = []
    written = []
    for _, internal in missing:
        digits = internal.rsplit("_", 1)[-1]
        class_name = f"Shim{digits}"
        lines = [
            f"package {PACKAGE};",
            "",
            "/**",
            f" * Stands in for {internal}, which exists in Minecraft 1.21 but not in 1.20.1.",
            " *",
            " * Generated by tools/roxy-mappings/make_shims.py from the unresolved references the link",
            " * checker found in Voxy; the mapping points Voxy's calls at this class. Members keep the names",
            " * Voxy uses, so only the owner is rewritten.",
            " *",
            " * Bodies throw on purpose - this milestone is \"Voxy links on 1.20.1\".",
            " */",
            f"public class {class_name} {{",
        ]
        for member in sorted(fields_by_owner.get(internal, ())):
            name, descriptor = split_field(member)
            lines.append(f"    public {java_type(descriptor, shim_names)} {safe(name)};")
        for member in sorted(methods_by_owner.get(internal, ())):
            name = member.split("(")[0]
            descriptor = "(" + member.split("(", 1)[1]
            types = [java_type(part, shim_names) for part in split_parameters(descriptor)]
            arguments = ", ".join(f"{type} argument{position}" for position, type in enumerate(types))
            returns = java_type(descriptor[descriptor.rindex(")") + 1:], shim_names)
            lines.append(f"    public {returns} {safe(name)}({arguments}) {{")
            lines.append(f"        throw new UnsupportedOperationException(\"RoxyForge shim "
                         f"{class_name}.{name}\");")
            lines.append("    }")
        lines.append("}")
        (java_dir / f"{class_name}.java").write_text("\n".join(lines) + "\n", encoding="utf-8")
        aliases.append(f"{internal} {PACKAGE.replace('.', '/')}/{class_name}")
        written.append((internal, class_name))

    mapping_dir.mkdir(parents=True, exist_ok=True)
    (mapping_dir / "shim-aliases.txt").write_text(
        "# Generated by make_shims.py: Minecraft 1.21 classes that 1.20.1 does not have, redirected to shims.\n"
        + "\n".join(aliases) + "\n", encoding="utf-8")

    for internal, class_name in written:
        print(f"{internal:32} -> {PACKAGE}.{class_name}")
    print(f"\n{len(written)} shims written to {java_dir}")
    print(f"wrote {mapping_dir / 'shim-aliases.txt'}")


if __name__ == "__main__":
    main()