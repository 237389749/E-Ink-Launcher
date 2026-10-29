#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Rebuild ota/images/recovery.img into a recovery that starts adbd by default.

Why (see RECOVERY-FASTBOOT.md):
  Poke6 ships a stock AOSP recovery (system/bin/recovery + librecovery_ui +
  fastbootd + res/images are all there), but:
    * init.rc keeps adbd disabled; adb only comes up after the user picks
      "Apply update from ADB" (sideload) in the menu;
    * the device has a power key only (no volume keys), and ABL's recovery
      entry only watches the power key, so the stock menu cannot be navigated.
  This script appends two blocks to the ramdisk's init.rc:
    on boot                          -> service.adb.root=1, sys.usb.config=adb
    on property:sys.usb.config=none  -> force adb again (recovery turns USB off)
  Result: `adb reboot recovery` gives a root adb shell, so you can dd
  backup/restore partitions, and `setprop sys.usb.config fastboot` starts
  fastbootd for dynamic partitions.

Only the ramdisk changes; kernel/dtbo/dtb are byte-identical. The header v2
`id` field is recomputed with the mkbootimg algorithm.

Usage:
  python tools/mk-recovery-adb.py                  # -> out/recovery_adb.img
  python tools/mk-recovery-adb.py --check          # verify only, write nothing
  python tools/mk-recovery-adb.py --pad 100663296  # zero-pad to whole partition
"""
import argparse
import gzip
import hashlib
import os
import struct

PAGE = 4096
INIT_RC = "system/etc/init/hw/init.rc"

PATCH = """

# ---- added by tools/mk-recovery-adb.py : headless adb ----
on boot
    setprop service.adb.root 1
    setprop sys.usb.config adb

# recovery turns USB off during boot; re-arm adb so it survives
on property:sys.usb.config=none
    setprop sys.usb.config adb

# last resort: recovery service is up, make sure adb is on
on property:init.svc.recovery=running
    setprop sys.usb.config adb
