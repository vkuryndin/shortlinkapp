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

/**
 * Minimal JSON-backed persistence helper for small repositories.
 *
 * <p>This utility provides two operations:
 *
 * <ul>
 *   <li>{@link #readOrDefault(Path, Type, Object)} — read JSON into a model type, creating the file
 *       with the provided default value when it doesn't exist or when parsing returns {@code null}.
 *   <li>{@link #writeAtomic(Path, Object)} — write a value as JSON to a temporary file and then
 *       atomically move it into place to prevent partial writes and reduce corruption risk.
 * </ul>
 *
 * <p>All file I/O is performed with UTF-8 encoding. Parent directories are created as needed.
 *
 * <p><b>Thread-safety:</b> this class is stateless; callers must ensure external synchronization if
 * multiple threads/processes write to the same path concurrently.
 */
final class JsonRepository {
  /** Not instantiable. */
  private JsonRepository() {}

  /** Shared Gson instance configured by {@link JsonUtils#gson()}. */
  private static final Gson GSON = JsonUtils.gson();

  /**
   * Reads JSON from {@code path} into an object of type {@code typeOfT}. If the file does not
   * exist, the method creates it and writes {@code defaultValue} into the new file, then returns
   * {@code defaultValue}. If reading/parsing fails, a warning is printed to {@code stderr} and
   * {@code defaultValue} is returned.
   *
   * <p>Behavioral details:
   *
   * <ul>
   *   <li>Ensures the parent directory exists.
   *   <li>When the file exists but JSON parsing returns {@code null}, {@code defaultValue} is
   *       returned.
   *   <li>When the file does not exist, it is created and initialized with {@code defaultValue}.
   * </ul>
   *
   * @param path the file to read
   * @param typeOfT the target type token (e.g., {@code new TypeToken<List<Foo>>() {}.getType()})
   * @param defaultValue the value to return (and to initialize the file with) when the file is
   *     missing or parsing yields {@code null}, or when an I/O error occurs
   * @param <T> the result type
   * @return the parsed value, or {@code defaultValue} on absence/error/null content
   */
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
  /**
   * Writes {@code value} as JSON to {@code target} using an atomic replace strategy.
   *
   * <p>The method writes to a temporary file in the same directory (named {@code .<filename>.tmp})
   * and then performs {@link Files#move(Path, Path, CopyOption...)} with {@link
   * StandardCopyOption#ATOMIC_MOVE} and {@link StandardCopyOption#REPLACE_EXISTING}. This minimizes
   * the risk of partially written files in case of crashes.
   *
   * <p>Preconditions and safety checks:
   *
   * <ul>
   *   <li>{@code target} and {@code value} must be non-null.
   *   <li>The parent directory must exist (it will be created if needed). If {@code target} has no
   *       parent, an {@link IOException} is thrown.
   *   <li>If {@code target.getFileName()} is {@code null} (root path), a safe fallback name {@code
   *       "data"} is used for the temp file.
   * </ul>
   *
   * @param target destination file to write
   * @param value the object to serialize as JSON
   * @throws IOException if the parent directory is missing and cannot be created, if the target has
   *     no parent, or if the write/move operation fails
   */
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

  /**
   * Ensures that the parent directory for {@code p} exists; creates it if necessary.
   *
   * @param p the path whose parent should exist
   * @throws IOException if the directory cannot be created
   */
  private static void ensureParent(Path p) throws IOException {
    Path parent = p.getParent();
    if (parent != null) Files.createDirectories(parent);
  }
}
