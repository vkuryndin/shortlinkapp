package org.example.shortlinkapp.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Application configuration holder with load/save helpers.
 *
 * <p>This class encapsulates all tunable parameters for the ShortlinkApp CLI application and
 * provides utilities to load them from (or persist them to) a JSON file located at {@code
 * data/config.json}. If the file is missing or unreadable, default in-memory values are used and a
 * default file is created on disk when possible.
 *
 * <p><b>File format:</b> pretty-printed JSON produced by Gson. All fields are public for simple
 * serialization/deserialization.
 *
 * <p><b>Typical usage:</b>
 *
 * <pre>{@code
 * ConfigJson cfg = ConfigJson.loadOrCreateDefault();
 * System.out.println(cfg.baseUrl);
 * }</pre>
 */
public class ConfigJson {

  /** Prefix used when printing short links (e.g., {@code cli://}). */
  public String baseUrl = "cli://";

  /** Length of the generated Base62 short code. */
  public int shortCodeLength = 7;

  /** Default time-to-live for new links, in hours. */
  public int defaultTtlHours = 24;

  /**
   * Default click limit for new links.
   *
   * <p>If {@code null}, links are created with unlimited clicks unless explicitly overridden.
   */
  public Integer defaultClickLimit = 10; // null => unlimited

  /** Maximum allowed length of the long URL (validation guard). */
  public int maxUrlLength = 2048;

  /**
   * When {@code true}, maintenance routines (expired and limit-reached cleanup) are executed
   * automatically before user-visible operations.
   */
  public boolean cleanupOnEachOp = true;

  /**
   * Allows the owner to change an existing link's click limit via the console UI when {@code true}.
   */
  public boolean allowOwnerEditLimit = true;

  /**
   * Controls cleanup behavior:
   *
   * <ul>
   *   <li>{@code true}: remove records permanently (hard delete).
   *   <li>{@code false}: only mark status (e.g., {@code EXPIRED}, {@code LIMIT_REACHED}).
   * </ul>
   */
  public boolean hardDeleteExpired = true;

  /** Enables the event/notification log when {@code true}. */
  public boolean eventsLogEnabled = true;

  /** Reserved for minor clock skew tolerance (in seconds) when validating time-based conditions. */
  public int clockSkewToleranceSec = 2;

  /** Shared Gson instance configured for pretty printing. */
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  /** Canonical filesystem location of {@code config.json}. */
  private static final Path CONFIG_PATH = Paths.get("data", "config.json");

  /**
   * Returns the canonical path to {@code data/config.json}.
   *
   * @return absolute or relative {@link Path} used by this application to store configuration
   */
  public static Path getConfigPath() {
    return CONFIG_PATH;
  }

  /**
   * Loads configuration from {@code data/config.json}, creating the file with defaults if it does
   * not exist.
   *
   * <p>If the file is present but cannot be parsed, the method falls back to default in-memory
   * values and logs the cause to {@code System.err}. Parent directories are created as necessary.
   *
   * @return a non-null {@link ConfigJson} instance populated from disk or defaults
   */
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

  /**
   * Writes a fresh default configuration to {@code data/config.json} (pretty-printed) and returns
   * the instance used for serialization.
   *
   * @return the default {@link ConfigJson} that was persisted
   * @throws IOException if the file cannot be created or written
   */
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

  /**
   * Ensures that the parent directory of the provided path exists.
   *
   * @param p path whose parent directory should be created if missing
   * @throws IOException if directory creation fails
   */
  private static void ensureParentDir(Path p) throws IOException {
    Path parent = p.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
  }
}
