package org.example.shortlinkapp.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.example.shortlinkapp.model.ShortLink;
import org.example.shortlinkapp.model.Status;
import org.example.shortlinkapp.model.User;
import org.example.shortlinkapp.storage.ConfigJson;
import org.example.shortlinkapp.storage.DataPaths;
import org.example.shortlinkapp.util.JsonUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Single, high-coverage interactive scenario for {@link ConsoleMenu}.
 *
 * <p>Why single test? <br>
 * {@link InputUtils} holds a static final {@link java.util.Scanner} bound to System.in upon first
 * use. Running multiple tests with different System.in leads to stale/closed input in later tests.
 * This class runs one comprehensive scenario so the static Scanner stays valid throughout.
 *
 * <p>What is covered:
 *
 * <ul>
 *   <li>Settings + reload (actionReloadConfig)
 *   <li>Users: show/list/make default
 *   <li>My Links: list with filters, details (existing code), edit limit (unlimited & number),
 *       delete (with confirm), export, view notifications, create link (valid URL + invalid limit)
 *   <li>Open Short Link: blank & not-found
 *   <li>Maintenance: cleanup expired/limit, validate JSON, stats (mine & global), global event log
 *   <li>Help, Exit
 * </ul>
 *
 * <p>All repository files are isolated in {@code @TempDir} by overriding {@code user.dir}.
 */
public class ConsoleMenuTest {

  @TempDir Path tempDir;

  private String originalUserDir;
  private PrintStream originalOut;
  private InputStream originalIn;

  private ByteArrayOutputStream out;

  private static final Gson GSON = JsonUtils.gson();

  @BeforeEach
  void setUp() throws Exception {
    // Isolate relative "data" into tempDir
    originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.toAbsolutePath().toString());

    // Capture stdout
    originalOut = System.out;
    out = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));

    // Keep stdin to restore later
    originalIn = System.in;

    // Ensure data dir exists
    Files.createDirectories(DataPaths.DATA_DIR);

    // Prepare deterministic users.json and links.json
    seedUsersAndLinks();
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
    System.setIn(originalIn);
    System.setProperty("user.dir", originalUserDir);
  }

  /**
   * Seeds:
   *
   * <ul>
   *   <li>users.json: one known user (TEST_UUID)
   *   <li>links.json: one ACTIVE link owned by TEST_UUID with known shortCode PRE_CODE
   * </ul>
   */
  private void seedUsersAndLinks() throws IOException {
    String TEST_UUID = "99999999-9999-9999-9999-999999999999";
    String PRE_CODE = "AaBbC1"; // 6 chars to match default shortCodeLength in cfg()

    // users.json
    List<User> users = new ArrayList<>();
    User u = new User();
    u.uuid = TEST_UUID;
    u.createdAt = LocalDateTime.now().minusDays(1);
    u.lastSeenAt = LocalDateTime.now();
    users.add(u);
    try (BufferedWriter bw =
        Files.newBufferedWriter(
            DataPaths.USERS_JSON,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      GSON.toJson(users, bw);
    }

    // links.json
    List<ShortLink> links = new ArrayList<>();
    ShortLink l = new ShortLink();
    l.id = "L-000001";
    l.ownerUuid = TEST_UUID;
    l.longUrl = "http://pre.example.com/path";
    l.shortCode = PRE_CODE;
    l.createdAt = LocalDateTime.now().minusHours(2);
    l.expiresAt = LocalDateTime.now().plusHours(24);
    l.clickLimit = 5;
    l.clickCount = 1;
    l.lastAccessAt = null;
    l.status = Status.ACTIVE;
    links.add(l);
    try (BufferedWriter bw =
        Files.newBufferedWriter(
            DataPaths.LINKS_JSON,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      GSON.toJson(links, bw);
    }

    // empty events.json
    try (BufferedWriter bw =
        Files.newBufferedWriter(
            DataPaths.EVENTS_JSON,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      bw.write("[]");
    }
  }

  private static ConfigJson cfg() {
    ConfigJson c = new ConfigJson();
    c.baseUrl = "cli://";
    c.shortCodeLength = 6;
    c.defaultTtlHours = 24;
    c.defaultClickLimit = 3;
    c.maxUrlLength = 2048;
    c.cleanupOnEachOp = true;
    c.allowOwnerEditLimit = true;
    c.hardDeleteExpired = false; // keep entries for listing
    c.eventsLogEnabled = true;
    c.clockSkewToleranceSec = 2;
    return c;
  }

  @Test
  @DisplayName("Single comprehensive scenario (>80% coverage of ConsoleMenu)")
  void fullScenario_highCoverage() {
    final String TEST_UUID = "99999999-9999-9999-9999-999999999999";
    final String PRE_CODE = "AaBbC1"; // we pre-seeded this code

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
