package org.example.shortlinkapp.storage;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Objects;
import org.example.shortlinkapp.util.JsonUtils;

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
        // create an empty file
        try (BufferedWriter bw =
            Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
          GSON.toJson(defaultValue, bw);
        }
        return defaultValue;
      }
    } catch (IOException e) {
      System.err.println("Warning: failed to read " + path + ": " + e.getMessage());
      return defaultValue;
    }
  }

  // Keep the same signature.
  public static void writeAtomic(Path target, Object value) throws IOException {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(value, "value");

    // Ensure we have a parent directory; Path.getParent() can be null (root).
    Path parent = target.getParent();
    if (parent == null) {
      throw new IOException("Target path has no parent directory: " + target);
    }
    Files.createDirectories(parent);

    // Path.getFileName() may be null for root paths; use a safe fallback.
    Path fn = target.getFileName();
    String baseName = (fn != null) ? fn.toString() : "data";

    Path tmp = parent.resolve("." + baseName + ".tmp");

    // Write JSON into a temp file, then atomically move it into place.
    try (BufferedWriter bw =
        Files.newBufferedWriter(
            tmp,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      GSON.toJson(value, bw);
    }

    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static void ensureParent(Path p) throws IOException {
    Path parent = p.getParent();
    if (parent != null) Files.createDirectories(parent);
  }
}
