package com.zenithuzu.droidlab;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

final class NativeEngine {
    private static final String GUEST_MARKER = "DROIDLAB_ARM64_OK";
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(20);

    private NativeEngine() {}

    static String probe(Context context) throws IOException, InterruptedException {
        File libraryDirectory = new File(context.getApplicationInfo().nativeLibraryDir);
        File engine = new File(libraryDirectory, "libdroidlab_qemu.so");
        if (!engine.isFile()) {
            throw new IOException("Este APK no incluye el motor ARM64 experimental.");
        }

        Path workspace = Files.createTempDirectory(context.getCacheDir().toPath(), "qemu-probe-");
        File transcript = workspace.resolve("qmp.log").toFile();
        File serial = workspace.resolve("serial.log").toFile();
        Path program = workspace.resolve("probe.bin");
        Process vm = null;
        try {
            Files.write(program, guestProgram());
            ProcessBuilder launcher = new ProcessBuilder(engine.getAbsolutePath(),
                    "-machine", "virt", "-cpu", "max", "-accel", "tcg",
                    "-m", "128", "-smp", "1", "-nodefaults", "-display", "none",
                    "-nic", "none", "-serial", "file:serial.log",
                    "-device", "loader,file=probe.bin,addr=0x40080000,cpu-num=0,force-raw=on",
                    "-S", "-qmp", "stdio");
            launcher.directory(workspace.toFile());
            launcher.environment().put("LD_LIBRARY_PATH", libraryDirectory.getAbsolutePath());
            launcher.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
            launcher.redirectErrorStream(true);
            launcher.redirectOutput(transcript);
            vm = launcher.start();
            try (OutputStream control = vm.getOutputStream()) {
                command(control, "qmp_capabilities");
                command(control, "cont");
                long deadline = System.nanoTime() + TIMEOUT_NANOS;
                while (vm.isAlive() && System.nanoTime() < deadline && !hasGuestMarker(serial)) {
                    Thread.sleep(100);
                }
                if (!hasGuestMarker(serial)) {
                    throw new IOException("La máquina ARM64 no devolvió la señal de prueba.");
                }
                command(control, "quit");
                if (!vm.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IOException("El invitado ejecutó la prueba, pero QEMU no cerró.");
                }
            } catch (IOException error) {
                String details = logTail(transcript);
                if (!details.isEmpty()) {
                    throw new IOException(error.getMessage() + "\n" + details, error);
                }
                throw error;
            }
            if (vm.exitValue() != 0 || !hasCommandReply(transcript, "cont")) {
                throw new IOException("Falló el control de QEMU.\n" + logTail(transcript));
            }
            return "QEMU ejecutó el programa ARM64 de prueba y recibió su señal por el puerto serial.";
        } finally {
            if (vm != null && vm.isAlive()) {
                vm.destroyForcibly();
            }
            Files.deleteIfExists(program);
            Files.deleteIfExists(serial.toPath());
            Files.deleteIfExists(transcript.toPath());
            Files.deleteIfExists(workspace);
        }
    }

    private static void command(OutputStream stream, String name) throws IOException {
        String json = "{\"execute\":\"" + name + "\",\"id\":\"" + name + "\"}\n";
        stream.write(json.getBytes(StandardCharsets.UTF_8));
        stream.flush();
    }

    private static byte[] guestProgram() {
        byte[] message = GUEST_MARKER.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer instructions = ByteBuffer.allocate(4 + message.length * 8 + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        // QEMU virt maps the PL011 transmit register at 0x09000000.
        instructions.putInt(0xd2a12000); // movz x0, #0x900, lsl #16
        for (byte letter : message) {
            instructions.putInt(0x52800001 | (letter << 5)); // movz w1, #letter
            instructions.putInt(0xb9000001); // str w1, [x0]
        }
        instructions.putInt(0x14000000); // b .
        return instructions.array();
    }

    private static boolean hasGuestMarker(File serial) throws IOException {
        return serial.isFile() && logTail(serial).contains(GUEST_MARKER);
    }

    private static boolean hasCommandReply(File transcript, String id) throws IOException {
        try (BufferedReader lines = new BufferedReader(new FileReader(transcript))) {
            String line;
            while ((line = lines.readLine()) != null) {
                try {
                    JSONObject response = new JSONObject(line);
                    if (id.equals(response.optString("id")) && response.optJSONObject("return") != null) {
                        return true;
                    }
                } catch (JSONException ignored) {
                    // Diagnostic lines can share the transcript with QMP JSON.
                }
            }
        }
        return false;
    }

    private static String logTail(File transcript) throws IOException {
        if (!transcript.isFile()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(transcript))) {
            char[] buffer = new char[1024];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                result.append(buffer, 0, count);
                if (result.length() > 3000) {
                    result.delete(0, result.length() - 3000);
                }
            }
        }
        return result.toString().trim();
    }
}
