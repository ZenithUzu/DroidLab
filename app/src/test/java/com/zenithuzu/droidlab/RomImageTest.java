package com.zenithuzu.droidlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RomImageTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void importsEachImageIntoASeparateProfile() throws IOException {
        byte[] ext4 = fileSystem(1080, 0x0000ef53);
        byte[] erofs = fileSystem(1024, 0xe0f5e1e2);
        Path first = temporary.newFolder("first").toPath();
        Path second = temporary.newFolder("second").toPath();
        assertEquals("ext4", importInto(ext4, "Miku UI.img", first));
        assertEquals("EROFS", importInto(xz(erofs), "Other.img.xz", second));
        assertArrayEquals(ext4, Files.readAllBytes(first.resolve("system.img")));
        assertArrayEquals(erofs, Files.readAllBytes(second.resolve("system.img")));
    }

    @Test
    public void extractsOnlyTheSystemImageFromAZip() throws IOException {
        byte[] system = fileSystem(1080, 0x0000ef53);
        Path profile = temporary.newFolder().toPath();
        byte[] archive = zip(Map.of("rom/system_gapps.img.xz", xz(system),
                "notes/readme.txt", new byte[] {1}, "system_ext.img", new byte[] {3}));
        assertEquals("ext4", importInto(archive, "Miku.zip", profile));
        assertArrayEquals(system, Files.readAllBytes(profile.resolve("system.img")));
        assertFalse(Files.exists(profile.resolve("archive.zip")));
        assertFalse(Files.exists(profile.resolve("notes")));
    }

    @Test
    public void rejectsZipWithParentTraversal() throws IOException {
        Path profile = temporary.newFolder().toPath();
        byte[] system = fileSystem(1080, 0x0000ef53);
        assertThrows(IOException.class, () -> importInto(
                zip(Map.of("../system.img", system)), "rom.zip", profile));
        assertFalse(Files.exists(profile.resolve("system.img")));
        assertFalse(Files.exists(profile.getParent().resolve("system.img")));
    }

    @Test
    public void rejectsOtaEvenWhenItContainsASystemImage() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("system.img", fileSystem(1080, 0x0000ef53));
        files.put("payload.bin", new byte[] {1});
        Path profile = temporary.newFolder().toPath();
        IOException error = assertThrows(IOException.class,
                () -> importInto(zip(files), "device.zip", profile));
        assertTrue(error.getMessage().contains("actualización"));
        assertFalse(Files.exists(profile.resolve("system.img")));
        assertFalse(Files.exists(profile.resolve("archive.zip")));
    }

    @Test
    public void rejectsAmbiguousInvalidAndAuxiliaryImages() throws IOException {
        Path profile = temporary.newFolder().toPath();
        byte[] archive = zip(Map.of("system.img", new byte[] {1}, "system_gapps.img", new byte[] {2}));
        assertTrue(assertThrows(IOException.class,
                () -> importInto(archive, "rom.zip", profile)).getMessage().contains("varias"));
        assertThrows(IOException.class, () -> importInto(
                zip(Map.of("system_ext.img", fileSystem(1080, 0x0000ef53))), "rom.zip", profile));
        assertThrows(IOException.class, () -> importInto(new byte[4096], "rom.img", profile));
    }

    @Test
    public void recognizesAndroidSparseHeaders() throws IOException {
        byte[] header = new byte[4096];
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0xed26ff3a).putShort((short) 1).putShort((short) 0)
                .putShort((short) 28).putShort((short) 12).putInt(4096).putInt(3).putInt(1);
        assertEquals("Android sparse", importInto(header, "system.img", temporary.newFolder().toPath()));
    }

    @Test
    public void reportsZipCopyExtractionAndVerificationWithSeparateTotals() throws IOException {
        byte[] system = fileSystem(1080, 0x0000ef53);
        byte[] source = zip(Map.of("system.img", system));
        List<String> events = new ArrayList<>();
        RomImage.importImage(new ByteArrayInputStream(source), "rom.zip", source.length,
                temporary.newFolder().toPath(),
                (stage, bytes, total) -> events.add(stage + ":" + bytes + "/" + total));
        assertEquals(List.of(
                "COPY_ZIP:0/" + source.length,
                "COPY_ZIP:" + source.length + "/" + source.length,
                "INSPECT_ZIP:0/-1",
                "EXTRACT_ZIP:0/" + system.length,
                "EXTRACT_ZIP:" + system.length + "/" + system.length,
                "VERIFY:0/-1"), new ArrayList<>(new java.util.LinkedHashSet<>(events)));
    }

    @Test
    public void reportsExpandedXzBytesWithoutInventingATotal() throws IOException {
        byte[] system = fileSystem(1080, 0x0000ef53);
        byte[] source = xz(system);
        List<String> events = new ArrayList<>();
        RomImage.importImage(new ByteArrayInputStream(source), "rom.img.xz", source.length,
                temporary.newFolder().toPath(),
                (stage, bytes, total) -> events.add(stage + ":" + bytes + "/" + total));
        assertEquals(List.of("EXTRACT_XZ:0/-1", "EXTRACT_XZ:" + system.length + "/-1",
                "VERIFY:0/-1"), new ArrayList<>(new java.util.LinkedHashSet<>(events)));
    }

    private static String importInto(byte[] source, String name, Path profile) throws IOException {
        return RomImage.importImage(new ByteArrayInputStream(source), name, source.length, profile,
                (stage, bytes, total) -> {});
    }

    private static byte[] fileSystem(int offset, int magic) {
        byte[] content = new byte[4096];
        ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, magic);
        return content;
    }

    private static byte[] xz(byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XZOutputStream archive = new XZOutputStream(bytes, new LZMA2Options(0))) {
            archive.write(data);
        }
        return bytes.toByteArray();
    }

    private static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                archive.putNextEntry(new ZipEntry(file.getKey()));
                archive.write(file.getValue());
                archive.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
