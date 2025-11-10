package org.example.shortlinkapp.storage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/**
 * Utility for persisting and retrieving the "current" user UUID on the local machine.
 *
 * <p>The UUID is stored in a plain-text file at <code>.local/user.uuid</code> (relative to the
 * working directory). This class provides two main operations:
 *
 * <ul>
 *   <li>{@link #ensureCurrentUserUuid()} — loads the UUID if present; otherwise generates a new one,
 *       saves it to disk, and returns it. If persistence fails (e.g., I/O error), an ephemeral UUID
 *       is generated and returned for the current process only.
 *   <li>{@link #setCurrentUserUuid(String)} — explicitly writes a provided UUID to the storage
 *       location, replacing any existing value.
 * </ul>
 *
 * <h3>Thread-safety</h3>
 * Methods are not synchronized; typical usage is from a single CLI process. The underlying writes
 * replace the entire file content.
 *
 * <h3>Error handling</h3>
 * On read/write failures, diagnostics are printed to {@code System.err}. The API avoids throwing
 * checked exceptions to keep call sites simple in a console application.
 */
public final class LocalUuid {

    /** Directory used to keep a local app state (created if missing). */
    private static final Path LOCAL_DIR = Paths.get(".local");
    /** File holding the current user's UUID in text form. */
    private static final Path UUID_FILE = LOCAL_DIR.resolve("user.uuid");

    private LocalUuid() {}

    /**
     * Ensures a stable "current user" UUID for this machine/session.
     *
     * <p>Behavior:
     * <ol>
     *   <li>If <code>.local/user.uuid</code> exists and contains a non-blank line, that trimmed value
     *       is returned.
     *   <li>Otherwise, a new {@link UUID} is generated, written to the file (creating directories and
     *       file as needed), and then returned.
     *   <li>If persistence fails (e.g., cannot create directory/file), a new UUID is generated and
     *       returned without saving it to disk; a warning is printed to {@code System.err}.
     * </ol>
     *
     * @return the existing or newly generated UUID string (never {@code null})
     */
    public static String ensureCurrentUserUuid() {
        try {
            Files.createDirectories(LOCAL_DIR);
            if (Files.exists(UUID_FILE)) {
                try (BufferedReader br = Files.newBufferedReader(UUID_FILE, StandardCharsets.UTF_8)) {
                    String s = br.readLine();
                    if (s != null && !s.isBlank()) {
                        return s.trim();
                    }
                }
            }
            String generated = UUID.randomUUID().toString();
            try (BufferedWriter bw =
                         Files.newBufferedWriter(
                                 UUID_FILE,
                                 StandardCharsets.UTF_8,
                                 StandardOpenOption.CREATE,
                                 StandardOpenOption.TRUNCATE_EXISTING,
                                 StandardOpenOption.WRITE)) {
                bw.write(generated);
                bw.write("\n");
            }
            return generated;
        } catch (IOException e) {
            // If we cannot persist, still return an in-memory UUID for this session
            String fallback = UUID.randomUUID().toString();
            System.err.println(
                    "Warning: cannot persist user UUID, using ephemeral: "
                            + fallback
                            + " (cause: "
                            + e.getMessage()
                            + ")");
            return fallback;
        }
    }

    /**
     * Persists the provided UUID as the "current" user identifier.
     *
     * <p>Writes the given value to <code>.local/user.uuid</code>, creating the directory and file if
     * necessary. The value is trimmed before writing. Invalid input (null/blank) is rejected.
     *
     * @param uuid the UUID string to store; must be non-blank (format is not validated here)
     * @return {@code true} if the UUID was written successfully; {@code false} if the input was
     *     blank or an I/O error occurred (an error message is printed to {@code System.err})
     */
    public static boolean setCurrentUserUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) return false;
        try {
            Files.createDirectories(LOCAL_DIR);
            try (BufferedWriter bw =
                         Files.newBufferedWriter(
                                 UUID_FILE,
                                 StandardCharsets.UTF_8,
                                 StandardOpenOption.CREATE,
                                 StandardOpenOption.TRUNCATE_EXISTING,
                                 StandardOpenOption.WRITE)) {
                bw.write(uuid.trim());
                bw.write("\n");
            }
            return true;
        } catch (IOException e) {
            System.err.println("Failed to write default user UUID: " + e.getMessage());
            return false;
        }
    }
}
