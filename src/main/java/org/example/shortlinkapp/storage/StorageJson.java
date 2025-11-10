package org.example.shortlinkapp.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;

/**
 * Utility for persisting JSON files with an <b>atomic write</b> strategy.
 *
 * <p>This class provides a single static method that serializes a list of items to JSON and writes
 * it to a target file using a temporary file + atomic move pattern:
 *
 * <ol>
 *   <li>Serialize the content into a temp file located in the same directory as the target
 *       (e.g., <code>.filename.tmp</code>).</li>
 *   <li>Move the temp file over the target using
 *       {@link StandardCopyOption#ATOMIC_MOVE} and {@link StandardCopyOption#REPLACE_EXISTING}.</li>
 * </ol>
 *
 * <p>The approach minimizes the risk of readers observing a partially written file and helps avoid
 * corruption in case of crashes mid-write. Parent directories are created if missing.
 *
 * <p><b>Thread-safety:</b> This class has no mutable state and performs no internal locking; calls
 * are independent. If multiple threads/processes write to the same path concurrently, the last
 * writer wins. Coordinate at a higher level if needed.
 *
 * <p><b>Character encoding:</b> Files are written in UTF-8 with pretty-printed JSON.
 */
public final class StorageJson {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private StorageJson() {}

    // Keep the same signature; only make it null-safe for parent/fileName

    // Keep the same signature; no overloads added.
    /**
     * Writes the given list of items to the {@code target} path in JSON format using an
     * <em>atomic replace</em> strategy.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Validates {@code target}, {@code list}, and {@code type} are non-null.</li>
     *   <li>Ensures the parent directory of {@code target} exists; if the parent is {@code null}
     *       (e.g., a root path), an {@link IOException} is thrown.</li>
     *   <li>Creates a sibling temporary file named <code>.&lt;target-filename&gt;.tmp</code>
     *       (falls back to {@code "data"} if {@code getFileName()} is {@code null}).</li>
     *   <li>Serializes {@code list} to the temp file with UTF-8 encoding.</li>
     *   <li>Atomically moves the temp file over {@code target} with
     *       {@link StandardCopyOption#REPLACE_EXISTING} and {@link StandardCopyOption#ATOMIC_MOVE}.</li>
     * </ul>
     *
     * <p><b>Note:</b> Some filesystems may ignore {@link StandardCopyOption#ATOMIC_MOVE}; in such
     * environments, the move is still requested but true atomicity is not guaranteed by the FS.
     *
     * @param target the destination JSON file path (must have a parent directory)
     * @param list the collection of items to serialize (never {@code null})
     * @param type the element type for the list; retained for signature compatibility
     * @param <T> element type
     * @throws IOException if the parent directory cannot be created, the temp file cannot be written,
     *     or the atomic move fails
     * @implNote Uses {@link Gson} with pretty printing and {@link StandardCharsets#UTF_8}.
     */
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
