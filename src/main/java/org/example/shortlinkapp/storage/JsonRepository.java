package org.example.shortlinkapp.storage;


import com.google.gson.Gson;
import org.example.shortlinkapp.util.JsonUtils;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;


final class JsonRepository {
    private JsonRepository() {}


    private static final Gson GSON = JsonUtils.gson();


    static <T> T readOrDefault(Path path, Type typeOfT, T defaultValue) {
        try {
            ensureParent(path);
            if (Files.exists(path)) {
                try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    T data = GSON.fromJson(br, typeOfT);
                    return (data != null) ? data : defaultValue;
                }
            } else {
// create empty file
                try (BufferedWriter bw = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    GSON.toJson(defaultValue, bw);
                }
                return defaultValue;
            }
        } catch (IOException e) {
            System.err.println("Warning: failed to read " + path + ": " + e.getMessage());
            return defaultValue;
        }
    }


    static <T> void writeAtomic(Path target, T payload) throws IOException {
        ensureParent(target);
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        try (BufferedWriter bw = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            GSON.toJson(payload, bw);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }


    private static void ensureParent(Path p) throws IOException {
        Path parent = p.getParent();
        if (parent != null) Files.createDirectories(parent);
    }
}