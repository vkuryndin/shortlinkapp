package org.example.shortlinkapp.service;

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
 * Working, isolated tests for {@link ShortLinkService}.
 *
 * <p><b>Isolation & stability:</b>
 *
 * <ul>
 *   <li>All JSON files are written into a sandbox by overriding {@code user.dir} to
 *       {@code @TempDir}.
 *   <li>{@code java.awt.headless=true} ensures {@code Desktop.isDesktopSupported()} is false, so
 *       {@link ShortLinkService#openShortLink(String)} prints “Copy and open manually” and never
 *       tries to open a real browser.
 *   <li>We seed {@code users.json} explicitly, because validation and ownership checks rely on it.
 * </ul>
 */
public class ShortLinkServiceTest {

  @TempDir Path tempDir;

  private String originalUserDir;
  private String originalHeadless;
  private PrintStream originalOut;
  private ByteArrayOutputStream outCapture;

  private static final Gson GSON = JsonUtils.gson();

  @BeforeEach
  void setUp() throws Exception {
    // Isolate relative "data" under tempDir
    originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
    Files.createDirectories(DataPaths.DATA_DIR);

    // Force headless to avoid Desktop opening attempts
    originalHeadless = System.getProperty("java.awt.headless");
    System.setProperty("java.awt.headless", "true");

    // Capture stdout for assertions on user-visible messages
    originalOut = System.out;
    outCapture = new ByteArrayOutputStream();
    System.setOut(new PrintStream(outCapture, true, StandardCharsets.UTF_8));
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
    if (originalHeadless == null) {
      System.clearProperty("java.awt.headless");
    } else {
      System.setProperty("java.awt.headless", originalHeadless);
    }
    System.setProperty("user.dir", originalUserDir);
  }

  // ---------- helpers ----------

  private static ConfigJson cfgDefault() {
    ConfigJson c = new ConfigJson();
    c.baseUrl = "cli://";
    c.shortCodeLength = 6;
    c.defaultTtlHours = 24;
    c.defaultClickLimit = 3; // small to reach limit quickly
    c.maxUrlLength = 2048;
    c.cleanupOnEachOp = true;
    c.allowOwnerEditLimit = true;
    c.hardDeleteExpired = false; // keep links to observe status transitions
    c.eventsLogEnabled = true;
    c.clockSkewToleranceSec = 2;
    return c;
  }

  private static ConfigJson cfgNegativeTtl() {
    ConfigJson c = cfgDefault();
    c.defaultTtlHours = -1; // ensures newly created links are already expired
    return c;
  }

  private static void seedUsers(String... uuids) throws IOException {
    List<User> users = new ArrayList<>();
    for (String id : uuids) {
      User u = new User();
      u.uuid = id;
      u.createdAt = LocalDateTime.now().minusDays(1);
      u.lastSeenAt = LocalDateTime.now();
      users.add(u);
    }
    try (BufferedWriter bw =
        Files.newBufferedWriter(
            DataPaths.USERS_JSON,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      GSON.toJson(users, bw);
    }
  }

  private String stdout() {
    return outCapture.toString(StandardCharsets.UTF_8);
  }

  // ---------- tests ----------

  @Test
  @DisplayName("Create, list, find, stats, export: must work and print expected messages")
  void create_list_find_stats_export() throws Exception {
    final String OWNER = "11111111-1111-1111-1111-111111111111";
    seedUsers(OWNER);

    ShortLinkService svc = new ShortLinkService(OWNER, cfgDefault(), new EventService(true));

    // Create two links (one with default limit, one with explicit)
    ShortLink a = svc.createShortLink("http://example.com/a", null);
    ShortLink b = svc.createShortLink("https://host/b", 5);

    assertNotNull(a.shortCode);
    assertNotNull(b.shortCode);
    assertEquals(2, svc.listMyLinks().size(), "Owner should see exactly 2 links.");

    assertTrue(svc.findByShortCode(a.shortCode).isPresent(), "findByShortCode must locate 'a'.");
    assertTrue(svc.findByShortCode(b.shortCode).isPresent(), "findByShortCode must locate 'b'.");

    // Stats (mine)
    ShortLinkService.Stats st = svc.statsMine(10);
    assertEquals(2, st.total, "Stats should see 2 links.");
    assertEquals(0, st.totalClicks, "No opens yet.");
    assertEquals(2, st.topByClicks.size(), "Top N should include both (N=10).");

    // Export
    Path out = svc.exportMyLinks();
    assertNotNull(out, "Export should return a file path.");
    assertTrue(Files.exists(out), "Export file must exist.");

    // Check console messages
    String console = stdout();
    assertTrue(console.contains("Exported 2 link(s)"), "Export should print exported count.");
  }

  @Test
  @DisplayName(
      "openShortLink: limit reached and expired paths produce expected outputs and statuses")
  void open_limit_and_expired() throws Exception {
    final String OWNER = "22222222-2222-2222-2222-222222222222";
    seedUsers(OWNER);

    // Limit scenario (default limit = 3)
    ShortLinkService svc = new ShortLinkService(OWNER, cfgDefault(), new EventService(true));
    ShortLink l = svc.createShortLink("http://example.com/limit", null);

    // Open twice -> still allowed (clickCount becomes 2)
    svc.openShortLink(l.shortCode);
    svc.openShortLink("cli://" + l.shortCode); // with baseUrl prefix
    // Third open -> limit reached (3/3) and blocked on next try
    svc.openShortLink(l.shortCode);
    svc.openShortLink(l.shortCode); // this attempt should print "Click limit reached..."

    String console1 = stdout();
    assertTrue(
        console1.contains("Copy and open manually: http://example.com/limit")
            || console1.contains("Opening in browser: http://example.com/limit"),
        "Must print an open message at least once.");
    assertTrue(
        console1.contains("Click limit reached"),
        "After reaching limit, service must report the limit reached.");

    // Expired scenario: create expired link in the SAME service via temporary TTL switch
    outCapture.reset();
    ConfigJson saved = cfgDefault();
    ConfigJson neg = cfgNegativeTtl();
    // Switch to negative TTL, create (expired), then switch back
    svc.reloadConfig(neg);
    ShortLink e = svc.createShortLink("http://example.com/expired", null);
    svc.reloadConfig(saved);

    svc.openShortLink(e.shortCode); // should be expired immediately

    String console2 = stdout();
    assertTrue(console2.contains("expired at"), "Expired message must be printed.");

    // Verify statuses via repository-backed service calls
    assertEquals(Status.LIMIT_REACHED, svc.findByShortCode(l.shortCode).get().status);
    assertEquals(Status.EXPIRED, svc.findByShortCode(e.shortCode).get().status);
  }

  @Test
  @DisplayName("editClickLimit: errors and success (unlimited and numeric), then deleteLink works")
  void edit_and_delete() throws Exception {
    final String OWNER = "33333333-3333-3333-3333-333333333333";
    seedUsers(OWNER);

    ShortLinkService svc = new ShortLinkService(OWNER, cfgDefault(), new EventService(true));
    ShortLink l = svc.createShortLink("http://example.com/edit", 5);

    // Invalid: negative
    outCapture.reset();
    boolean okNeg = svc.editClickLimit(l.shortCode, -1);
    assertFalse(okNeg, "Negative limit must be rejected.");
    assertTrue(stdout().contains("New limit must be positive."));

    // Increase clickCount to 2
    svc.openShortLink(l.shortCode);
    svc.openShortLink(l.shortCode);

    // Now set lower than current clicks but > 0 to hit the '< current clicks' branch
    outCapture.reset();
    boolean okTooLow = svc.editClickLimit(l.shortCode, 1);
    assertFalse(okTooLow, "Limit lower than current clicks must be rejected.");
    assertTrue(
        stdout().contains("New limit must be >= current clicks"), "Proper message expected.");

    // Set unlimited (null)
    outCapture.reset();
    boolean okUnlimited = svc.editClickLimit("cli://" + l.shortCode, null);
    assertTrue(okUnlimited, "Setting unlimited should be accepted.");
    assertTrue(stdout().contains("set to unlimited"));

    // Set specific numeric limit again
    outCapture.reset();
    boolean okNum = svc.editClickLimit(l.shortCode, 10);
    assertTrue(okNum, "Setting numeric limit should be accepted.");
    assertTrue(stdout().contains("set to 10"));

    // Delete by owner
    outCapture.reset();
    boolean del = svc.deleteLink(l.shortCode);
    assertTrue(del, "Owner should be able to delete the link.");
    assertTrue(stdout().contains("Link deleted: cli://" + l.shortCode));
  }

  @Test
  @DisplayName(
      "validateJson: reports issues for malformed links (missing fields / invalid URL / unknown owner)")
  void validateJson_reportsIssues() throws Exception {
    final String OWNER_OK = "55555555-5555-5555-5555-555555555555";
    final String OWNER_UNKNOWN = "66666666-6666-6666-6666-666666666666";
    seedUsers(OWNER_OK); // do NOT seed OWNER_UNKNOWN to trigger 'orphan link'

    ShortLinkService svc = new ShortLinkService(OWNER_OK, cfgDefault(), new EventService(true));
    // Ensure there is at least one valid link as baseline
    svc.createShortLink("http://valid.example.com", null);

    // Now inject malformed entries directly into links.json
    List<ShortLink> all = new ArrayList<>();
    // 1) valid one(s) — re-read from repository file to keep consistency
    try (BufferedReader br =
        Files.newBufferedReader(DataPaths.LINKS_JSON, StandardCharsets.UTF_8)) {
      ShortLink[] existing = GSON.fromJson(br, ShortLink[].class);
      if (existing != null) {
        for (ShortLink s : existing) all.add(s);
      }
    }

    // 2) Bad entry
    ShortLink bad = new ShortLink();
    bad.id = "L-999999";
    bad.ownerUuid = OWNER_UNKNOWN; // unknown owner
    bad.longUrl = "notaurl"; // invalid URL
    bad.shortCode = ""; // missing shortCode
    bad.createdAt = null; // missing dates
    bad.expiresAt = null;
    bad.clickLimit = 0; // invalid (<=0)
    bad.clickCount = -1; // invalid (<0)
    bad.lastAccessAt = null;
    bad.status = Status.ACTIVE;
    all.add(bad);

    try (BufferedWriter bw =
        Files.newBufferedWriter(
            DataPaths.LINKS_JSON,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      GSON.toJson(all, bw);
    }

    ShortLinkService.ValidationReport rep = svc.validateJson();
    assertTrue(rep.totalLinks >= 2, "There should be at least two entries in validation.");
    assertTrue(rep.issues > 0, "Malformed entry must produce issues.");
    assertFalse(rep.messages.isEmpty(), "Validation should list at least one message.");
  }
}
