package com.zenithuzu.droidlab;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class RomLibrary {
    static final class Rom {
        final Path directory;
        final String title;
        final String source;
        final String format;
        final long size;

        Rom(Path directory, String title, String source, String format, long size) {
            this.directory = directory;
            this.title = title;
            this.source = source;
            this.format = format;
            this.size = size;
        }
    }

    interface Observer {
        void onLibraryChanged(List<Rom> roms, boolean working, String status);
        void onImportProgress(ImportProgress progress);
    }

    static final class ImportProgress {
        final String file;
        final int position;
        final int count;
        final RomImage.Stage stage;
        final long bytes;
        final long total;

        ImportProgress(String file, int position, int count, RomImage.Stage stage, long bytes, long total) {
            this.file = file;
            this.position = position;
            this.count = count;
            this.stage = stage;
            this.bytes = bytes;
            this.total = total;
        }
    }

    private static final class Source {
        final String name;
        final long size;

        Source(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    private interface Action {
        String run() throws IOException;
    }

    private static RomLibrary instance;
    private final Path root;
    private final ContentResolver resolver;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Observer> observers = new ArrayList<>();
    private List<Rom> roms = Collections.emptyList();
    private boolean working;
    private String status = "";
    private ImportProgress importProgress;

    static RomLibrary get(Context context) {
        if (instance == null) {
            instance = new RomLibrary(context.getApplicationContext());
        }
        return instance;
    }

    private RomLibrary(Context context) {
        root = context.getFilesDir().toPath().resolve("roms");
        resolver = context.getContentResolver();
        run("Abriendo biblioteca…", () -> {
            cleanup();
            return "";
        });
    }

    void observe(Observer observer) {
        observers.add(observer);
        observer.onLibraryChanged(roms, working, status);
        observer.onImportProgress(importProgress);
    }

    void unobserve(Observer observer) {
        observers.remove(observer);
    }

    void importRoms(List<Uri> sources) {
        run(sources.size() == 1 ? "Importando imagen…" : "Importando imágenes…", () -> {
            int imported = 0;
            List<String> errors = new ArrayList<>();
            for (int index = 0; index < sources.size(); index++) {
                Uri uri = sources.get(index);
                Source source = sourceInfo(uri);
                int position = index + 1;
                try {
                    add(uri, source, (stage, bytes, total) -> reportImportProgress(
                            new ImportProgress(source.name, position, sources.size(), stage, bytes, total)));
                    imported++;
                } catch (IOException | SecurityException error) {
                    errors.add(source.name + " · " + message(error));
                }
            }
            String summary = imported == 1 ? "1 ROM importada" : imported + " ROMs importadas";
            return errors.isEmpty() ? summary : summary + "\n" + String.join("\n", errors);
        });
    }

    void rename(Rom rom, String title) {
        run("Guardando nombre…", () -> {
            writeMetadata(rom.directory, title, rom.source, rom.format);
            return "Nombre actualizado";
        });
    }

    void delete(Rom rom) {
        run("Eliminando ROM…", () -> {
            Path trash = root.resolve(".trash-" + rom.directory.getFileName());
            Files.move(rom.directory, trash, StandardCopyOption.ATOMIC_MOVE);
            removeTree(trash);
            return "ROM eliminada";
        });
    }

    private void add(Uri uri, Source source, RomImage.Progress progress) throws IOException {
        String id = UUID.randomUUID().toString();
        Path pending = root.resolve(".import-" + id);
        Files.createDirectory(pending);
        try {
            String format;
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) {
                    throw new IOException("No se pudo abrir el archivo.");
                }
                format = RomImage.importImage(input, source.name, source.size, pending, progress);
            }
            progress.update(RomImage.Stage.SAVE, 0, -1);
            Files.createDirectory(pending.resolve("data"));
            writeMetadata(pending, defaultTitle(source.name), source.name, format);
            Files.move(pending, root.resolve(id), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            removeTree(pending);
        }
    }

    private List<Rom> readLibrary() throws IOException {
        List<Rom> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(root)) {
            for (Path directory : children.collect(Collectors.toList())) {
                if (directory.getFileName().toString().startsWith(".")) {
                    continue;
                }
                try {
                    AtomicFile file = new AtomicFile(directory.resolve("rom.json").toFile());
                    JSONObject metadata = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
                    entries.add(new Rom(directory, metadata.getString("title"),
                            metadata.getString("source"), metadata.getString("format"),
                            Files.size(directory.resolve("system.img"))));
                } catch (IOException | JSONException error) {
                    Log.w("DroidLab", "Perfil ilegible: " + directory.getFileName(), error);
                }
            }
        }
        entries.sort(Comparator.comparing((Rom rom) -> rom.title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(rom -> rom.directory.getFileName().toString()));
        return entries;
    }

    private void writeMetadata(Path directory, String title, String source, String format)
            throws IOException {
        JSONObject metadata = new JSONObject();
        try {
            metadata.put("title", title);
            metadata.put("source", source);
            metadata.put("format", format);
        } catch (JSONException error) {
            throw new IOException("No se pudieron guardar los datos de la ROM.", error);
        }
        AtomicFile file = new AtomicFile(directory.resolve("rom.json").toFile());
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(metadata.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (IOException error) {
            file.failWrite(stream);
            throw error;
        }
    }

    private void cleanup() throws IOException {
        Files.createDirectories(root);
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.collect(Collectors.toList())) {
                String name = child.getFileName().toString();
                if (name.startsWith(".import-") || name.startsWith(".trash-")) {
                    removeTree(child);
                }
            }
        }
    }

    private static void removeTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Source sourceInfo(Uri uri) {
        try (Cursor cursor = resolver.query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                String name = nameColumn >= 0 ? cursor.getString(nameColumn) : null;
                long size = sizeColumn >= 0 && !cursor.isNull(sizeColumn) ? cursor.getLong(sizeColumn) : -1;
                if (name != null && !name.trim().isEmpty()) {
                    return new Source(name, size);
                }
            }
        } catch (RuntimeException error) {
            Log.w("DroidLab", "No se pudo leer el archivo seleccionado.", error);
        }
        return new Source("Imagen sin nombre", -1);
    }

    private void reportImportProgress(ImportProgress progress) {
        main.post(() -> {
            importProgress = progress;
            for (Observer observer : new ArrayList<>(observers)) {
                observer.onImportProgress(progress);
            }
        });
    }

    private static String defaultTitle(String name) {
        String title = name.replaceFirst("(?i)\\.(?:img(?:\\.xz)?|gsi|zip)$", "").trim();
        if (title.isEmpty()) {
            return "ROM importada";
        }
        int count = title.codePointCount(0, title.length());
        return count <= 60 ? title : title.substring(0, title.offsetByCodePoints(0, 60));
    }

    private static String message(Exception error) {
        return error.getLocalizedMessage() == null ? "No se pudo leer o guardar el archivo."
                : error.getLocalizedMessage();
    }

    private void run(String busyStatus, Action action) {
        if (working) {
            return;
        }
        working = true;
        status = busyStatus;
        notifyObservers();
        List<Rom> previous = roms;
        io.execute(() -> {
            String result;
            List<Rom> updated;
            try {
                result = action.run();
            } catch (IOException | RuntimeException error) {
                result = message(error);
            }
            try {
                updated = readLibrary();
            } catch (IOException | RuntimeException error) {
                updated = previous;
                result = result.isEmpty() ? message(error) : result + "\n" + message(error);
            }
            String finalStatus = result;
            List<Rom> finalRoms = updated;
            main.post(() -> {
                importProgress = null;
                for (Observer observer : new ArrayList<>(observers)) {
                    observer.onImportProgress(null);
                }
                status = finalStatus;
                roms = finalRoms;
                working = false;
                notifyObservers();
            });
        });
    }

    private void notifyObservers() {
        for (Observer observer : new ArrayList<>(observers)) {
            observer.onLibraryChanged(roms, working, status);
        }
    }
}
