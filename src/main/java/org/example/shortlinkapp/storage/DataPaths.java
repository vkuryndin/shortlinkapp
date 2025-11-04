package org.example.shortlinkapp.storage;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class DataPaths {
    private DataPaths() {}

    public static final Path DATA_DIR   = Paths.get("data");
    public static final Path USERS_JSON = DATA_DIR.resolve("users.json");
    public static final Path LINKS_JSON = DATA_DIR.resolve("links.json");
    public static final Path EVENTS_JSON= DATA_DIR.resolve("events.json");
}
