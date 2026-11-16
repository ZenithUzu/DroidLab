package com.zenithuzu.droidlab;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class GuestDisk {
    private static final long LIMIT = 32L << 30;
    private GuestDisk() {}

    static Path prepare(Path system, Path profile) throws IOException {
        if (!"Android sparse".equals(RomImage.identify(system))) return system;
        Path raw = profile.resolve("system-raw.img");
        if (Files.isRegularFile(raw)) return raw;
        Path temporary = profile.resolve("system-raw.tmp");
        try {
            expand(system, temporary);
            Files.move(temporary, raw, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return raw;
    }

    static void expand(Path system, Path raw) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(system)));
             RandomAccessFile output = new RandomAccessFile(raw.toFile(), "rw")) {
            if (input.readInt() != 0x3aff26ed || shortLe(input) != 1) {
                throw new IOException("Cabecera sparse no admitida.");
            }
            shortLe(input);
            int fileHeader = shortLe(input), chunkHeader = shortLe(input);
            long block = intLe(input), blocks = intLe(input), chunks = intLe(input);
            intLe(input);
            if (fileHeader < 28 || chunkHeader < 12 || block == 0 || block % 4 != 0
                    || blocks == 0 || blocks > LIMIT / block || chunks == 0
                    || chunks > Files.size(system) / chunkHeader) {
                throw new IOException("Dimensiones sparse inválidas.");
            }
            skip(input, fileHeader - 28);
            output.setLength(0);
            byte[] buffer = new byte[256 * 1024];
            long done = 0;
            for (long index = 0; index < chunks; index++) {
                int kind = shortLe(input);
                shortLe(input);
                long count = intLe(input), payload = intLe(input) - chunkHeader;
                if (count > blocks - done || payload < 0) throw new IOException("Bloque sparse inválido.");
                skip(input, chunkHeader - 12);
                long bytes = count * block;
                if (kind == 0xcac1 && payload == bytes) {
                    transfer(input, output, buffer, bytes, false);
                } else if (kind == 0xcac2 && payload == 4) {
                    byte[] pattern = new byte[4];
                    input.readFully(pattern);
                    for (int i = 0; i < buffer.length; i += 4) {
                        System.arraycopy(pattern, 0, buffer, i, 4);
                    }
                    transfer(input, output, buffer, bytes, true);
                } else if (kind == 0xcac3 && payload == 0) {
                    output.seek(output.getFilePointer() + bytes);
                } else if (kind == 0xcac4 && payload == 4 && count == 0) {
                    intLe(input);
                } else {
                    throw new IOException("Tipo o tamaño de bloque sparse inválido.");
                }
                done += count;
            }
            if (done != blocks || input.read() != -1) throw new IOException("Imagen sparse incompleta.");
            output.setLength(blocks * block);
        }
    }

    private static void transfer(DataInputStream input, RandomAccessFile output, byte[] buffer,
            long size, boolean fill) throws IOException {
        while (size > 0) {
            int count = (int) Math.min(size, buffer.length);
            if (!fill) input.readFully(buffer, 0, count);
            output.write(buffer, 0, count);
            size -= count;
        }
    }

    private static int shortLe(DataInputStream input) throws IOException {
        return Short.toUnsignedInt(Short.reverseBytes(input.readShort()));
    }

    private static long intLe(DataInputStream input) throws IOException {
        return Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
    }

    private static void skip(DataInputStream input, int count) throws IOException {
        while (count > 0) {
            int skipped = input.skipBytes(count);
            if (skipped == 0) throw new EOFException("Cabecera sparse incompleta.");
            count -= skipped;
        }
    }
}
