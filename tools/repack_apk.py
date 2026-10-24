#!/usr/bin/env python3
"""Put a freshly built classes.dex into the official APK, everything else untouched.

The Android side of this port is a few Java classes over the engine in
`lib/arm64-v8a/libtorchlight.so`. Only the classes change when the launcher
changes, so the release APK is the base: its manifest, its resources, its
assets and its engine library are taken as they are, `classes.dex` is swapped,
and the result is signed again (`apksigner`, in the workflow).

Taking the release as the base is also what keeps the build honest: the
workflow checks the base's SHA-256 against the one the release publishes, so
the only thing this script can change is the dex.

Rewriting the archive moves every entry after the first one whose compressed
size changes, which is why `resources.arsc` is aligned again here by hand: the
system refuses an APK whose resources are not uncompressed and 4-byte aligned
(targetSdk 30 and later). The padding is the extra field zipalign itself writes
(id 0xd935), so the archive stays a well-formed zip.

    python3 tools/repack_apk.py base.apk dex/classes.dex unsigned.apk
    python3 tools/repack_apk.py base.apk dex/classes.dex unsigned.apk --lib libtorchlight.so
"""
import struct
import sys
import zipfile

# Entries that must stay uncompressed and 4-byte aligned.
ALIGNED = ("resources.arsc",)
# The base APK's own signature files: apksigner writes its own.
SIGNATURE_DIR = "META-INF/"
ALIGNMENT_EXTRA = 0xD935


def padding(name, offset, needed):
    """The extra field that puts this entry's data on a 4-byte boundary."""
    if name not in ALIGNED:
        return b""
    base = (offset + 30 + len(name.encode())) % 4
    if base == 0:
        return b""
    # An extra field is at least 4 bytes: id, size, then the bytes.
    room = (4 - base) % 4
    return struct.pack("<HH", ALIGNMENT_EXTRA, room) + b"\0" * room


ENGINE = "lib/arm64-v8a/libtorchlight.so"


def repack(base, dex, out, lib=None):
    with open(dex, "rb") as handle:
        new_dex = handle.read()
    src = zipfile.ZipFile(base)
    if "classes.dex" not in src.namelist():
        raise SystemExit(f"{base}: no classes.dex")
    replaced = {"classes.dex": new_dex}
    if lib:
        # The engine, when this build has one of its own: the release's is
        # what an ordinary launcher change keeps.
        with open(lib, "rb") as handle:
            replaced[ENGINE] = handle.read()
        if ENGINE not in src.namelist():
            raise SystemExit(f"{base}: no {ENGINE} to replace")
    with zipfile.ZipFile(out, "w") as dst:
        for info in src.infolist():
            if info.filename.startswith(SIGNATURE_DIR):
                continue
            data = replaced.get(info.filename) or src.read(info.filename)
            entry = zipfile.ZipInfo(info.filename, info.date_time)
            entry.compress_type = info.compress_type
            entry.external_attr = info.external_attr
            entry.create_system = info.create_system
            # Where this entry's local header is about to go, which is what
            # the alignment is measured from.
            entry.extra = padding(info.filename, dst.fp.tell(), 4)
            dst.writestr(entry, data)
    return check(out)


def check(path):
    """The alignment the system checks, and the count of what went in."""
    z = zipfile.ZipFile(path)
    bad = z.testzip()
    if bad is not None:
        raise SystemExit(f"{path}: corrupt entry {bad}")
    for info in z.infolist():
        if info.filename in ALIGNED:
            if info.compress_type != 0:
                raise SystemExit(f"{info.filename}: compressed, it must be stored")
            offset = info.header_offset + 30 + len(info.filename.encode()) + len(info.extra)
            if offset % 4:
                raise SystemExit(f"{info.filename}: data at {offset}, not a multiple of 4")
    return len(z.namelist()), sum(i.file_size for i in z.infolist())


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--lib=")]
    lib = next((a.split("=", 1)[1] for a in sys.argv[1:] if a.startswith("--lib=")), None)
    if len(args) != 3:
        raise SystemExit(__doc__)
    count, size = repack(args[0], args[1], args[2], lib)
    print(f"{args[2]}: {count} entries, {size} bytes uncompressed" + (f", engine {lib}" if lib else ""))
