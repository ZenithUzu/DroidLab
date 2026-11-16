package com.zenithuzu.droidlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

public class GuestDiskTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void expandsRawFillAndHoleChunks() throws IOException {
        ByteBuffer sparse = header(3, 3);
        chunk(sparse, 0xcac1, 1, 4).put(new byte[] {1, 2, 3, 4});
        chunk(sparse, 0xcac2, 1, 4).put(new byte[] {9, 8, 7, 6});
        chunk(sparse, 0xcac3, 1, 0);
        Path directory = temporary.newFolder().toPath();
        Path source = write(directory, sparse);
        Path raw = directory.resolve("raw");
        GuestDisk.expand(source, raw);
        assertArrayEquals(new byte[] {1, 2, 3, 4, 9, 8, 7, 6, 0, 0, 0, 0}, Files.readAllBytes(raw));
    }

    @Test
    public void refusesChunksOutsideTheDeclaredDisk() throws IOException {
        ByteBuffer sparse = header(1, 1);
        chunk(sparse, 0xcac3, 2, 0);
        Path directory = temporary.newFolder().toPath();
        Path source = write(directory, sparse);
        assertThrows(IOException.class, () -> GuestDisk.expand(source, directory.resolve("raw")));
    }

    private static Path write(Path directory, ByteBuffer sparse) throws IOException {
        sparse.flip();
        byte[] content = new byte[sparse.remaining()];
        sparse.get(content);
        return Files.write(directory.resolve("system.img"), content);
    }

    private static ByteBuffer header(int blocks, int chunks) {
        return ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN).putInt(0xed26ff3a)
                .putShort((short) 1).putShort((short) 0)
                .putShort((short) 28).putShort((short) 12)
                .putInt(4).putInt(blocks).putInt(chunks).putInt(0);
    }

    private static ByteBuffer chunk(ByteBuffer buffer, int kind, int blocks, int payload) {
        return buffer.putShort((short) kind).putShort((short) 0).putInt(blocks).putInt(payload + 12);
    }
}
