package org.example.shortlinkapp.storage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

public final class LocalUuid {

  private static final Path LOCAL_DIR = Paths.get(".local");
  private static final Path UUID_FILE = LOCAL_DIR.resolve("user.uuid");

  private LocalUuid() {}

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
