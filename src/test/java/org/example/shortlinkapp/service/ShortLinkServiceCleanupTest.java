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
import org.example.shortlinkapp.model.User;
import org.example.shortlinkapp.storage.ConfigJson;
import org.example.shortlinkapp.storage.DataPaths;
import org.example.shortlinkapp.util.JsonUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stable, isolated cleanup tests for {@link ShortLinkService}.
 *
 * <p>Key ideas to make it stable:
 *
 * <ul>
 *   <li>All I/O goes to a sandbox via {@code @TempDir} + overriding {@code user.dir}.
 *   <li>{@code cleanupOnEachOp=false} so nothing is auto-cleaned before we call cleanup explicitly.
 *   <li>{@code hardDeleteExpired=true} so cleanup returns non-zero counts even when items were
 *       already marked as expired/limit-reached.
 *   <li>Both candidates (expired & limit-reached) are created within the SAME service instance,
 *       avoiding cache divergence.
 * </ul>
 */
public class ShortLinkServiceCleanupTest {

  @TempDir Path tempDir;

  private String originalUserDir;
  private String originalHeadless;
  private PrintStream originalOut;
  private ByteArrayOutputStream outCapture;

  private static final Gson GSON = JsonUtils.gson();

  @BeforeEach
  void setUp() throws Exception {
    originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
    Files.createDirectories(DataPaths.DATA_DIR);

    originalHeadless = System.getProperty("java.awt.headless");
    System.setProperty("java.awt.headless", "true");

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

  // -------- helpers --------

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

  private static ConfigJson cfgCleanupBase() {
    ConfigJson c = new ConfigJson();
    c.baseUrl = "cli://";
    c.shortCodeLength = 6;
    c.defaultTtlHours = 24; // normal positive TTL by default
    c.defaultClickLimit = 3;
    c.maxUrlLength = 2048;
    c.cleanupOnEachOp = false; // IMPORTANT: no auto cleanup before explicit calls
    c.allowOwnerEditLimit = true;
    c.hardDeleteExpired = true; // IMPORTANT: cleanup deletes & returns positive counts
    c.eventsLogEnabled = true;
    c.clockSkewToleranceSec = 2;
    return c;
  }

  private static ConfigJson cfgNegativeTtlFrom(ConfigJson base) {
    ConfigJson n = new ConfigJson();
    n.baseUrl = base.baseUrl;
    n.shortCodeLength = base.shortCodeLength;
    n.defaultTtlHours = -1; // make newly created links immediately expired by TTL
    n.defaultClickLimit = base.defaultClickLimit;
    n.maxUrlLength = base.maxUrlLength;
    n.cleanupOnEachOp = base.cleanupOnEachOp;
    n.allowOwnerEditLimit = base.allowOwnerEditLimit;
    n.hardDeleteExpired = base.hardDeleteExpired;
    n.eventsLogEnabled = base.eventsLogEnabled;
    n.clockSkewToleranceSec = base.clockSkewToleranceSec;
    return n;
  }

  private String stdout() {
    return outCapture.toString(StandardCharsets.UTF_8);
  }

  // -------- test --------

  @Test
  @DisplayName(
      "cleanupExpired / cleanupLimitReached: states change and counts are >= 1 (no auto-clean)")
  void cleanup_paths_stable() throws Exception {
    final String OWNER = "77777777-7777-7777-7777-777777777777";
    seedUsers(OWNER);

    // Base config: NO auto cleanup, HARD delete during cleanup
    ConfigJson base = cfgCleanupBase();
    ShortLinkService svc = new ShortLinkService(OWNER, base, new EventService(true));

    // 1) Create EXPIRED candidate by temporarily switching to negative TTL (in the SAME service)
    ConfigJson neg = cfgNegativeTtlFrom(base);
    svc.reloadConfig(neg);
    ShortLink expired = svc.createShortLink("http://example.com/expired-by-ttl", null);
    // (Do NOT touch it; status is still ACTIVE, but expiresAt is in the past.)
    // Switch back to the base config (still no auto cleanup)
    svc.reloadConfig(base);

    // 2) Create LIMIT-REACHED candidate: limit = 1, then open once (status becomes LIMIT_REACHED)
    ShortLink limited = svc.createShortLink("http://example.com/reached", 1);
    svc.openShortLink(limited.shortCode); // clickCount == 1 (== limit), status LIMIT_REACHED

    // Sanity: both entries must be present in repo before cleanup
    assertTrue(
        svc.findByShortCode(expired.shortCode).isPresent(),
        "[DEBUG] expired candidate is present before cleanup");
    assertTrue(
        svc.findByShortCode(limited.shortCode).isPresent(),
        "[DEBUG] limit candidate is present before cleanup");

    // Run both cleanups explicitly; with hardDelete=true they should delete matching entries.
    outCapture.reset();
    int nExp = svc.cleanupExpired();
    int nLim = svc.cleanupLimitReached();
    String log = stdout();
    System.out.println("[DEBUG] cleanupExpired returned: " + nExp);
    System.out.println("[DEBUG] cleanupLimitReached returned: " + nLim);
    System.out.println("[DEBUG] console:\n" + log);

    assertTrue(log.contains("Expired cleaned: "), "[DEBUG] must print expired summary");
    assertTrue(log.contains("Limit-reached cleaned: "), "[DEBUG] must print limit-reached summary");
    assertTrue(nExp >= 1, "[DEBUG] At least one expired entry should be processed.");
    assertTrue(nLim >= 1, "[DEBUG] At least one limit-reached entry should be processed.");

    // After HARD delete both should be absent
    assertTrue(
        svc.findByShortCode(expired.shortCode).isEmpty(),
        "[DEBUG] expired candidate should be deleted");
    assertTrue(
        svc.findByShortCode(limited.shortCode).isEmpty(),
        "[DEBUG] limit candidate should be deleted");
  }
}
