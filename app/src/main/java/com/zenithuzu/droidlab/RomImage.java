package com.zenithuzu.droidlab;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class RomImage {
    private static final long IMAGE_LIMIT = 32L << 30;
    private static final int XZ_MEMORY_LIMIT_KIB = 96 * 1024;

    private RomImage() {}

    static String importImage(InputStream source, String name, Path directory) throws IOException {
        String lower = name.toLowerCase(Locale.ROOT);
        Path target = directory.resolve("system.img");
        if (lower.endsWith(".zip")) {
            Path archive = directory.resolve("archive.zip");
            try {
                copy(source, archive, null, -1);
                unpackZip(archive, target);
            } finally {
                Files.deleteIfExists(archive);
            }
        } else if (lower.endsWith(".img.xz")) {
            unpackXz(source, target);
        } else if (lower.endsWith(".img") || lower.endsWith(".gsi")) {
            copy(source, target, null, -1);
        } else {
            throw new IOException("Usa una GSI .img, .img.xz o un ZIP que contenga system.img.");
        }
        return identify(target);
    }

    private static void unpackZip(Path archive, Path target) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry system = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (path.startsWith("/") || path.startsWith("../") || path.contains("/../")
                        || path.endsWith("/..") || path.equals("..")) {
                    throw new IOException("El ZIP contiene una ruta de archivo inválida.");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (name.equals("payload.bin") || name.equals("update-binary")
                        || name.equals("updater-script") || name.equals("super.img")) {
                    throw new IOException("Este ZIP parece un paquete de actualización para un dispositivo.");
                }
                if (!name.startsWith("system_ext.") && !name.startsWith("system_dlkm.")
                        && name.matches("system(?:[._-][a-z0-9._-]+)?\\.img(?:\\.xz)?")) {
                    if (system != null) {
                        throw new IOException("El ZIP contiene varias imágenes de sistema. Elige una por separado.");
                    }
                    system = entry;
                }
            }
            if (system == null) {
                throw new IOException("El ZIP no contiene una imagen de sistema reconocible, como system.img.");
            }
            try (InputStream input = zip.getInputStream(system)) {
                if (system.getName().toLowerCase(Locale.ROOT).endsWith(".xz")) {
                    unpackXz(input, target);
                } else {
                    copy(input, target, new CRC32(), system.getCrc());
                }
            }
        }
    }

    private static void unpackXz(InputStream source, Path target) throws IOException {
        try (XZInputStream xz = new XZInputStream(new BufferedInputStream(source),
                XZ_MEMORY_LIMIT_KIB)) {
            copy(xz, target, null, -1);
        }
    }

    private static void copy(InputStream input, Path target, CRC32 crc, long expectedCrc)
            throws IOException {
        try (OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[256 * 1024];
            long written = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                written += count;
                if (written > IMAGE_LIMIT) {
                    throw new IOException("El archivo supera el límite de 32 GiB.");
                }
                if (crc != null) {
                    crc.update(buffer, 0, count);
                }
                output.write(buffer, 0, count);
            }
            if (crc != null && crc.getValue() != expectedCrc) {
                throw new IOException("La imagen comprimida está dañada.");
            }
        }
    }

    static String identify(Path image) throws IOException {
        byte[] header = new byte[2048];
        try (InputStream input = Files.newInputStream(image)) {
            int position = 0;
            while (position < header.length) {
                int count = input.read(header, position, header.length - position);
                if (count < 0) {
                    throw new IOException("La imagen está incompleta.");
                }
                position += count;
            }
        }
        ByteBuffer bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.getInt(0) == 0xed26ff3a && bytes.getShort(4) == 1
                && Short.toUnsignedInt(bytes.getShort(8)) >= 28
                && Short.toUnsignedInt(bytes.getShort(10)) >= 12 && bytes.getInt(12) > 0
                && bytes.getInt(16) != 0 && bytes.getInt(20) != 0) {
            return "Android sparse";
        }
        if (bytes.getShort(1080) == (short) 0xef53) {
            return "ext4";
        }
        if (bytes.getInt(1024) == 0xe0f5e1e2) {
            return "EROFS";
        }
        throw new IOException("El archivo no parece una imagen de sistema ext4, EROFS o Android sparse.");
    }
}
