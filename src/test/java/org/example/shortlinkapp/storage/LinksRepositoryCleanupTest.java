package org.example.shortlinkapp.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.example.shortlinkapp.model.ShortLink;
import org.example.shortlinkapp.model.Status;
import org.example.shortlinkapp.util.JsonUtils;
import org.example.shortlinkapp.util.TimeUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Direct, stable coverage for cleanup methods in {@link LinksRepository}:
 *
 * <ul>
 *   <li>{@link LinksRepository#cleanupExpired(LocalDateTime, boolean)}
 *   <li>{@link LinksRepository#cleanupLimitReached(boolean)}
 *   <li>{@link LinksRepository#cleanupExpiredForOwner(LocalDateTime, String, boolean)}
 *   <li>{@link LinksRepository#cleanupLimitReachedForOwner(String, boolean)}
 * </ul>
 *
 * <p>Each test seeds data/links.json with a known dataset, then constructs a fresh {@code
 * LinksRepository} which loads that dataset, invokes the cleanup method, and asserts on returned
 * counts and on-file effects (status changes vs. deletions).
 *
 * <p>All paths are isolated via {@code @TempDir} by overriding {@code user.dir}.
 */
public class LinksRepositoryCleanupTest {

  @TempDir Path tempDir;

  private String originalUserDir;
  private static final Gson GSON = JsonUtils.gson();

  @BeforeEach
  void setUp() throws Exception {
    originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
    Files.createDirectories(DataPaths.DATA_DIR);
  }

  @AfterEach
  void tearDown() {
    System.setProperty("user.dir", originalUserDir);
  }

  // -----------------------------
  // Helpers
  // -----------------------------

  private static ShortLink link(
      String id,
      String owner,
      String code,
      String url,
      LocalDateTime created,
      LocalDateTime expires,
      Integer limit,
      int clicks,
      Status status) {

    ShortLink l = new ShortLink();
    l.id = id;
    l.ownerUuid = owner;
    l.shortCode = code;
    l.longUrl = url;
    l.createdAt = created;
    l.expiresAt = expires;
    l.clickLimit = limit;
    l.clickCount = clicks;
    l.lastAccessAt = null;
    l.status = status;
    return l;
  }

  private static void writeLinks(List<ShortLink> links) throws IOException {
    try (BufferedWriter bw =
        Files.newBufferedWriter(
            DataPaths.LINKS_JSON,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      GSON.toJson(links, bw);
    }
  }

  private static List<ShortLink> readLinks() throws IOException {
    // We can re-use LinksRepository to flush/read, but for pure verification read raw file
    String json = Files.readString(DataPaths.LINKS_JSON, StandardCharsets.UTF_8);
    ShortLink[] arr = GSON.fromJson(json, ShortLink[].class);
    List<ShortLink> list = new ArrayList<>();
    if (arr != null) {
      for (ShortLink s : arr) list.add(s);
    }
    return list;
  }

  private static ShortLink findByCode(List<ShortLink> list, String code) {
    for (ShortLink l : list) if (code.equals(l.shortCode)) return l;
    return null;
  }

  // -----------------------------
  // Tests
  // -----------------------------

  @Test
  @DisplayName("cleanupExpired(now,false): marks EXPIRED (no deletions) and returns count")
  void cleanupExpired_soft() throws Exception {
    LocalDateTime now = LocalDateTime.now();

    // Dataset:
    //  - L1: expired ACTIVE -> should become EXPIRED
    //  - L2: not expired ACTIVE -> unchanged
    //  - L3: already DELETED and expired -> ignored
    List<ShortLink> seed = new ArrayList<>();
    seed.add(
        link(
            "L-000001",
            "A",
            "AAA111",
            "http://a/1",
            now.minusDays(2),
            now.minusMinutes(1),
            null,
            0,
            Status.ACTIVE));
    seed.add(
        link(
            "L-000002",
            "A",
            "AAA222",
            "http://a/2",
            now.minusDays(1),
            now.plusDays(1),
            null,
            0,
            Status.ACTIVE));
    seed.add(
        link(
            "L-000003",
            "A",
            "AAA333",
            "http://a/3",
            now.minusDays(3),
            now.minusDays(1),
            null,
            0,
            Status.DELETED));
    writeLinks(seed);

    LinksRepository repo = new LinksRepository();
    int changed = repo.cleanupExpired(now, /* hardDelete= */ false);
    assertEquals(1, changed, "Exactly one link should be marked EXPIRED.");

    List<ShortLink> after = readLinks();
    assertEquals(Status.EXPIRED, findByCode(after, "AAA111").status, "L1 must be EXPIRED now.");
    assertEquals(Status.ACTIVE, findByCode(after, "AAA222").status, "L2 must remain ACTIVE.");
    assertEquals(
        Status.DELETED, findByCode(after, "AAA333").status, "Deleted entries are ignored.");
  }

  @Test
  @DisplayName("cleanupExpired(now,true): deletes expired entries and returns count")
  void cleanupExpired_hardDelete() throws Exception {
    LocalDateTime now = LocalDateTime.now();

    List<ShortLink> seed = new ArrayList<>();
    seed.add(
        link(
            "L-000011",
            "A",
            "A1",
            "http://a/1",
            now.minusDays(2),
            now.minusMinutes(1),
            null,
            0,
            Status.ACTIVE)); // expired -> delete
    seed.add(
        link(
            "L-000012",
            "A",
            "A2",
            "http://a/2",
            now.minusDays(1),
            now.plusDays(1),
            null,
            0,
            Status.ACTIVE)); // alive
    writeLinks(seed);

    LinksRepository repo = new LinksRepository();
    int deleted = repo.cleanupExpired(now, /* hardDelete= */ true);
    assertEquals(1, deleted, "Exactly one expired entry should be deleted.");

    List<ShortLink> after = readLinks();
    assertNull(findByCode(after, "A1"), "Expired L-000011 must be removed from file.");
    assertNotNull(findByCode(after, "A2"), "Alive L-000012 must remain.");
  }

  @Test
  @DisplayName("cleanupLimitReached(false): marks LIMIT_REACHED (no deletions) and returns count")
  void cleanupLimitReached_soft() throws Exception {
    LocalDateTime now = LocalDateTime.now();

    List<ShortLink> seed = new ArrayList<>();
    seed.add(
        link(
            "L-000021",
            "A",
            "B1",
            "http://b/1",
            now.minusDays(1),
            now.plusDays(1),
            3,
            3,
            Status.ACTIVE)); // at limit -> mark
    seed.add(
        link(
            "L-000022",
            "A",
            "B2",
            "http://b/2",
            now.minusDays(1),
            now.plusDays(1),
            5,
            2,
            Status.ACTIVE)); // under limit
    seed.add(
        link(
            "L-000023",
            "A",
            "B3",
            "http://b/3",
            now.minusDays(1),
            now.plusDays(1),
            1,
            2,
            Status.DELETED)); // deleted -> ignored
    writeLinks(seed);

    LinksRepository repo = new LinksRepository();
    int changed = repo.cleanupLimitReached(/* hardDelete= */ false);
    assertEquals(1, changed, "Exactly one entry should be marked LIMIT_REACHED.");

    List<ShortLink> after = readLinks();
    assertEquals(
        Status.LIMIT_REACHED, findByCode(after, "B1").status, "B1 must be LIMIT_REACHED now.");
    assertEquals(Status.ACTIVE, findByCode(after, "B2").status, "B2 must remain ACTIVE.");
    assertEquals(Status.DELETED, findByCode(after, "B3").status, "Deleted entries are ignored.");
  }

  @Test
  @DisplayName("cleanupLimitReached(true): deletes reached-limit entries and returns count")
  void cleanupLimitReached_hardDelete() throws Exception {
    LocalDateTime now = LocalDateTime.now();

    List<ShortLink> seed = new ArrayList<>();
    seed.add(
        link(
            "L-000031",
            "A",
            "C1",
            "http://c/1",
            now.minusDays(1),
            now.plusDays(1),
            1,
            1,
            Status.ACTIVE)); // reached -> delete
    seed.add(
        link(
            "L-000032",
            "A",
            "C2",
            "http://c/2",
            now.minusDays(1),
            now.plusDays(1),
            2,
            1,
            Status.ACTIVE)); // not reached
    writeLinks(seed);

    LinksRepository repo = new LinksRepository();
    int deleted = repo.cleanupLimitReached(/* hardDelete= */ true);
    assertEquals(1, deleted, "Exactly one reached-limit entry should be deleted.");

    List<ShortLink> after = readLinks();
    assertNull(findByCode(after, "C1"), "C1 (reached) must be removed.");
    assertNotNull(findByCode(after, "C2"), "C2 (not reached) must remain.");
  }

  @Test
  @DisplayName("cleanupExpiredForOwner(now, owner, false): marks EXPIRED for only that owner")
  void cleanupExpiredForOwner_soft_ownerScope() throws Exception {
    LocalDateTime now = LocalDateTime.now();
    String ownerA = "owner-A";
    String ownerB = "owner-B";

    List<ShortLink> seed = new ArrayList<>();
    // A: one expired, one alive
    seed.add(
        link(
            "L-000041",
            ownerA,
            "D1",
            "http://d/1",
            now.minusDays(2),
            now.minusMinutes(1),
            null,
            0,
            Status.ACTIVE)); // expired -> mark
    seed.add(
        link(
            "L-000042",
            ownerA,
            "D2",
            "http://d/2",
            now.minusDays(1),
            now.plusDays(1),
            null,
            0,
            Status.ACTIVE)); // alive
    // B: expired but should be ignored in owner-scope A
    seed.add(
        link(
            "L-000043",
            ownerB,
            "D3",
            "http://d/3",
            now.minusDays(3),
            now.minusMinutes(1),
            null,
            0,
            Status.ACTIVE)); // expired (other owner)
    writeLinks(seed);

    LinksRepository repo = new LinksRepository();
    int changed = repo.cleanupExpiredForOwner(now, ownerA, /* hardDelete= */ false);
    assertEquals(1, changed, "Only one of owner A should be marked EXPIRED.");

    List<ShortLink> after = readLinks();
    assertEquals(Status.EXPIRED, findByCode(after, "D1").status, "A/D1 must be EXPIRED now.");
    assertEquals(Status.ACTIVE, findByCode(after, "D2").status, "A/D2 must remain ACTIVE.");
    assertEquals(Status.ACTIVE, findByCode(after, "D3").status, "B/D3 unchanged in owner A scope.");
  }

  @Test
  @DisplayName("cleanupExpiredForOwner(now, owner, true): deletes only owner's expired entries")
  void cleanupExpiredForOwner_hard_ownerScope() throws Exception {
    LocalDateTime now = LocalDateTime.now();
    String ownerA = "owner-A";
    String ownerB = "owner-B";

    List<ShortLink> seed = new ArrayList<>();
    seed.add(
        link(
            "L-000051",
            ownerA,
            "E1",
            "http://e/1",
            now.minusDays(2),
            now.minusMinutes(1),
            null,
            0,
            Status.ACTIVE)); // expired -> delete
    seed.add(
        link(
            "L-000052",
            ownerA,
            "E2",
            "http://e/2",
            now.minusDays(1),
            now.plusDays(1),
            null,
            0,
            Status.ACTIVE)); // alive
    seed.add(
        link(
            "L-000053",
            ownerB,
            "E3",
            "http://e/3",
            now.minusDays(3),
            now.minusMinutes(1),
            null,
            0,
            Status.ACTIVE)); // other owner expired
    writeLinks(seed);

    LinksRepository repo = new LinksRepository();
    int deleted = repo.cleanupExpiredForOwner(now, ownerA, /* hardDelete= */ true);
    assertEquals(1, deleted, "Exactly one of owner A should be deleted.");

    List<ShortLink> after = readLinks();
    assertNull(findByCode(after, "E1"), "A/E1 must be removed.");
    assertNotNull(findByCode(after, "E2"), "A/E2 must remain.");
    assertNotNull(findByCode(after, "E3"), "B/E3 must remain (different owner).");
  }

  @Test
  @DisplayName("cleanupLimitReachedForOwner(owner, false): marks LIMIT_REACHED for only that owner")
  void cleanupLimitReachedForOwner_soft_ownerScope() throws Exception {
    LocalDateTime now = LocalDateTime.now();
    String ownerA = "owner-A";
    String ownerB = "owner-B";

    List<ShortLink> seed = new ArrayList<>();
    seed.add(
        link(
            "L-000061",
            ownerA,
            "F1",
            "http://f/1",
            now.minusDays(1),
            now.plusDays(1),
            2,
            2,
            Status.ACTIVE)); // reached -> mark
    seed.add(
        link(
            "L-000062",
            ownerA,
            "F2",
            "http://f/2",
            now.minusDays(1),
            now.plusDays(1),
            5,
            1,
            Status.ACTIVE)); // not reached
    seed.add(
        link(
            "L-000063",
            ownerB,
            "F3",
            "http://f/3",
            now.minusDays(1),
            now.plusDays(1),
            1,
            1,
            Status.ACTIVE)); // other owner reached
    writeLinks(seed);

    LinksRepository repo = new LinksRepository();
    int changed = repo.cleanupLimitReachedForOwner(ownerA, /* hardDelete= */ false);
    assertEquals(1, changed, "Only one of owner A should be marked.");

    List<ShortLink> after = readLinks();
    assertEquals(
        Status.LIMIT_REACHED, findByCode(after, "F1").status, "A/F1 must be LIMIT_REACHED.");
    assertEquals(Status.ACTIVE, findByCode(after, "F2").status, "A/F2 must remain ACTIVE.");
    assertEquals(Status.ACTIVE, findByCode(after, "F3").status, "B/F3 unchanged in owner A scope.");
  }

  @Test
  @DisplayName(
      "cleanupLimitReachedForOwner(owner, true): deletes only owner's reached-limit entries")
  void cleanupLimitReachedForOwner_hard_ownerScope() throws Exception {
    LocalDateTime now = LocalDateTime.now();
    String ownerA = "owner-A";
    String ownerB = "owner-B";

    List<ShortLink> seed = new ArrayList<>();
    seed.add(
        link(
            "L-000071",
            ownerA,
            "G1",
            "http://g/1",
            now.minusDays(1),
            now.plusDays(1),
            1,
            2,
            Status.ACTIVE)); // reached -> delete
    seed.add(
        link(
            "L-000072",
            ownerA,
            "G2",
            "http://g/2",
            now.minusDays(1),
            now.plusDays(1),
            3,
            1,
            Status.ACTIVE)); // not reached
    seed.add(
        link(
            "L-000073",
            ownerB,
            "G3",
            "http://g/3",
            now.minusDays(1),
            now.plusDays(1),
            1,
            1,
            Status.ACTIVE)); // other owner reached
    writeLinks(seed);

    LinksRepository repo = new LinksRepository();
    int deleted = repo.cleanupLimitReachedForOwner(ownerA, /* hardDelete= */ true);
    assertEquals(1, deleted, "Exactly one of owner A should be removed.");

    List<ShortLink> after = readLinks();
    assertNull(findByCode(after, "G1"), "A/G1 must be deleted.");
    assertNotNull(findByCode(after, "G2"), "A/G2 must remain.");
    assertNotNull(findByCode(after, "G3"), "B/G3 must remain (different owner).");
  }

  @Test
  @DisplayName("TimeUtils.isExpired sanity check used by repository (boundary inclusive)")
  void timeUtilsBoundary() {
    LocalDateTime now = LocalDateTime.now();
    assertTrue(TimeUtils.isExpired(now, now), "Equal timestamps are considered expired.");
    assertTrue(TimeUtils.isExpired(now, now.minusSeconds(1)), "Past expiresAt is expired.");
    assertFalse(TimeUtils.isExpired(now, now.plusSeconds(1)), "Future expiresAt is not expired.");
  }
}
