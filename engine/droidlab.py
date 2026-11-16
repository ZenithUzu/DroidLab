#!/usr/bin/env python3
"""DroidLab's local ARM64 QEMU launcher for Termux."""
import argparse
import fcntl
import json
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys

LAB = Path.home() / ".droidlab"
VNC_HOST = "127.0.0.1:0"


def profile_name(value):
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,31}", value):
        raise argparse.ArgumentTypeError("Usa entre 1 y 32 letras minúsculas, números o guiones.")
    return value


def launch(profile, profile_id):
    firmware = LAB / "QEMU_EFI.fd"
    disk = profile / "disk.raw"
    iso = profile / "boot.iso"
    if not firmware.is_file():
        raise RuntimeError(f"Falta el firmware UEFI ARM64: {firmware}")
    if not disk.is_file() and not iso.is_file():
        raise RuntimeError(f"Agrega disk.raw o boot.iso a {profile}")
    qemu = shutil.which("qemu-system-aarch64")
    if qemu is None:
        raise RuntimeError("Instala qemu-system-aarch64-headless en Termux.")

    args = [
        qemu, "-name", f"DroidLab {profile_id}", "-machine", "virt",
        "-accel", "tcg,thread=multi", "-cpu", "max", "-smp", "4", "-m", "3072",
        "-bios", str(firmware), "-display", "none", "-vnc", VNC_HOST,
        "-device", "virtio-gpu-pci", "-device", "virtio-keyboard-pci",
        "-device", "virtio-tablet-pci", "-nic", "none", "-monitor", "none",
        "-qmp", "unix:qmp.sock,server=on,wait=off", "-serial", "file:serial.log",
    ]
    if disk.is_file():
        args += ["-drive", "file=disk.raw,if=virtio,format=raw"]
    if iso.is_file():
        args += [
            "-device", "virtio-scsi-pci,id=scsi",
            "-drive", "file=boot.iso,if=none,media=cdrom,readonly=on,format=raw,id=bootcd",
            "-device", "scsi-cd,drive=bootcd,bus=scsi.0,bootindex=1",
        ]

    with (LAB / "run.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("Ya hay una máquina en ejecución.") from None
        (profile / "qmp.sock").unlink(missing_ok=True)
        print("Pantalla VNC local: 127.0.0.1:5900", flush=True)
        print(f"Consola del invitado: {profile / 'serial.log'}", flush=True)
        try:
            with (profile / "qemu.log").open("w", encoding="utf-8") as log:
                result = subprocess.run(args, cwd=profile, stderr=log, check=False)
            if result.returncode:
                print((profile / "qemu.log").read_text(encoding="utf-8", errors="replace"),
                      file=sys.stderr, end="")
            return result.returncode
        finally:
            (profile / "qmp.sock").unlink(missing_ok=True)


def qmp_message(stream):
    line = stream.readline()
    if not line:
        raise RuntimeError("QEMU cerró el canal de control.")
    return json.loads(line)


def qmp_command(stream, command):
    stream.write(json.dumps({"execute": command}).encode() + b"\n")
    stream.flush()
    while True:
        reply = qmp_message(stream)
        if "return" in reply:
            return reply["return"]
        if "error" in reply:
            raise RuntimeError(reply["error"]["desc"])


def control(profile, action):
    socket_path = profile / "qmp.sock"
    if not socket_path.exists():
        print("Esta máquina no está en ejecución.")
        return 0
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as channel:
        channel.settimeout(3)
        try:
            channel.connect(str(socket_path))
        except (ConnectionRefusedError, FileNotFoundError):
            print("Esta máquina no está en ejecución.")
            return 0
        with channel.makefile("rwb") as stream:
            qmp_message(stream)
            qmp_command(stream, "qmp_capabilities")
            if action == "stop":
                qmp_command(stream, "system_powerdown")
                print("Se solicitó apagar el invitado. El sistema debe admitir la señal.")
            elif action == "force-stop":
                qmp_command(stream, "quit")
                print("QEMU se cerró.")
            else:
                print(f"Estado QEMU: {qmp_command(stream, 'query-status')['status']}")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("start", "stop", "force-stop", "status"))
    parser.add_argument("profile", type=profile_name)
    args = parser.parse_args()
    profile = LAB / "profiles" / args.profile
    try:
        if args.action == "start":
            return launch(profile, args.profile)
        return control(profile, args.action)
    except (OSError, RuntimeError) as error:
        print(f"DroidLab: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
