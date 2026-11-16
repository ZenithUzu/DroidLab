package com.zenithuzu.droidlab;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class VirtualBase {
    static final String LABEL = "LineageOS 23.0 · Android 16 ARM64";
    private static final String URL = "https://github.com/jqssun/android-lineage-qemu/releases/download/"
            + "v2025.10.14/UTM-VM-lineage-23.0-20251014-jqssun-virtio_arm64only.zip";
    private static final String SHA256 = "8ae5547fbb93f250187ed05f92a54c609837a11a0ff3103521f1fc55faac6920";
    private static final long DOWNLOAD_SIZE = 1004217012L;
    private static final String[] DISKS = {"vda.img", "vdb.qcow2", "efi_vars.fd"};
    private static final long BOOT_ENTRY_OFFSET = 30_000_647;
    private static final long ANIMATION_OPTION_OFFSET = 29_990_193;
    private static final String ANIMATION_OPTION = "if [ \"$android_nobootanim\" = \"1\" ]; then";
    private static final String UPSTREAM_ENTRY = "menuentry \"LineageOS 23.0"
            + "${kernel_version_display_mainmenu}\" --class android --id android {\n"
            + "\tset_android_default android\n\tboot_android\n}";
    private static final AtomicBoolean INSTALLING = new AtomicBoolean();

    interface Progress {
        void update(String stage, long bytes, long total);
    }

    private VirtualBase() {}

    static Path location(Context context) {
        return context.getFilesDir().toPath().resolve("bases").resolve("lineage23-20251014-arm64");
    }

    static boolean installed(Context context) {
        Path location = location(context);
        return Files.isRegularFile(location.resolve("ready"));
    }

    static void install(Context context, Progress progress) throws IOException {
        if (!INSTALLING.compareAndSet(false, true)) {
            throw new IOException("La base ya se está instalando.");
        }
        Path directory = location(context);
        Path archive = directory.resolve("base.zip");
        try {
            if (installed(context)) return;
            Files.createDirectories(directory);
            download(archive, progress);
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                Map<String, ZipEntry> files = new HashMap<>();
                for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                    String path = entry.getName();
                    for (String name : DISKS) {
                        if (!entry.isDirectory() && path.endsWith(".utm/Data/" + name)
                                && files.put(name, entry) != null) {
                            throw new IOException("La base contiene discos duplicados.");
                        }
                    }
                }
                for (String name : DISKS) {
                    ZipEntry entry = files.get(name);
                    if (entry == null || entry.getSize() <= 0 || entry.getSize() > (8L << 30)) {
                        throw new IOException("Falta un disco de la base o tiene un tamaño inválido: " + name);
                    }
                    try (InputStream input = zip.getInputStream(entry)) {
                        copy(input, directory.resolve(name), entry.getSize(), null,
                                "Extrayendo " + name, progress);
                    }
                }
            }
            Files.createFile(directory.resolve("ready"));
        } finally {
            try {
                Files.deleteIfExists(archive);
                if (!installed(context)) {
                    for (String name : DISKS) Files.deleteIfExists(directory.resolve(name));
                }
            } finally {
                INSTALLING.set(false);
            }
        }
    }

    private static void download(Path archive, Progress progress) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("No se puede verificar SHA-256.", error);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(URL).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub respondió con HTTP " + connection.getResponseCode());
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                copy(input, archive, DOWNLOAD_SIZE, digest, "Descargando base", progress);
            }
        } finally {
            connection.disconnect();
        }
        StringBuilder checksum = new StringBuilder();
        for (byte value : digest.digest()) checksum.append(String.format(Locale.ROOT, "%02x", value & 255));
        if (!SHA256.contentEquals(checksum)) throw new IOException("El SHA-256 de la base no coincide.");
    }

    private static void copy(InputStream input, Path target, long total, MessageDigest digest,
            String stage, Progress progress) throws IOException {
        progress.update(stage, 0, total);
        long written = 0;
        long last = System.nanoTime();
        byte[] buffer = new byte[512 * 1024];
        boolean sparse = target.getFileName().toString().startsWith("vda.img");
        try (RandomAccessFile output = new RandomAccessFile(target.toFile(), "rw")) {
            output.setLength(0);
            int count;
            while ((count = input.read(buffer)) != -1) {
                written += count;
                if (written > total) throw new IOException("El archivo supera su tamaño esperado.");
                if (sparse && isAllZero(buffer, count)) output.seek(written);
                else output.write(buffer, 0, count);
                if (digest != null) digest.update(buffer, 0, count);
                if (System.nanoTime() - last >= 250_000_000L) {
                    progress.update(stage, written, total);
                    last = System.nanoTime();
                }
            }
            output.setLength(written);
        }
        if (written != total) throw new IOException("El archivo quedó incompleto.");
        progress.update(stage, written, total);
    }

    private static boolean isAllZero(byte[] bytes, int size) {
        for (int index = 0; index < size; index++) {
            if (bytes[index] != 0) return false;
        }
        return true;
    }

    static void prepare(Context context, Path profile, boolean gsi, Progress progress) throws IOException {
        if (!installed(context)) throw new IOException("Instala una base Android primero.");
        Files.createDirectories(profile);
        for (String name : DISKS) {
            Path target = profile.resolve(name);
            if (Files.isRegularFile(target)) continue;
            Path source = location(context).resolve(name);
            Path staging = profile.resolve(name + ".tmp");
            try (InputStream input = Files.newInputStream(source)) {
                copy(input, staging, Files.size(source), null, "Preparando " + name, progress);
                if ("vda.img".equals(name)) configureBoot(staging, gsi);
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(staging);
            }
        }
    }
    private static void configureBoot(Path disk, boolean gsi) throws IOException {
        // Offset and bytes refer to the exact base archive verified by SHA-256.
        byte[] original = UPSTREAM_ENTRY.getBytes(StandardCharsets.US_ASCII);
        byte[] replacement = ("menuentry \"Android\" {\n"
                + "\tboot_android androidboot.graphics=swiftshader"
                + (gsi ? " androidboot.fstab_suffix=virtio.gsi.vdc" : "")
                + " androidboot.low_perf=1\n}").getBytes(StandardCharsets.US_ASCII);
        try (RandomAccessFile image = new RandomAccessFile(disk.toFile(), "rw")) {
            patch(image, BOOT_ENTRY_OFFSET, original, replacement);
            patch(image, ANIMATION_OPTION_OFFSET, ANIMATION_OPTION.getBytes(StandardCharsets.US_ASCII),
                    "if true; then".getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static void patch(RandomAccessFile image, long offset, byte[] expected, byte[] replacement)
            throws IOException {
        byte[] current = new byte[expected.length];
        image.seek(offset);
        image.readFully(current);
        if (!Arrays.equals(expected, current) || replacement.length > current.length) {
            throw new IOException("La entrada de arranque no coincide con la base virtual.");
        }
        Arrays.fill(current, (byte) ' ');
        System.arraycopy(replacement, 0, current, 0, replacement.length);
        image.seek(offset);
        image.write(current);
    }
}
