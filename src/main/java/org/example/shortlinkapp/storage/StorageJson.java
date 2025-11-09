package org.example.shortlinkapp.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** */
public final class StorageJson {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private StorageJson() {}

  public static <T> void writeAtomic(Path target, List<T> items, Class<T> clazz)
      throws IOException {
    Path parent = target.getParent();
    if (parent != null) Files.createDirectories(parent);

    Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
    try (BufferedWriter bw =
        Files.newBufferedWriter(
            tmp,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      GSON.toJson(items, bw);
    }
    // Atomic move where supported; fallback to replace
    try {
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
