package org.example.shortlinkapp.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class ConfigJson {

  public String baseUrl = "cli://";
  public int shortCodeLength = 7;
  public int defaultTtlHours = 24;
  public Integer defaultClickLimit = 10; // null => unlimited
  public int maxUrlLength = 2048;
  public boolean cleanupOnEachOp = true;
  public boolean allowOwnerEditLimit = true;
  public boolean hardDeleteExpired = true;
  public boolean eventsLogEnabled = true;
  public int clockSkewToleranceSec = 2;

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Path CONFIG_PATH = Paths.get("data", "config.json");

  public static Path getConfigPath() {
    return CONFIG_PATH;
  }

  public static ConfigJson loadOrCreateDefault() {
    try {
      ensureParentDir(CONFIG_PATH);
      if (Files.exists(CONFIG_PATH)) {
        try (BufferedReader br = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
          ConfigJson cfg = GSON.fromJson(br, ConfigJson.class);
          return (cfg != null) ? cfg : writeDefault();
        }
      } else {
        return writeDefault();
      }
    } catch (IOException e) {
      System.err.println(
          "Failed to load config.json, using in-memory defaults. Cause: " + e.getMessage());
      return new ConfigJson();
    }
  }

  private static ConfigJson writeDefault() throws IOException {
    ConfigJson def = new ConfigJson();
    try (BufferedWriter bw =
        Files.newBufferedWriter(
            CONFIG_PATH,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      GSON.toJson(def, bw);
    }
    return def;
  }

  private static void ensureParentDir(Path p) throws IOException {
    Path parent = p.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
  }
}
