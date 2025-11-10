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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * High-coverage, stable tests for {@link LinksRepository}.
 *
 * <p>What we cover:
 *
 * <ul>
 *   <li>Sequence restoration and {@code nextId()}.
 *   <li>add/update/find/listByOwner/deleteByShortCodeForOwner/flush.
 *   <li>{@code cleanupExpired(now, hardDelete)}: soft mark and hard delete branches.
 *   <li>{@code cleanupLimitReached(hardDelete)}: soft mark and hard delete branches.
 *   <li>Owner-specific cleanups: {@code cleanupExpiredForOwner(...)} and {@code
 *       cleanupLimitReachedForOwner(...)} in both soft and hard modes.
 * </ul>
 *
 * <p>Isolation: we override {@code user.dir} to a fresh {@code @TempDir}, so all repository files
 * live under {@code data/} inside the sandbox.
 */
public class LinksRepositoryTest {

  @TempDir Path tempDir;

  private String originalUserDir;
  private static final Gson G = JsonUtils.gson();

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

  // ---------- helpers ----------

  private static ShortLink link(
      String id,
      String owner,
      String code,
      String url,
      LocalDateTime createdAt,
      LocalDateTime expiresAt,
      Integer clickLimit,
      int clickCount,
      Status status) {
    ShortLink l = new ShortLink();
    l.id = id;
    l.ownerUuid = owner;
    l.shortCode = code;
    l.longUrl = url;
    l.createdAt = createdAt;
    l.expiresAt = expiresAt;
    l.clickLimit = clickLimit;
    l.clickCount = clickCount;
    l.lastAccessAt = null;
    l.status = status;
    return l;
  }

