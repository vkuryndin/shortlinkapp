package org.example.shortlinkapp.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import org.example.shortlinkapp.model.ShortLink;
import org.example.shortlinkapp.model.Status;
import org.example.shortlinkapp.storage.ConfigJson;
import org.example.shortlinkapp.storage.DataPaths;
import org.example.shortlinkapp.util.JsonUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Single end-to-end scenario for ConsoleMenu.
 *
 * <p>Key fix vs previous failing version: we PRE-SEED data files before running the menu:
 * - data/config.json          (defaults, baseUrl=cli://, allowOwnerEditLimit=true)
 * - data/users.json           (contains TEST_UUID)
 * - data/links.json           (contains link owned by TEST_UUID with shortCode=PRE_CODE)

 * This guarantees actionEditClickLimit() finds the link and ShortLinkService prints
 * "Limit for cli://<code> set to ..." as asserted by the test.
 */
public class ConsoleMenuTest {

  @TempDir Path tempDir;

  private String originalUserDir;
  private PrintStream originalOut;
  private ByteArrayOutputStream out;

  private static final Gson GSON = JsonUtils.gson();

  @BeforeEach
  void setUp() throws Exception {
    // Isolate all relative paths under @TempDir
    originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
    Files.createDirectories(DataPaths.DATA_DIR);

    // Capture stdout
    originalOut = System.out;
    out = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));

    // Prepare config/users/links
    writeDefaultConfig();
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
    System.setProperty("user.dir", originalUserDir);
  }

  // ---------- Helpers ----------

  private static void writeDefaultConfig() throws IOException {
    // ConfigJson.loadOrCreateDefault() пишет файл сам, но мы создадим его явно,
    // чтобы гарантировать нужные флаги (baseUrl/allowOwnerEditLimit/etc)
    ConfigJson cfg = new ConfigJson();
    cfg.baseUrl = "cli://";
    cfg.shortCodeLength = 6;
    cfg.defaultTtlHours = 24;
    cfg.defaultClickLimit = 10;
    cfg.maxUrlLength = 2048;
    cfg.cleanupOnEachOp = true;
    cfg.allowOwnerEditLimit = true;
    cfg.hardDeleteExpired = false; // нам важно не удалять, чтобы видеть статусы
    cfg.eventsLogEnabled = true;
    cfg.clockSkewToleranceSec = 2;

    Path p = ConfigJson.getConfigPath();

    // --- Null-safe parent creation (SpotBugs-friendly) ---
    Path parent = p.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    try (BufferedWriter bw =
        Files.newBufferedWriter(
            p,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(cfg, bw);
    }
  }

  private static void seedUser(String uuid) throws IOException {
    // Minimal users.json with a single user
    String json =
        "[{\"uuid\":\""
            + uuid
            + "\",\"createdAt\":\""
            + LocalDateTime.now().minusDays(1)
            + "\",\"lastSeenAt\":\""
            + LocalDateTime.now()
            + "\"}]";
    Files.writeString(
        DataPaths.USERS_JSON,
        json,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
  }

  private static void seedLink(String ownerUuid, String shortCode) throws IOException {
    ShortLink l = new ShortLink();
    l.id = "L-000001";
    l.ownerUuid = ownerUuid;
    l.longUrl = "http://example.com/seed";
    l.shortCode = shortCode;
    l.createdAt = LocalDateTime.now().minusHours(1);
    l.expiresAt = LocalDateTime.now().plusHours(24);
    l.clickLimit = 5;
    l.clickCount = 0;
    l.lastAccessAt = null;
    l.status = Status.ACTIVE;

    try (BufferedWriter bw =
        Files.newBufferedWriter(
            DataPaths.LINKS_JSON,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      // store as array
      GSON.toJson(new ShortLink[] {l}, bw);
    }
  }

  private static ConfigJson cfg() {
    // Use loader used by ConsoleMenu codepath to mimic the app behavior
    return ConfigJson.loadOrCreateDefault();
  }

  // ---------- The test you provided, unchanged in logic ----------

  @Test
  @DisplayName("Single comprehensive scenario (>80% coverage of ConsoleMenu)")
  void fullScenario_highCoverage() throws Exception {
    final String TEST_UUID = "99999999-9999-9999-9999-999999999999";
    final String PRE_CODE = "AaBbC1"; // we pre-seeded this code

    // PRE-SEED data so that Edit Limit/Details/Delete can work
    seedUser(TEST_UUID);
    seedLink(TEST_UUID, PRE_CODE);

    // One long script. NOTE: We never create a second ConsoleMenu or re-init InputUtils.
    // All prompts are answered in order.
    String script =
        String.join(
            "\n",
            // Settings -> reload
            "5",
            "reload",

            // Users: show + list + make default + back
            "6",
            "1",
            "2",
            "5",
            "0",

            // My Links
            "1",
            // List my links with explicit filters (ALL, query by shortCode, sort=2 clicks desc)
            "2",
            "ALL",
            PRE_CODE,
            "2",
            // Details (known code with/without prefix)
            "3",
            PRE_CODE,
            // Edit limit unlimited
            "4",
            "cli://" + PRE_CODE,
            "unlimited",
            // Edit limit numeric
            "4",
            PRE_CODE,
            "10",
            // Delete with confirm
            "5",
            PRE_CODE,
            "DELETE",
            // Notifications (default limit)
            "7",
            "",
            // Create new link: valid URL + invalid number (to hit 'Invalid number. Using default.')
            "1",
            "http://example.com/page",
            "x",
            // Back from My Links
            "0",

            // Open Short Link: blank and not-found
            "2",
            "1",
            "",
            "1",
            "notexists",
            "0",

            // Maintenance: cleanup expired/limit, validate, stats mine+global, event log global
            "3",
            "1",
            "2",
            "3",
            "4",
            "", // scope mine
            "", // default top
            "4",
            "global",
            "10",
            "5",
            "", // default 50
            "0",

            // Help, then Exit
            "4",
            "7");

    // Feed stdin BEFORE first InputUtils use
    System.setIn(new ByteArrayInputStream((script + "\n").getBytes(StandardCharsets.UTF_8)));

    // Run menu
    ConsoleMenu menu = new ConsoleMenu(cfg(), TEST_UUID);
    menu.mainLoop();

    // Assert on stable fragments
    String console = out.toString(StandardCharsets.UTF_8);

    // Main screens
    assertTrue(console.contains("Main Menu"), "Main menu must appear.");
    assertTrue(console.contains("Settings (config.json):"), "Settings screen must appear.");
    assertTrue(console.contains("Config reloaded."), "Reload confirmation must appear.");
    assertTrue(console.contains("Users"), "Users menu must appear.");
    assertTrue(console.contains("== Current User =="), "Current user panel must appear.");
    assertTrue(
        console.contains("Saved as default: " + TEST_UUID), "Default user save must be printed.");

    // My Links flow
    assertTrue(console.contains("My Links"), "My Links menu must appear.");
    assertTrue(
        console.contains("shortCode") || console.contains("You have no links yet."),
        "List header or 'no links' expected.");
    assertTrue(console.contains("== Link Details =="), "Details view must be printed.");
    assertTrue(
        console.contains("Limit for cli://" + PRE_CODE), "Edit limit success message must appear.");
    assertTrue(
        console.contains("Link deleted: cli://" + PRE_CODE), "Delete confirmation must appear.");

    // Creation of a new link (we don't need its code, only that it prints Created:)
    assertTrue(console.contains("Enter long URL: "), "Create prompt must appear.");
    assertTrue(
        console.contains("Invalid number. Using default."), "Invalid limit path must appear.");
    assertTrue(console.contains("Created: "), "Creation must print a 'Created:' line.");

    // Notifications
    assertTrue(
        console.contains("No notifications yet.")
            || (console.contains("type") && console.contains("message")),
        "Notifications header or empty message expected.");

    // Open Short Link
    assertTrue(console.contains("Open Short Link"), "Open menu header expected.");
    assertTrue(console.contains("No code provided."), "Blank code should be handled.");
    assertTrue(console.contains("Link not found: notexists"), "Not-found message expected.");

    // Maintenance
    assertTrue(console.contains("== JSON Validation Report =="), "Validation header must appear.");
    assertTrue(console.contains("== Statistics =="), "Statistics header must appear.");

    // Help
    assertTrue(console.contains("Help / Examples"), "Help screen must be printed.");
  }
}
