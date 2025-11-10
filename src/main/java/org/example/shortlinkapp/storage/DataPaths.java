package org.example.shortlinkapp.storage;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Centralized set of file-system paths used by storage components.
 *
 * <p>All paths are relative to the working directory:
 *
 * <ul>
 *   <li>{@link #DATA_DIR} – root data folder.
 *   <li>{@link #USERS_JSON} – users registry JSON.
 *   <li>{@link #LINKS_JSON} – short links JSON.
 *   <li>{@link #EVENTS_JSON} – event log JSON.
 * </ul>
 *
 * <p>This class is a non-instantiable constants holder.
 */
public final class DataPaths {
  private DataPaths() {}

  /** Root directory for application data files: {@code data/}. */
  public static final Path DATA_DIR = Paths.get("data");

  /** JSON file storing user records: {@code data/users.json}. */
  public static final Path USERS_JSON = DATA_DIR.resolve("users.json");

  /** JSON file storing short links: {@code data/links.json}. */
  public static final Path LINKS_JSON = DATA_DIR.resolve("links.json");

  /** JSON file storing event logs: {@code data/events.json}. */
  public static final Path EVENTS_JSON = DATA_DIR.resolve("events.json");
}