""".encode("utf-8")


# ---------------------------------------------------------------- boot image
def parse_boot(d):
    if d[:8] != b"ANDROID!":
        raise SystemExit("not an ANDROID boot image")
    h = {
        "kernel_size": struct.unpack("<I", d[0x08:0x0C])[0],
        "kernel_addr": struct.unpack("<I", d[0x0C:0x10])[0],
        "ramdisk_size": struct.unpack("<I", d[0x10:0x14])[0],
        "ramdisk_addr": struct.unpack("<I", d[0x14:0x18])[0],
        "second_size": struct.unpack("<I", d[0x18:0x1C])[0],
        "second_addr": struct.unpack("<I", d[0x1C:0x20])[0],
        "tags_addr": struct.unpack("<I", d[0x20:0x24])[0],
        "page_size": struct.unpack("<I", d[0x24:0x28])[0],
        "header_version": struct.unpack("<I", d[0x28:0x2C])[0],
        "os_version": struct.unpack("<I", d[0x2C:0x30])[0],
        "cmdline": d[0x40:0x240].split(b"\0")[0],
    }
    if h["header_version"] >= 1:
        h["recovery_dtbo_size"] = struct.unpack("<I", d[0x660:0x664])[0]
        h["recovery_dtbo_offset"] = struct.unpack("<Q", d[0x664:0x66C])[0]
        h["header_size"] = struct.unpack("<I", d[0x66C:0x670])[0]
    if h["header_version"] >= 2:
        h["dtb_size"] = struct.unpack("<I", d[0x670:0x674])[0]
        h["dtb_addr"] = struct.unpack("<Q", d[0x674:0x67C])[0]
    return h


def boot_id(kernel, ramdisk, second, dtbo, dtb):
    """mkbootimg id: sha1(each blob followed by its u32 LE length)"""
    m = hashlib.sha1()
    for blob in (kernel, ramdisk, second, dtbo, dtb):
        m.update(blob)
        m.update(struct.pack("<I", len(blob)))
    return m.digest()


def align(n, p=PAGE):
    return (n + p - 1) // p * p


def split_boot(d, h):
    ps = h["page_size"]
    off = ps
    kernel = d[off:off + h["kernel_size"]]
    off += align(h["kernel_size"], ps)
    ramdisk = d[off:off + h["ramdisk_size"]]
    off += align(h["ramdisk_size"], ps)
    second = d[off:off + h["second_size"]]
    off += align(h["second_size"], ps)
    dtbo = b""
    if h["header_version"] >= 1:
        dtbo = d[off:off + h["recovery_dtbo_size"]]
        off += align(h["recovery_dtbo_size"], ps)
    dtb = b""
    if h["header_version"] >= 2:
        dtb = d[off:off + h["dtb_size"]]
    return kernel, ramdisk, second, dtbo, dtb


def build_boot(header_bytes, h, kernel, ramdisk, second, dtbo, dtb):
    ps = h["page_size"]
    hdr = bytearray(header_bytes)
    struct.pack_into("<I", hdr, 0x10, len(ramdisk))
    hdr[0x240:0x260] = boot_id(kernel, ramdisk, second, dtbo, dtb) + b"\0" * 12
    if h["header_version"] >= 1:
        struct.pack_into("<I", hdr, 0x660, len(dtbo))
        # recovery_dtbo_offset is an ABSOLUTE offset in the image. It must be
        # recomputed whenever kernel/ramdisk/second sizes change, otherwise ABL's
        # RecoveryDtboOffset check reads garbage (this is what broke the first build).
        dtbo_off = ps + align(len(kernel), ps) + align(len(ramdisk), ps) + align(len(second), ps)
        struct.pack_into("<Q", hdr, 0x664, dtbo_off)
    if h["header_version"] >= 2:
        struct.pack_into("<I", hdr, 0x670, len(dtb))
    out = bytes(hdr)
    for blob in (kernel, ramdisk, second, dtbo, dtb):
        if blob:
            out += blob + b"\0" * (align(len(blob), ps) - len(blob))
    return out


# ---------------------------------------------------------------- cpio
def cpio_parse(buf):
    ents, i = [], 0
    while True:
        if buf[i:i + 6] != b"070701":
            raise SystemExit("bad cpio magic at %#x" % i)
        def f(o):
            return int(buf[i + o:i + o + 8], 16)
        e = dict(
            ino=f(6), mode=f(14), uid=f(22), gid=f(30), nlink=f(38),
            mtime=f(46), size=f(54), devmajor=f(62), devminor=f(70),
            rdevmajor=f(78), rdevminor=f(86), namesize=f(94),
        )
        name = buf[i + 110:i + 110 + e["namesize"] - 1]
        hdr_len = 110 + e["namesize"]
        hdr_len += (-hdr_len) % 4
        data = buf[i + hdr_len:i + hdr_len + e["size"]]
        i += hdr_len + e["size"]
        i += (-i) % 4
        if name == b"TRAILER!!!":
            break
        ents.append((e, name, data))
    return ents


def _hex(v):
    return b"%08x" % v


FIELDS = ("ino", "mode", "uid", "gid", "nlink", "mtime", "size",
          "devmajor", "devminor", "rdevmajor", "rdevminor")


def _entry(e, namez, data):
    hdr = b"070701" + b"".join(_hex(e[k]) for k in FIELDS) + _hex(len(namez)) + _hex(0)
    out = bytearray(hdr + namez)
    out += b"\0" * ((-len(out)) % 4)
    out += data
    out += b"\0" * ((-len(out)) % 4)
    return bytes(out)


def cpio_build(ents):
    out = bytearray()
    for e, name, data in ents:
        out += _entry(e, name + b"\0", data)
    trailer = dict(ino=0, mode=0, uid=0, gid=0, nlink=1, mtime=0, size=0,
                   devmajor=0, devminor=0, rdevmajor=0, rdevminor=0)
    out += _entry(trailer, b"TRAILER!!!\0", b"")
    return bytes(out)


def patch_init_rc(cpio):
    ents = cpio_parse(cpio)
    hit, new = 0, []
    for e, name, data in ents:
        if name.decode() == INIT_RC:
            hit += 1
            if b"mk-recovery-adb.py" in data:
                print("  init.rc already patched, skip")
            else:
                data = data + PATCH
                e = dict(e, size=len(data))
                print("  init.rc patched (+%d B)" % len(PATCH))
        new.append((e, name, data))
    if hit != 1:
        raise SystemExit("init.rc matched %d times (expect 1)" % hit)
    return cpio_build(new)


def patch_insecure_adb(cpio, pubkey=None):
    """ro.adb.secure=1 -> 0 (recovery has no UI to confirm the RSA prompt),
    optionally add /adb_keys so this host is authorized even if secure stays 1."""
    ents = cpio_parse(cpio)
    changed = 0
    for i, (e, name, data) in enumerate(ents):
        if name.decode() != "prop.default":
            continue
        n = data.count(b"ro.adb.secure=1")
        if n:
            data = data.replace(b"ro.adb.secure=1", b"ro.adb.secure=0")
            ents[i] = (dict(e, size=len(data)), name, data)
            changed += n
    print("  prop.default: ro.adb.secure 1->0 x%d" % changed)
    if pubkey:
        key = open(pubkey, "rb").read().strip() + b"\n"
        if not any(n == b"adb_keys" for _, n, _ in ents):
            e = dict(ino=9999, mode=0o100644, uid=0, gid=0, nlink=1, mtime=0,
                     size=len(key), devmajor=0, devminor=0, rdevmajor=0, rdevminor=0)
            ents.insert(len(ents) - 1, (e, b"adb_keys", key))
            print("  added /adb_keys (%d B)" % len(key))
    return cpio_build(ents)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="ota/images/recovery.img")
    ap.add_argument("--out", default="out/recovery_adb.img")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--pad", type=int, default=0, help="pad to N bytes (e.g. 100663296)")
    ap.add_argument("--secure", type=int, choices=(0, 1), default=0,
                    help="0 = adb needs no authorization (default for rescue)")
    ap.add_argument("--adb-key", default="",
                    help="path to adbkey.pub to also install as /adb_keys")
    a = ap.parse_args()

    raw = open(a.src, "rb").read()
    h = parse_boot(raw)
    print("== source %s (%d B)" % (a.src, len(raw)))
    for k in ("header_version", "page_size", "kernel_size", "ramdisk_size",
              "second_size", "recovery_dtbo_size", "dtb_size"):
        if k in h:
            print("   %-18s %s" % (k, h[k]))

    kernel, ramdisk, second, dtbo, dtb = split_boot(raw, h)
    assert len(kernel) == h["kernel_size"], (len(kernel), h["kernel_size"])
    assert len(ramdisk) == h["ramdisk_size"], (len(ramdisk), h["ramdisk_size"])

    rebuilt0 = build_boot(raw[:PAGE], h, kernel, ramdisk, second, dtbo, dtb)
    n = min(len(rebuilt0), len(raw))
    print("   rebuild-identical: %s (%d B)" % (rebuilt0[:n] == raw[:n], len(rebuilt0)))
    if rebuilt0[:n] != raw[:n]:
        print("   src id ", raw[0x240:0x260].hex())
        print("   new id ", rebuilt0[0x240:0x260].hex())

    if a.check:
        return

    cpio = gzip.decompress(ramdisk)
    print("   ramdisk decompressed %d B" % len(cpio))
    cpio2 = patch_init_rc(cpio)
    if a.secure == 0:
        cpio2 = patch_insecure_adb(cpio2, a.adb_key or None)
    ramdisk2 = gzip.compress(cpio2, 9, mtime=0)
    print("   new ramdisk %d B" % len(ramdisk2))

    img = build_boot(raw[:PAGE], h, kernel, ramdisk2, second, dtbo, dtb)
    if a.pad:
        if len(img) > a.pad:
            raise SystemExit("image %d > pad %d" % (len(img), a.pad))
        img = img + b"\0" * (a.pad - len(img))

    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    open(a.out, "wb").write(img)
    print("== wrote %s (%d B) md5=%s" % (a.out, len(img), hashlib.md5(img).hexdigest()))

    h2 = parse_boot(img)
    k2, r2, s2, d2, t2 = split_boot(img, h2)
    assert k2 == kernel, "kernel changed"
    assert d2 == dtbo and t2 == dtb, "dtbo/dtb changed"
    assert gzip.decompress(r2) == cpio2, "ramdisk mismatch"
    assert b"mk-recovery-adb.py" in cpio2, "patch missing"
    print("== self-check OK: kernel/dtbo/dtb unchanged, ramdisk contains patch")


if __name__ == "__main__":
    main()