  private static void writeLinks(List<ShortLink> list) throws IOException {
    try (BufferedWriter bw =
        Files.newBufferedWriter(
            DataPaths.LINKS_JSON,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      G.toJson(list, bw);
    }
  }

  private static List<ShortLink> readLinks() throws IOException {
    if (!Files.exists(DataPaths.LINKS_JSON)) return List.of();
    String json = Files.readString(DataPaths.LINKS_JSON, StandardCharsets.UTF_8);
    ShortLink[] arr = G.fromJson(json, ShortLink[].class);
    List<ShortLink> out = new ArrayList<>();
    if (arr != null) {
      for (ShortLink s : arr) out.add(s);
    }
    return out;
  }

  // ---------- tests ----------

  @Test
  @DisplayName("nextId() restores from existing L-xxxxxx and increments correctly")
  void nextId_restoresAndIncrements() throws Exception {
    LocalDateTime base = LocalDateTime.now().minusDays(1);
    List<ShortLink> seed = new ArrayList<>();
    seed.add(
        link("L-000003", "U1", "C3", "http://u/3", base, base.plusDays(1), 5, 0, Status.ACTIVE));
    seed.add(
        link("L-000007", "U1", "C7", "http://u/7", base, base.plusDays(1), 5, 0, Status.ACTIVE));
    seed.add(
        link("L-000002", "U1", "C2", "http://u/2", base, base.plusDays(1), 5, 0, Status.ACTIVE));
    writeLinks(seed);

    LinksRepository repo = new LinksRepository();
    // max existing = 7 → next must be 8
    String id1 = repo.nextId();
    String id2 = repo.nextId();
    assertEquals("L-000008", id1);
    assertEquals("L-000009", id2);
  }

  @Test
  @DisplayName(
      "Basic operations: add, update, findByShortCode, listByOwner, deleteByShortCodeForOwner")
  void basic_ops() throws Exception {
    LocalDateTime now = LocalDateTime.now();
    writeLinks(new ArrayList<>()); // start empty

    LinksRepository repo = new LinksRepository();

    ShortLink a =
        link("L-000001", "OWN-A", "AAA111", "http://a", now, now.plusDays(1), 10, 0, Status.ACTIVE);
    ShortLink b =
        link("L-000002", "OWN-B", "BBB222", "http://b", now, now.plusDays(2), 10, 0, Status.ACTIVE);

    repo.add(a);
    repo.add(b);

    assertTrue(repo.findByShortCode("AAA111").isPresent(), "AAA111 must be found.");
    assertEquals(1, repo.listByOwner("OWN-A").size(), "Owner A should see exactly one link.");
    assertEquals(1, repo.listByOwner("OWN-B").size(), "Owner B should see exactly one link.");

    // update: change clicks and status then flush
    a.clickCount = 3;
    a.status = Status.LIMIT_REACHED;
    repo.update(a);

    // delete by owner
    assertFalse(repo.deleteByShortCodeForOwner("AAA111", "OWN-B"), "Wrong owner must not delete.");
    assertTrue(repo.deleteByShortCodeForOwner("AAA111", "OWN-A"), "Correct owner must delete.");

    // persistence check: re-open repository reading the file
    LinksRepository repo2 = new LinksRepository();
    assertTrue(repo2.findByShortCode("AAA111").isEmpty(), "AAA111 must be removed.");
    assertTrue(repo2.findByShortCode("BBB222").isPresent(), "BBB222 must remain.");
  }

  @Test
  @DisplayName(
      "cleanupExpired(now,false): soft mark EXPIRED only for truly expired; DELETED ignored")
  void cleanupExpired_soft() throws Exception {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime past = now.minusHours(2);
    LocalDateTime future = now.plusHours(2);

    List<ShortLink> seed = new ArrayList<>();
    seed.add(
        link(
            "L-000001",
            "U",
            "E1",
            "http://e1",
            now.minusDays(1),
            past,
            null,
            0,
            Status.ACTIVE)); // expired
    seed.add(
        link(
            "L-000002",
            "U",
            "N1",
            "http://n1",
            now.minusDays(1),
            future,
            null,
            0,
            Status.ACTIVE)); // not expired
    seed.add(
        link(
            "L-000003",
            "U",
            "D1",
            "http://d1",
            now.minusDays(1),
            past,
            null,
            0,
            Status.DELETED)); // deleted
    writeLinks(seed);

    LinksRepository repo = new LinksRepository();
    int changed = repo.cleanupExpired(now, false);
    assertEquals(1, changed, "Exactly one ACTIVE expired must be marked.");

    // Verify state on file
    List<ShortLink> after = readLinks();
    ShortLink e1 = after.stream().filter(s -> s.shortCode.equals("E1")).findFirst().orElseThrow();
    ShortLink n1 = after.stream().filter(s -> s.shortCode.equals("N1")).findFirst().orElseThrow();
    ShortLink d1 = after.stream().filter(s -> s.shortCode.equals("D1")).findFirst().orElseThrow();

    assertEquals(Status.EXPIRED, e1.status, "Expired ACTIVE must be marked EXPIRED.");
    assertEquals(Status.ACTIVE, n1.status, "Non-expired stays ACTIVE.");
    assertEquals(Status.DELETED, d1.status, "Deleted remains untouched.");
  }

  @Test
  @DisplayName("cleanupExpired(now,true): hard delete removes expired entries")
  void cleanupExpired_hard() throws Exception {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime past = now.minusMinutes(10);

    List<ShortLink> seed = new ArrayList<>();
    seed.add(
        link("L-000010", "U", "E2", "http://e2", now.minusDays(1), past, null, 0, Status.ACTIVE));
    seed.add(
        link(
            "L-000011",
            "U",
            "E3",
            "http://e3",
            now.minusDays(1),
            past,
            null,
            0,
            Status.EXPIRED)); // already expired
    seed.add(
        link(
            "L-000012",
            "U",
            "N2",
            "http://n2",
            now.minusDays(1),
            now.plusDays(1),
            null,
            0,
            Status.ACTIVE));
    writeLinks(seed);

    LinksRepository repo = new LinksRepository();
    int removed = repo.cleanupExpired(now, true);
    assertEquals(2, removed, "Both expired (ACTIVE and already EXPIRED) must be removed.");

    // Verify only N2 remains
    List<ShortLink> after = readLinks();
    assertEquals(1, after.size(), "Only one entry should remain.");
    assertEquals("N2", after.get(0).shortCode);
  }

  @Test
  @DisplayName("cleanupLimitReached(false): soft mark LIMIT_REACHED; DELETED ignored")
  void cleanupLimitReached_soft() throws Exception {
    LocalDateTime now = LocalDateTime.now();

    List<ShortLink> seed = new ArrayList<>();
    seed.add(
        link(
            "L-000020",
            "U",
            "L1",
            "http://l1",
            now.minusDays(1),
            now.plusDays(1),
            2,
            3,
            Status.ACTIVE)); // over limit
    seed.add(
        link(
            "L-000021",
            "U",
            "L2",
            "http://l2",
            now.minusDays(1),
            now.plusDays(1),
            2,
            2,
            Status.ACTIVE)); // exactly limit -> reached
    seed.add(
        link(
            "L-000022",
            "U",
            "L3",
            "http://l3",
            now.minusDays(1),
            now.plusDays(1),
            5,
            1,
            Status.ACTIVE)); // below limit
    seed.add(
        link(
            "L-000023",
            "U",
            "LD",
            "http://ld",
            now.minusDays(1),
            now.plusDays(1),
            1,
            1,
            Status.DELETED)); // deleted
    writeLinks(seed);

    LinksRepository repo = new LinksRepository();
    int changed = repo.cleanupLimitReached(false);
    assertEquals(2, changed, "Two ACTIVE links must be marked LIMIT_REACHED.");

    List<ShortLink> after = readLinks();
    ShortLink l1 = after.stream().filter(s -> s.shortCode.equals("L1")).findFirst().orElseThrow();
    ShortLink l2 = after.stream().filter(s -> s.shortCode.equals("L2")).findFirst().orElseThrow();
    ShortLink l3 = after.stream().filter(s -> s.shortCode.equals("L3")).findFirst().orElseThrow();
    ShortLink ld = after.stream().filter(s -> s.shortCode.equals("LD")).findFirst().orElseThrow();

    assertEquals(Status.LIMIT_REACHED, l1.status);
    assertEquals(Status.LIMIT_REACHED, l2.status);
    assertEquals(Status.ACTIVE, l3.status);
    assertEquals(Status.DELETED, ld.status);
  }

  @Test
  @DisplayName(
      "cleanupLimitReached(true): hard delete removes any link with clickCount >= clickLimit")
  void cleanupLimitReached_hard() throws Exception {
    LocalDateTime now = LocalDateTime.now();

    List<ShortLink> seed = new ArrayList<>();
    seed.add(
        link(
            "L-000030",
            "U",
            "H1",
            "http://h1",
            now.minusDays(1),
            now.plusDays(1),
            2,
            2,
            Status.LIMIT_REACHED));
    seed.add(
        link(
            "L-000031",
            "U",
            "H2",
            "http://h2",
            now.minusDays(1),
            now.plusDays(1),
            2,
            5,
            Status.ACTIVE));
    seed.add(
        link(
            "L-000032",
            "U",
            "H3",
            "http://h3",
            now.minusDays(1),
            now.plusDays(1),
            5,
            1,
            Status.ACTIVE));
    writeLinks(seed);

    LinksRepository repo = new LinksRepository();
    int removed = repo.cleanupLimitReached(true);
    assertEquals(2, removed, "Two over-limit links must be removed.");

    List<ShortLink> after = readLinks();
    assertEquals(1, after.size());
    assertEquals("H3", after.get(0).shortCode);
  }

  @Test
  @DisplayName(
      "cleanupExpiredForOwner(now, owner, false/true): affects only the owner's expired links")
  void cleanupExpired_owner_scoped_soft_and_hard() throws Exception {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime past = now.minusHours(1);

    List<ShortLink> seed = new ArrayList<>();
    seed.add(
        link(
            "L-000040",
            "A",
            "AE",
            "http://ae",
            now.minusDays(1),
            past,
            null,
            0,
            Status.ACTIVE)); // A expired
    seed.add(
        link(
            "L-000041",
            "A",
            "AN",
            "http://an",
            now.minusDays(1),
            now.plusDays(1),
            null,
            0,
            Status.ACTIVE)); // A not expired
    seed.add(
        link(
            "L-000042",
            "B",
            "BE",
            "http://be",
            now.minusDays(1),
            past,
            null,
            0,
            Status.ACTIVE)); // B expired
    writeLinks(seed);

    LinksRepository repoSoft = new LinksRepository();
    int markedA = repoSoft.cleanupExpiredForOwner(now, "A", false);
    assertEquals(1, markedA, "Only A's expired entry should be marked.");

    List<ShortLink> afterSoft = readLinks();
    ShortLink ae =
        afterSoft.stream().filter(s -> s.shortCode.equals("AE")).findFirst().orElseThrow();
    ShortLink an =
        afterSoft.stream().filter(s -> s.shortCode.equals("AN")).findFirst().orElseThrow();
    ShortLink be =
        afterSoft.stream().filter(s -> s.shortCode.equals("BE")).findFirst().orElseThrow();
    assertEquals(Status.EXPIRED, ae.status);
    assertEquals(Status.ACTIVE, an.status);
    assertEquals(Status.ACTIVE, be.status, "Owner B's entry is untouched in soft A cleanup.");

    // Now hard delete for owner B
    LinksRepository repoHard = new LinksRepository();
    int removedB = repoHard.cleanupExpiredForOwner(now, "B", true);
    assertEquals(1, removedB, "Only B's expired should be removed.");

    List<ShortLink> afterHard = readLinks();
    assertTrue(
        afterHard.stream().noneMatch(s -> "BE".equals(s.shortCode)), "B's expired must be gone.");
  }

  @Test
  @DisplayName(
      "cleanupLimitReachedForOwner(owner, false/true): affects only owner's over-limit links")
  void cleanupLimitReached_owner_scoped_soft_and_hard() throws Exception {
    LocalDateTime now = LocalDateTime.now();

    List<ShortLink> seed = new ArrayList<>();
    seed.add(
        link(
            "L-000050",
            "A",
            "AL1",
            "http://al1",
            now.minusDays(1),
            now.plusDays(1),
            1,
            1,
            Status.ACTIVE)); // A at limit
    seed.add(
        link(
            "L-000051",
            "A",
            "AL2",
            "http://al2",
            now.minusDays(1),
            now.plusDays(1),
            2,
            3,
            Status.ACTIVE)); // A over
    seed.add(
        link(
            "L-000052",
            "B",
            "BL1",
            "http://bl1",
            now.minusDays(1),
            now.plusDays(1),
            1,
            1,
            Status.ACTIVE)); // B at limit
    seed.add(
        link(
            "L-000053",
            "B",
            "BL2",
            "http://bl2",
            now.minusDays(1),
            now.plusDays(1),
            5,
            1,
            Status.ACTIVE)); // B under
    writeLinks(seed);

    LinksRepository repoSoft = new LinksRepository();
    int markedA = repoSoft.cleanupLimitReachedForOwner("A", false);
    assertEquals(2, markedA, "Two A links must be marked LIMIT_REACHED.");

    List<ShortLink> afterSoft = readLinks();
    ShortLink al1 =
        afterSoft.stream().filter(s -> s.shortCode.equals("AL1")).findFirst().orElseThrow();
    ShortLink al2 =
        afterSoft.stream().filter(s -> s.shortCode.equals("AL2")).findFirst().orElseThrow();
    ShortLink bl1 =
        afterSoft.stream().filter(s -> s.shortCode.equals("BL1")).findFirst().orElseThrow();
    ShortLink bl2 =
        afterSoft.stream().filter(s -> s.shortCode.equals("BL2")).findFirst().orElseThrow();

    assertEquals(Status.LIMIT_REACHED, al1.status);
    assertEquals(Status.LIMIT_REACHED, al2.status);
    assertEquals(Status.ACTIVE, bl1.status, "B's entries are untouched by A's cleanup.");
    assertEquals(Status.ACTIVE, bl2.status);

    // Now hard delete for owner B
    LinksRepository repoHard = new LinksRepository();
    int removedB = repoHard.cleanupLimitReachedForOwner("B", true);
    assertEquals(1, removedB, "Only B's at/over limit entry should be removed.");

    List<ShortLink> afterHard = readLinks();
    assertFalse(
        afterHard.stream().anyMatch(s -> "BL1".equals(s.shortCode)), "BL1 must be removed.");
    assertTrue(
        afterHard.stream().anyMatch(s -> "AL1".equals(s.shortCode)),
        "A's entries remain after B cleanup.");
  }
}
