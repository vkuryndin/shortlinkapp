package org.example.shortlinkapp.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.UUID;
import org.example.shortlinkapp.model.ShortLink;
import org.example.shortlinkapp.model.Status;
import org.example.shortlinkapp.service.EventService;
import org.example.shortlinkapp.service.ShortLinkService;
import org.example.shortlinkapp.storage.ConfigJson;
import org.example.shortlinkapp.storage.DataPaths;
import org.example.shortlinkapp.storage.LinksRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ConsoleMenuUsersAndBulkTest {

  @TempDir Path tmp;
  private String owner;
  private ConfigJson cfg;

  @BeforeEach
  void setUp() throws IOException {
    // Route "data/" under temp working dir so repos write to sandbox
    System.setProperty("user.dir", tmp.toString());
    Files.createDirectories(DataPaths.DATA_DIR);

    owner = UUID.randomUUID().toString();

    // Explicit config for tests
    cfg = new ConfigJson();
    cfg.hardDeleteExpired = false; // soft mode by default (mark but don't delete)
    cfg.cleanupOnEachOp = false; // no auto-clean during list/find etc.
    cfg.eventsLogEnabled = false; // keep tests quiet
    cfg.baseUrl = "cli://";
    cfg.shortCodeLength = 6;
    cfg.defaultTtlHours = 24;
    cfg.defaultClickLimit = 10;
    cfg.maxUrlLength = 2048;
    cfg.allowOwnerEditLimit = true;
  }

  @Test
  @DisplayName("Expired item becomes EXPIRED after bulk cleanup (mine) and is not deleted")
  void expired_becomes_EXPIRED_after_bulk_cleanup() {
    // Arrange
    LinksRepository seed = new LinksRepository();
    ShortLink expired = new ShortLink();
    expired.id = seed.nextId();
    expired.ownerUuid = owner;
    expired.longUrl = "https://example.com/old";
    expired.shortCode = "old001";
    expired.createdAt = LocalDateTime.now().minusDays(3);
    expired.expiresAt = LocalDateTime.now().minusDays(1); // already expired
    expired.clickLimit = 5;
    expired.clickCount = 1;
    expired.lastAccessAt = null;
    expired.status = Status.ACTIVE;
    seed.add(expired);

    // Act
    ShortLinkService svc = new ShortLinkService(owner, cfg, new EventService(cfg.eventsLogEnabled));
    int processed = svc.bulkDeleteExpiredMine();

    // Assert
    assertTrue(processed > 0, "Expected at least one record to be processed.");
    LinksRepository check = new LinksRepository();
    var updated = check.findByShortCode("old001").orElse(null);
    assertNotNull(updated, "Record must still exist (hardDeleteExpired=false).");
    assertEquals(Status.EXPIRED, updated.status, "Expired link must be marked EXPIRED.");
  }

  @Test
  @DisplayName("Reached-limit item becomes LIMIT_REACHED after bulk cleanup (mine)")
  void limitReached_becomes_LIMIT_REACHED_after_bulk_cleanup() {
    // Arrange
    LinksRepository seed = new LinksRepository();
    ShortLink limited = new ShortLink();
    limited.id = seed.nextId();
    limited.ownerUuid = owner;
    limited.longUrl = "https://example.com/hot";
    limited.shortCode = "hot001";
    limited.createdAt = LocalDateTime.now().minusHours(2);
    limited.expiresAt = LocalDateTime.now().plusDays(1);
    limited.clickLimit = 3;
    limited.clickCount = 3; // reached
    limited.lastAccessAt = LocalDateTime.now().minusMinutes(10);
    limited.status = Status.ACTIVE;
    seed.add(limited);

    // Act
    ShortLinkService svc = new ShortLinkService(owner, cfg, new EventService(cfg.eventsLogEnabled));
    int processed = svc.bulkDeleteLimitReachedMine();

    // Assert
    assertTrue(processed > 0, "Expected at least one record to be processed.");
    LinksRepository check = new LinksRepository();
    var updated = check.findByShortCode("hot001").orElse(null);
    assertNotNull(updated, "Record must still exist (hardDeleteExpired=false).");
    assertEquals(Status.LIMIT_REACHED, updated.status, "Link must be marked LIMIT_REACHED.");
  }

  @Test
  @DisplayName("Export my links returns a JSON file path under data/ and file exists")
  void export_creates_json_file() {
    // Arrange
    LinksRepository seed = new LinksRepository();
    ShortLink a = new ShortLink();
    a.id = seed.nextId();
    a.ownerUuid = owner;
    a.longUrl = "https://example.com/a";
    a.shortCode = "codeA1";
    a.createdAt = LocalDateTime.now();
    a.expiresAt = LocalDateTime.now().plusDays(1);
    a.clickLimit = null; // unlimited
    a.clickCount = 0;
    a.status = Status.ACTIVE;
    seed.add(a);

    ShortLink b = new ShortLink();
    b.id = seed.nextId();
    b.ownerUuid = owner;
    b.longUrl = "https://example.com/b";
    b.shortCode = "codeB1";
    b.createdAt = LocalDateTime.now();
    b.expiresAt = LocalDateTime.now().plusDays(2);
    b.clickLimit = 5;
    b.clickCount = 1;
    b.status = Status.ACTIVE;
    seed.add(b);

    // Act
    ShortLinkService svc = new ShortLinkService(owner, cfg, new EventService(cfg.eventsLogEnabled));
    Path out = svc.exportMyLinks();

    // Assert
    assertNotNull(out, "Export should return a non-null path.");
    assertTrue(Files.exists(out), "Exported file must exist.");
    assertEquals(DataPaths.DATA_DIR, out.getParent(), "Export file should be placed under data/.");
    try {
      String head = Files.readString(out);
      assertTrue(
          head.trim().startsWith("["), "Exported file should contain a JSON array of links.");
    } catch (IOException e) {
      fail("Failed to read exported file: " + e.getMessage());
    }
  }

  @Test
  @DisplayName(
      "cleanupExpired / cleanupLimitReached print summary lines "
          + "(compatible with old/new wording) and affect >=1 record")
  void cleanup_messages_and_counts() {
    // Arrange: one expired + one limit-reached
    LinksRepository seed = new LinksRepository();

    ShortLink expired = new ShortLink();
    expired.id = seed.nextId();
    expired.ownerUuid = owner;
    expired.longUrl = "https://example.com/old";
    expired.shortCode = "old002";
    expired.createdAt = LocalDateTime.now().minusDays(3);
    expired.expiresAt = LocalDateTime.now().minusDays(1);
    expired.clickLimit = 5;
    expired.clickCount = 1;
    expired.status = Status.ACTIVE;
    seed.add(expired);

    ShortLink limited = new ShortLink();
    limited.id = seed.nextId();
    limited.ownerUuid = owner;
    limited.longUrl = "https://example.com/hot";
    limited.shortCode = "hot002";
    limited.createdAt = LocalDateTime.now().minusHours(2);
    limited.expiresAt = LocalDateTime.now().plusDays(1);
    limited.clickLimit = 3;
    limited.clickCount = 3;
    limited.status = Status.ACTIVE;
    seed.add(limited);

    ShortLinkService svc = new ShortLinkService(owner, cfg, new EventService(cfg.eventsLogEnabled));

    // Capture stdout
    PrintStream orig = System.out;
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    // Explicit UTF-8 to avoid DM_DEFAULT_ENCODING
    System.setOut(new PrintStream(bout, true, StandardCharsets.UTF_8));
    int nExpired, nLimit;
    try {
      nExpired = svc.cleanupExpired();
      nLimit = svc.cleanupLimitReached();
    } finally {
      System.setOut(orig);
    }
    // Prefer explicit charset when converting bytes to String
    String out = bout.toString(StandardCharsets.UTF_8);

    assertTrue(nExpired >= 1, "Expected expired cleanup to affect >= 1 record.");
    assertTrue(nLimit >= 1, "Expected limit-reached cleanup to affect >= 1 record.");

    // Accept both old and new messages
    assertOutContainsAny(
        out, "Expired links deleted:", "Expired links marked as EXPIRED:", "Expired cleaned:");
    assertOutContainsAny(
        out,
        "Limit-reached links deleted:",
        "Limit-reached links marked as LIMIT_REACHED:",
        "Limit-reached cleaned:");

    // Soft mode: records remain but must be marked
    if (!cfg.hardDeleteExpired) {
      LinksRepository check = new LinksRepository();
      var ex = check.findByShortCode("old002").orElse(null);
      var lm = check.findByShortCode("hot002").orElse(null);
      assertNotNull(ex, "Expired record must still exist in soft mode.");
      assertEquals(Status.EXPIRED, ex.status, "Expired must be marked EXPIRED.");
      assertNotNull(lm, "Limit-reached record must still exist in soft mode.");
      assertEquals(Status.LIMIT_REACHED, lm.status, "Limited must be marked LIMIT_REACHED.");
    }
  }

  // ---- helper ---------------------------------------------------------------

  private static void assertOutContainsAny(String out, String... fragments) {
    for (String f : fragments) {
      if (out.contains(f)) return;
    }
    fail(
        "[DEBUG] expected stdout to contain one of: "
            + String.join(" | ", fragments)
            + "\n--- STDOUT ---\n"
            + out);
  }
}
