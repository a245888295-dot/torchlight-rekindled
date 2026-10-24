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

    python3 tools/repack_apk.py base.apk dex/classes.dex unsigned.apk
"""
import sys
import zipfile

# Entries that must stay uncompressed and 4-byte aligned: the system refuses an
# APK whose resources.arsc is not (targetSdk 30 and later).
ALIGNED = ("resources.arsc",)
# The signature files of the base APK: apksigner writes its own.
SIGNATURE_DIR = "META-INF/"


def repack(base, dex, out):
    with open(dex, "rb") as handle:
        new_dex = handle.read()
    src = zipfile.ZipFile(base)
    if "classes.dex" not in src.namelist():
        raise SystemExit(f"{base}: no classes.dex")
    with zipfile.ZipFile(out, "w") as dst:
        for info in src.infolist():
            if info.filename.startswith(SIGNATURE_DIR):
                continue
            data = new_dex if info.filename == "classes.dex" else src.read(info.filename)
            entry = zipfile.ZipInfo(info.filename, info.date_time)
            entry.compress_type = info.compress_type
            entry.external_attr = info.external_attr
            entry.create_system = info.create_system
            dst.writestr(entry, data)
    return check(out)


def check(path):
    """The alignment the system checks, and the count of what went in."""
    z = zipfile.ZipFile(path)
    for info in z.infolist():
        if info.filename in ALIGNED:
            if info.compress_type != 0:
                raise SystemExit(f"{info.filename}: compressed, it must be stored")
            offset = info.header_offset + 30 + len(info.filename.encode()) + len(info.extra)
            if offset % 4:
                raise SystemExit(f"{info.filename}: data at {offset}, not a multiple of 4")
    return len(z.namelist()), sum(i.file_size for i in z.infolist())


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    count, size = repack(*sys.argv[1:])
    print(f"{sys.argv[3]}: {count} entries, {size} bytes uncompressed")
