package com.zenithuzu.droidlab;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class GuestMachine implements AutoCloseable {
    private final Process process;
    private final Path directory;
    private final Path socket;
    private volatile LocalSocket display;
    private boolean closed;

    private GuestMachine(Process process, Path directory, Path socket) {
        this.process = process;
        this.directory = directory;
        this.socket = socket;
    }

    static GuestMachine start(Context context, Path profile, Path system) throws IOException {
        Path firmware = firmware(context);
        File libraries = new File(context.getApplicationInfo().nativeLibraryDir);
        File engine = new File(libraries, "libdroidlab_qemu.so");
        if (!engine.isFile()) throw new IOException("Este APK no incluye el motor ARM64.");
        Path socket = context.getCacheDir().toPath().resolve("screen.sock");
        Files.deleteIfExists(socket);
        List<String> args = new ArrayList<>(Arrays.asList(engine.getAbsolutePath(),
                "-machine", "virt", "-cpu", "max,pauth-impdef=on", "-accel", "tcg,thread=multi,tb-size=64",
                "-m", "2048", "-smp", "4", "-no-reboot", "-nodefaults", "-display", "none", "-nic", "none",
                "-drive", "if=pflash,unit=0,format=raw,readonly=on,file=" + firmware,
                "-drive", "if=pflash,unit=1,format=qcow2,file=efi_vars.fd",
                "-drive", "if=none,id=vda,format=raw,file=vda.img",
                "-device", "virtio-blk-pci,drive=vda,bootindex=0",
                "-drive", "if=none,id=vdb,format=qcow2,file=vdb.qcow2",
                "-device", "virtio-blk-pci,drive=vdb,bootindex=1",
                "-device", "virtio-gpu-pci", "-device", "usb-ehci,id=usb-bus",
                "-device", "usb-kbd,bus=usb-bus.0", "-device", "usb-tablet,bus=usb-bus.0",
                "-device", "virtio-serial", "-device", "virtio-rng-pci",
                "-audiodev", "none,id=audio0", "-device", "intel-hda",
                "-device", "hda-duplex,audiodev=audio0",
                "-chardev", "null,id=guest-console", "-device", "virtconsole,chardev=guest-console",
                "-chardev", "file,id=guest-android,path=android.log",
                "-device", "virtconsole,chardev=guest-android",
                "-serial", "file:guest.log", "-qmp", "stdio", "-vnc", "unix:" + socket));
        if (system != null) {
            args.addAll(Arrays.asList("-drive", "if=none,id=vdc,format=raw,readonly=on,file=" + system,
                    "-device", "virtio-blk-pci,drive=vdc"));
        }
        ProcessBuilder builder = new ProcessBuilder(args).directory(profile.toFile()).redirectErrorStream(true);
        builder.environment().put("LD_LIBRARY_PATH", libraries.getAbsolutePath());
        builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
        builder.redirectOutput(profile.resolve("qemu.log").toFile());
        Process process = builder.start();
        GuestMachine guest = new GuestMachine(process, profile, socket);
        try {
            guest.control("qmp_capabilities");
            return guest;
        } catch (IOException error) {
            guest.close();
            throw error;
        }
    }

    LocalSocket connectDisplay() throws IOException, InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        while (process.isAlive() && SystemClock.elapsedRealtime() < deadline) {
            LocalSocket candidate = new LocalSocket();
            try {
                candidate.connect(new LocalSocketAddress(socket.toString(), LocalSocketAddress.Namespace.FILESYSTEM));
                display = candidate;
                return candidate;
            } catch (IOException error) {
                candidate.close();
                Thread.sleep(100);
            }
        }
        throw new IOException("QEMU no abrió la pantalla virtual.\n" + diagnostic());
    }

    String diagnostic() throws IOException {
        return tail(directory.resolve("qemu.log"), 3000);
    }

    String guestLog() throws IOException {
        String android = tail(directory.resolve("android.log"), 4500);
        return android.isEmpty() ? tail(directory.resolve("guest.log"), 6500)
                : android + "\n" + tail(directory.resolve("guest.log"), 2200);
    }

    private static String tail(Path log, int limit) throws IOException {
        if (!Files.isRegularFile(log)) return "";
        try (java.io.RandomAccessFile reader = new java.io.RandomAccessFile(log.toFile(), "r")) {
            int count = (int) Math.min(reader.length(), limit);
            byte[] bytes = new byte[count];
            reader.seek(reader.length() - count);
            reader.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private void control(String command) throws IOException {
        process.getOutputStream().write(("{\"execute\":\"" + command + "\"}\n")
                .getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            if (display != null) display.close();
        } catch (IOException ignored) {
            // Continue with QEMU shutdown.
        }
        try {
            if (process.isAlive()) {
                control("quit");
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            }
        } catch (IOException | InterruptedException error) {
            process.destroyForcibly();
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        try {
            Files.deleteIfExists(socket);
        } catch (IOException ignored) {
            // Removed before the next launch.
        }
    }

    private static Path firmware(Context context) throws IOException {
        Path destination = context.getFilesDir().toPath().resolve("edk2-aarch64-code.fd");
        if (Files.isRegularFile(destination)) return destination;
        Path temporary = destination.resolveSibling("edk2-aarch64-code.tmp");
        try (InputStream input = context.getAssets().open("qemu/edk2-aarch64-code.fd")) {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return destination;
    }
}
