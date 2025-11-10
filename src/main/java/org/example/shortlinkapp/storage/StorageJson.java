package org.example.shortlinkapp.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;

/** */
public final class StorageJson {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private StorageJson() {}

  // Keep the same signature; only make it null-safe for parent/fileName

  // Keep the same signature; no overloads added.
  public static <T> void writeAtomic(Path target, List<T> list, Class<T> type) throws IOException {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(list, "list");
    Objects.requireNonNull(type, "type");

    // Ensure parent directory exists.
    Path parent = target.getParent();
    if (parent == null) {
      throw new IOException("Target path has no parent directory: " + target);
    }
    Files.createDirectories(parent);

    // Safe base name even if getFileName() returns null.
    Path fn = target.getFileName();
    String baseName = (fn != null) ? fn.toString() : "data";

    Path tmp = parent.resolve("." + baseName + ".tmp");

    // Serialize list and replace atomically.
    try (BufferedWriter bw =
        Files.newBufferedWriter(
            tmp,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      GSON.toJson(list, bw); // Class<T> kept in signature; not required for serialization here.
    }

    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }
}
