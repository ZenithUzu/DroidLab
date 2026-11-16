#!/usr/bin/env python3
"""Bundle the locked Android ARM64 QEMU and its ELF dependency closure."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
from tempfile import TemporaryDirectory
from urllib.parse import quote
from urllib.request import urlopen

HERE = Path(__file__).resolve().parent
MIRROR = "https://packages.termux.dev/apt/termux-main"
PREFIX = "data/data/com.termux/files/usr"
SYSTEM = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
          "libaaudio.so", "libOpenSLES.so", "libEGL.so", "libGLESv2.so", "libneuralnetworks.so"}


def fetch(record, downloads):
    name, (location, expected) = record
    destination = downloads / (name + ".deb")
    with urlopen(f"{MIRROR}/{quote(location, safe='/')}", timeout=60) as response:
        with destination.open("wb") as output:
            shutil.copyfileobj(response, output)
    digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    if digest != expected:
        raise ValueError(f"SHA-256 mismatch: {name}")
    return name, destination


def install_firmware(info, workspace):
    url = ("https://deb.debian.org/debian/pool/main/e/edk2/"
           "qemu-efi-aarch64_2025.02-8+deb13u1_all.deb")
    deb = workspace / "debian-edk2.deb"
    with urlopen(url, timeout=60) as response, deb.open("wb") as output:
        shutil.copyfileobj(response, output)
    if hashlib.sha256(deb.read_bytes()).hexdigest() != \
            "a00b2411a79c8aeafd95a7c868ac3cd1aab592f1af9fff965f02d81a625276ed":
        raise ValueError("SHA-256 mismatch: Debian EDK II firmware")
    firmware = workspace / "firmware"
    subprocess.run(["dpkg-deb", "-x", deb, firmware], check=True)
    shutil.copyfile(firmware / "usr/share/AAVMF/AAVMF_CODE.no-secboot.fd",
                    info / "edk2-aarch64-code.fd")
    shutil.copyfile(firmware / "usr/share/doc/qemu-efi-aarch64/copyright",
                    info / "EDK2-COPYRIGHT")
    return "https://packages.debian.org/source/trixie/edk2"


def needed(path):
    dynamic = subprocess.check_output(["readelf", "-d", path], text=True)
    return re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", dynamic)


def libraries(prefixes):
    indexed = {}
    for package, prefix in prefixes.items():
        for file in sorted((prefix / "lib").rglob("lib*.so*")):
            if not file.is_file() or not re.fullmatch(r"lib[^/]+\.so(?:\.[\w.]+)?", file.name):
                continue
            with file.open("rb") as candidate:
                if candidate.read(4) != b"\x7fELF":
                    continue
            indexed.setdefault(file.name, (package, file.resolve()))
    return indexed


def collect(executable, indexed):
    selected = {}
    queue = needed(executable)
    while queue:
        library = queue.pop()
        if library in selected or library in SYSTEM:
            continue
        if library not in indexed:
            raise ValueError(f"Unresolved QEMU dependency: {library}")
        selected[library] = indexed[library]
        queue.extend(needed(selected[library][1]))
    return selected


def package(input_file, output, renames, soname=None):
    source_needed = needed(input_file)
    shutil.copyfile(input_file, output)
    output.chmod(0o755)
    args = ["patchelf", "--page-size", "16384", "--set-rpath", "$ORIGIN"]
    if soname is not None:
        args += ["--set-soname", soname]
    for dependency in source_needed:
        if dependency in renames:
            args += ["--replace-needed", dependency, renames[dependency]]
    subprocess.run([*args, output], check=True)
    expected = {renames.get(name, name) for name in source_needed}
    if set(needed(output)) != expected:
        raise ValueError(f"Unexpected dependencies in {output.name}")
    headers = subprocess.check_output(["readelf", "-lW", output], text=True)
    for line in headers.splitlines():
        fields = line.split()
        if fields and fields[0] == "LOAD":
            offset, address, alignment = (int(fields[index], 16) for index in (1, 2, -1))
            if alignment < 16384 or (offset - address) % 16384:
                raise ValueError(f"{output.name} does not support 16 KiB pages")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads((HERE / "termux-arm64.lock.json").read_text())
    with TemporaryDirectory() as temp:
        workspace = Path(temp)
        prefixes = {}
        with ThreadPoolExecutor(max_workers=10) as workers:
            for name, deb in workers.map(lambda item: fetch(item, workspace), lock["packages"].items()):
                root = workspace / name
                subprocess.run(["dpkg-deb", "-x", deb, root], check=True)
                prefixes[name] = root / PREFIX
        binary = prefixes["qemu-system-aarch64-headless"] / "bin" / "qemu-system-aarch64"
        header = subprocess.check_output(["readelf", "-h", binary], text=True)
        interpreter = subprocess.check_output(["readelf", "-lW", binary], text=True)
        if "AArch64" not in header or "/system/bin/linker64" not in interpreter:
            raise ValueError("Expected a native Android ARM64 executable")
        selected = collect(binary, libraries(prefixes))
        renames = {name: f"libdlq_{name.removeprefix('lib').replace('.', '_')}.so" for name in selected}
        if len(set(renames.values())) != len(renames):
            raise ValueError("Collision in Android library filenames")

        native = args.out / "jniLibs" / "arm64-v8a"
        info = args.out / "assets" / "qemu"
        native.mkdir(parents=True, exist_ok=True)
        info.mkdir(parents=True, exist_ok=True)
        for name, (_, file) in selected.items():
            package(file, native / renames[name], renames, renames[name])
        package(binary, native / "libdroidlab_qemu.so", renames)
        used = {"qemu-system-aarch64-headless", *(pkg for pkg, _ in selected.values())}
        sources = {
            "termux_sources": f"https://github.com/termux/termux-packages/tree/{lock['termux_sources']}",
            "qemu_upstream_source": "https://download.qemu.org/qemu-11.1.3.tar.xz",
            "firmware_source": install_firmware(info, workspace),
            "packages": {key: lock["packages"][key] for key in sorted(used)},
        }
        (info / "sources.json").write_text(json.dumps(sources, indent=2) + "\n")
        shutil.copyfile(HERE / "QEMU-COPYING", info / "QEMU-COPYING")
        for name in sorted(used):
            docs = prefixes[name] / "share" / "doc" / name
            if docs.is_dir():
                for file in docs.rglob("*"):
                    if file.is_file() and file.resolve().is_relative_to(prefixes[name]):
                        output = info / "licenses" / name / file.relative_to(docs)
                        output.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copyfile(file, output)
        print(f"Packaged QEMU and {len(selected)} Android libraries")


if __name__ == "__main__":
    main()
