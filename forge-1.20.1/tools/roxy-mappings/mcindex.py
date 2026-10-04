"""Minimal class file reader: what a jar declares, without loading anything.

Used to validate the generated mapping against the real Minecraft 1.20.1 jar: a mapping entry only
matters if the target name actually exists there.
"""
from __future__ import annotations

import struct
from pathlib import Path
import zipfile

ACC_STATIC = 0x0008
ACC_INTERFACE = 0x0200


class ClassFacts:
    __slots__ = ("name", "super_name", "interfaces", "methods", "fields")

    def __init__(self, name: str, super_name: str | None, interfaces: list[str]):
        self.name = name
        self.super_name = super_name
        self.interfaces = interfaces
        self.methods: set[str] = set()
        self.fields: set[str] = set()


def read_class(data: bytes) -> ClassFacts | None:
    if data[:4] != b"\xca\xfe\xba\xbe":
        return None
    count = struct.unpack(">H", data[8:10])[0]
    offset = 10
    utf8: list[str | None] = [None] * count
    class_names: dict[int, str] = {}
    pending_classes: list[tuple[int, int]] = []
    index = 1
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack(">H", data[offset:offset + 2])[0]
            offset += 2
            utf8[index] = data[offset:offset + length].decode("utf-8", "replace")
            offset += length
        elif tag in (3, 4):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            index += 1
        elif tag == 7:
            pending_classes.append((index, struct.unpack(">H", data[offset:offset + 2])[0]))
            offset += 2
        elif tag in (8, 16, 19, 20):
            offset += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            offset += 4
        elif tag == 15:
            offset += 3
        else:
            return None
        index += 1

    for slot, name_index in pending_classes:
        name = utf8[name_index]
        if name:
            class_names[slot] = name

    def class_name(index_or_zero: int) -> str | None:
        return class_names.get(index_or_zero) if index_or_zero else None

    offset += 2  # access flags
    this_class = struct.unpack(">H", data[offset:offset + 2])[0]
    offset += 2
    super_class = struct.unpack(">H", data[offset:offset + 2])[0]
    offset += 2
    interface_count = struct.unpack(">H", data[offset:offset + 2])[0]
    offset += 2
    interface_refs = []
    for _ in range(interface_count):
        interface_refs.append(struct.unpack(">H", data[offset:offset + 2])[0])
        offset += 2

    facts = ClassFacts(class_name(this_class) or "", class_name(super_class),
                       [class_name(ref) or "" for ref in interface_refs])

    for is_method in (False, True):
        member_count = struct.unpack(">H", data[offset:offset + 2])[0]
        offset += 2
        for _ in range(member_count):
            offset += 2  # access flags
            name_index = struct.unpack(">H", data[offset:offset + 2])[0]
            descriptor_index = struct.unpack(">H", data[offset + 2:offset + 4])[0]
            offset += 4
            attribute_count = struct.unpack(">H", data[offset:offset + 2])[0]
            offset += 2
            for _ in range(attribute_count):
                offset += 2
                length = struct.unpack(">I", data[offset:offset + 4])[0]
                offset += 4 + length
            entry = (utf8[name_index] or "") + (utf8[descriptor_index] or "")
            (facts.methods if is_method else facts.fields).add(entry)
    return facts


class JarIndex:
    """Everything a jar declares: classes, their members and their super chain."""

    def __init__(self) -> None:
        self.classes: dict[str, ClassFacts] = {}

    @classmethod
    def read(cls, jar: Path) -> "JarIndex":
        index = cls()
        with zipfile.ZipFile(jar) as zf:
            for entry in zf.namelist():
                if not entry.endswith(".class"):
                    continue
                facts = read_class(zf.read(entry))
                if facts is not None and facts.name:
                    index.classes[facts.name] = facts
        return index

    def has_class(self, internal_name: str) -> bool:
        return internal_name in self.classes

    def _walk(self, owner: str, kind: str):
        cursor = owner
        for _ in range(32):
            facts = self.classes.get(cursor)
            if facts is None:
                return
            yield facts.methods if kind == "m" else facts.fields
            for itf in facts.interfaces:
                yield from self._walk(itf, kind)
            cursor = facts.super_name or ""

    def has_method(self, owner: str, name: str, descriptor: str) -> bool:
        needle = name + descriptor
        for members in self._walk(owner, "m"):
            if needle in members:
                return True
        return False

    def has_field(self, owner: str, name: str, descriptor: str) -> bool:
        needle = name + descriptor
        for members in self._walk(owner, "f"):
            if needle in members:
                return True
        return False