package org.example.shortlinkapp.service;

import com.google.gson.Gson;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.example.shortlinkapp.model.ShortLink;
import org.example.shortlinkapp.model.Status;
import org.example.shortlinkapp.storage.ConfigJson;
import org.example.shortlinkapp.storage.DataPaths;
import org.example.shortlinkapp.storage.LinksRepository;
import org.example.shortlinkapp.storage.UsersRepository;
import org.example.shortlinkapp.util.JsonUtils;
import org.example.shortlinkapp.util.TimeUtils;
import org.example.shortlinkapp.util.UrlValidator;

/**
 * Application service that encapsulates all business logic for short-link lifecycle:
 * creation, opening (redirect), deletion, limit editing, maintenance (cleanup),
 * statistics, validation, and export.
 *
 * <p>State is scoped to a current {@code ownerUuid}. The service talks to the
 * {@link LinksRepository} as its persistence layer and can optionally emit domain
 * events via {@link EventService}. It also respects runtime behavior switches from
 * {@link ConfigJson} (e.g., TTL, default click limit, auto-cleanup).
 *
 * <p><strong>Thread-safety:</strong> this class is not thread-safe. A separate instance
 * should be used per interactive session/CLI menu.
 */
public class ShortLinkService {

    private String ownerUuid;
    private ConfigJson cfg;
    private final LinksRepository repo;
    private final EventService events;
    private final Random rnd = new Random();

    /**
     * Creates a service instance bound to a specific owner.
     *
     * @param ownerUuid current user's UUID; used to scope queries and ownership checks
     * @param cfg runtime configuration (TTL, base URL, code length, cleanup flags, etc.)
     * @param events optional event sink; if non-null, a new {@link EventService} is constructed
     *               using its enabled flag (to avoid sharing internal state across services)
     */
    public ShortLinkService(String ownerUuid, ConfigJson cfg, EventService events) {
        this.ownerUuid = ownerUuid;
        this.cfg = cfg;
        this.events =
                (events == null) ? null : new EventService(events.isEnabled()); // fixing spotbugs error
        this.repo = new LinksRepository();
    }

    // ---------- Queries ----------

    /**
     * Lists links owned by the current user. If {@code cleanupOnEachOp} is enabled,
     * performs automatic maintenance before the read.
     *
     * @return immutable snapshot of owner's links
     */
    public List<ShortLink> listMyLinks() {
        autoCleanupIfEnabled();
        return repo.listByOwner(ownerUuid);
    }

    /**
     * Finds a link by its short code.
     *
     * @param shortCode code to resolve
     * @return optional with the link or empty if not found
     */
    public Optional<ShortLink> findByShortCode(String shortCode) {
        return repo.findByShortCode(shortCode);
    }

    // ---------- Create ----------

    /**
     * Creates a new short link owned by the current user.
     *
     * <ul>
     *   <li>Validates URL via {@link UrlValidator} and {@code cfg.maxUrlLength}.</li>
     *   <li>Derives click limit from {@code limitOverride} or {@code cfg.defaultClickLimit};
     *       {@code null} means unlimited.</li>
     *   <li>Generates a unique short code of {@code cfg.shortCodeLength} using Base62.</li>
     *   <li>Assigns TTL via {@code cfg.defaultTtlHours}.</li>
     *   <li>Persists the entity and emits an INFO event when events are enabled.</li>
     * </ul>
     *
     * @param longUrl target URL (http/https with host)
     * @param limitOverride optional explicit click limit; {@code null} to use defaults
     * @return persisted short link
     * @throws IllegalArgumentException if URL is invalid or limit is non-positive
     */
    public ShortLink createShortLink(String longUrl, Integer limitOverride) {
        autoCleanupIfEnabled();
        if (!UrlValidator.isValidHttpUrl(longUrl, cfg.maxUrlLength)) {
            throw new IllegalArgumentException("Invalid URL. Only http/https with host are allowed.");
        }

        int limit =
                (limitOverride != null)
                        ? limitOverride
                        : (cfg.defaultClickLimit != null ? cfg.defaultClickLimit : Integer.MAX_VALUE);

        if (limit <= 0) {
            throw new IllegalArgumentException("Click limit must be positive or empty for default.");
        }

        LocalDateTime now = LocalDateTime.now();
        ShortLink l = new ShortLink();
        l.id = repo.nextId();
        l.ownerUuid = ownerUuid;
        l.longUrl = longUrl.trim();
        l.shortCode = generateUniqueCode();
        l.createdAt = now;
        l.expiresAt = now.plusHours(cfg.defaultTtlHours);
        // if defaultClickLimit == null and limitOverride not defined — unlimited
        l.clickLimit = (cfg.defaultClickLimit == null && limitOverride == null) ? null : limit;
        l.clickCount = 0;
        l.lastAccessAt = null;
        l.status = Status.ACTIVE;

        repo.add(l);
        if (events != null)
            events.info(l.ownerUuid, l.shortCode, "CREATE " + cfg.baseUrl + l.shortCode);
        return l;
    }

    /**
     * Generates a unique Base62 short code of length {@code cfg.shortCodeLength}.
     * The method retries until a free code is found (based on repository lookup).
     *
     * @return unique short code
     */
    private String generateUniqueCode() {
        while (true) {
            String code = randomBase62(cfg.shortCodeLength);
            if (repo.findByShortCode(code).isEmpty()) return code;
        }
    }

    private static final char[] B62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    /**
     * Produces a random Base62 string of the requested length.
     *
     * @param len number of characters
     * @return random Base62 string (not guaranteed to be unique)
     */
    private String randomBase62(int len) {
        char[] c = new char[len];
        for (int i = 0; i < len; i++) c[i] = B62[rnd.nextInt(B62.length)];
        return new String(c);
    }

    // ---------- Open (redirect) ----------

    /**
     * Attempts to open a short link in the user's default browser.
     *
     * <ul>
     *   <li>Normalizes input (strips {@code cfg.baseUrl} prefix if present).</li>
     *   <li>Checks TTL; expired links are marked {@link Status#EXPIRED} and blocked.</li>
     *   <li>Checks click limits; when reached, marks {@link Status#LIMIT_REACHED} and blocks.</li>
     *   <li>Increments click counter on success and updates {@code lastAccessAt}.</li>
     *   <li>Emits corresponding events when event logging is enabled.</li>
     * </ul>
     *
     * <p>In environments without {@link Desktop} support, prints the URL for manual opening.
     *
     * @param rawCode short code or full {@code baseUrl + code}
     */
    public void openShortLink(String rawCode) {
        autoCleanupIfEnabled();
        String code = normalizeCode(rawCode);
        Optional<ShortLink> opt = repo.findByShortCode(code);
        if (opt.isEmpty()) {
            System.out.println("Link not found: " + code);
            return;
        }
        ShortLink l = opt.get();

        LocalDateTime now = LocalDateTime.now();

        // TTL check
        if (l.status != Status.DELETED && TimeUtils.isExpired(now, l.expiresAt)) {
            l.status = Status.EXPIRED;
            repo.update(l);
            events.expired(l.ownerUuid, l.shortCode, "Link expired at " + l.expiresAt);
            System.out.println("Link " + cfg.baseUrl + l.shortCode + " expired at " + l.expiresAt + ".");
            return;
        }
        if (l.status == Status.DELETED) {
            System.out.println("Link was deleted by owner.");
            return;
        }

        // Limit check before open
        if (l.clickLimit != null && l.clickCount >= l.clickLimit) {
            l.status = Status.LIMIT_REACHED;
            repo.update(l);
            events.limitReached(
                    l.ownerUuid,
                    l.shortCode,
                    "Click limit reached (" + l.clickCount + "/" + l.clickLimit + ")");
            System.out.println(
                    "Click limit reached (" + l.clickCount + "/" + l.clickLimit + "). Link is blocked.");
            return;
        }

        // Proceed to open
        l.clickCount += 1;
        l.lastAccessAt = now;
        if (l.clickLimit != null && l.clickCount >= l.clickLimit) {
            l.status = Status.LIMIT_REACHED; // reached after this click
        } else {
            l.status = Status.ACTIVE;
        }
        repo.update(l);

        String url = l.longUrl;
        if (Desktop.isDesktopSupported()) {
            try {
                if (events != null) {
                    String lim = (l.clickLimit == null) ? "∞" : String.valueOf(l.clickLimit);
                    events.info(l.ownerUuid, l.shortCode, "OPEN " + l.clickCount + "/" + lim);
                }
                URI uri = new URI(url); // may throw URISyntaxException
                Desktop.getDesktop().browse(uri); // may throw IOException / SecurityException
                System.out.println("Opening in browser: " + url);
            } catch (URISyntaxException | IOException | SecurityException e) {
                System.out.println("Cannot open browser automatically. Copy and open manually: " + url);
            }
        } else {
            System.out.println("Copy and open manually: " + url);
        }
    }

    /**
     * Deletes a link by short code if the current user is the owner.
     *
     * @param rawCode short code or full {@code baseUrl + code}
     * @return {@code true} if a record was deleted; {@code false} otherwise
     */
    public boolean deleteLink(String rawCode) {
        String code = normalizeCode(rawCode);
        var opt = repo.findByShortCode(code);
        if (opt.isEmpty()) {
            System.out.println("Link not found: " + code);
            return false;
        }
        var l = opt.get();
        if (!ownerUuid.equals(l.ownerUuid)) {
            System.out.println("Operation allowed for the owner only.");
            return false;
        }
        boolean ok = repo.deleteByShortCodeForOwner(code, ownerUuid);
        if (ok) {
            System.out.println("Link deleted: " + cfg.baseUrl + code);
            if (events != null) {
                events.info(ownerUuid, code, "DELETE " + cfg.baseUrl + code);
            }
        } else {
            System.out.println("Delete failed (race or not found).");
        }
        return ok;
    }

    /**
     * Edits the click limit of a link (owner-only). Behavior is controlled by
     * {@code cfg.allowOwnerEditLimit}. {@code null} means "unlimited".
     *
     * @param rawCode short code or full {@code baseUrl + code}
     * @param newLimit new positive limit, or {@code null} for unlimited
     * @return {@code true} if updated; {@code false} if disabled/invalid/not owned/not found
     */
    public boolean editClickLimit(String rawCode, Integer newLimit) {
        // the main rule: all changes to the limit are allowed only if it is enabled in config
        if (!cfg.allowOwnerEditLimit) {
            System.out.println("Editing click limit is disabled by configuration.");
            return false;
        }

        String code;
        code = normalizeCode(rawCode);
        var opt = repo.findByShortCode(code);
        if (opt.isEmpty()) {
            System.out.println("Link not found: " + code);
            return false;
        }
        var l = opt.get();

        if (!ownerUuid.equals(l.ownerUuid)) {
            System.out.println("Operation allowed for the owner only.");
            return false;
        }

        // 'unlimited' → newLimit == null
        if (newLimit == null) {
            l.clickLimit = null;
        } else {
            if (newLimit <= 0) {
                System.out.println("New limit must be positive.");
                return false;
            }
            if (newLimit < l.clickCount) {
                System.out.println("New limit must be >= current clicks (" + l.clickCount + ").");
                return false;
            }
            l.clickLimit = newLimit;
        }

        // recalculate status
        var now = java.time.LocalDateTime.now();
        if (l.expiresAt != null
                && org.example.shortlinkapp.util.TimeUtils.isExpired(now, l.expiresAt)) {
            l.status = org.example.shortlinkapp.model.Status.EXPIRED;
        } else if (l.clickLimit != null && l.clickCount >= l.clickLimit) {
            l.status = org.example.shortlinkapp.model.Status.LIMIT_REACHED;
        } else {
            l.status = org.example.shortlinkapp.model.Status.ACTIVE;
        }

        repo.update(l);
        if (events != null) {
            String val = (l.clickLimit == null) ? "unlimited" : String.valueOf(l.clickLimit);
            events.info(l.ownerUuid, l.shortCode, "EDIT_LIMIT " + val);
        }
        System.out.println(
                "Limit for "
                        + cfg.baseUrl
                        + l.shortCode
                        + " set to "
                        + (l.clickLimit == null ? "unlimited" : l.clickLimit)
                        + ".");
        return true;
    }

    /**
     * Cleans up expired links (global scope) and prints a summary line.
     *
     * @return number of affected records
     */
    public int cleanupExpired() {
        int n = cleanupExpiredAndLog(false);
        System.out.println("Expired cleaned: " + n);
        return n;
    }

    /**
     * Cleans up links that reached the click limit (global scope) and prints a summary line.
     *
     * @return number of affected records
     */
    public int cleanupLimitReached() {
        int n = cleanupLimitReachedAndLog(false);
        System.out.println("Limit-reached cleaned: " + n);
        return n;
    }

    /**
     * Exports all links of the current user to {@code data/export_<uuid>_<timestamp>.json}.
     * Respects {@code cleanupOnEachOp} and emits an INFO event when enabled.
     *
     * @return output file path on success; {@code null} on failure
     */
    public Path exportMyLinks() {
        autoCleanupIfEnabled();
        try {
            var mine = repo.listByOwner(ownerUuid);
            Files.createDirectories(DataPaths.DATA_DIR);

            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path out = DataPaths.DATA_DIR.resolve("export_" + ownerUuid + "_" + ts + ".json");

            Gson gson = JsonUtils.gson();
            try (var bw =
                         Files.newBufferedWriter(
                                 out,
                                 StandardCharsets.UTF_8,
                                 StandardOpenOption.CREATE,
                                 StandardOpenOption.TRUNCATE_EXISTING,
                                 StandardOpenOption.WRITE)) {
                gson.toJson(mine, bw);
            }

            // logging event
            if (events != null) {
                events.info(ownerUuid, "-", "EXPORT " + mine.size());
            }

            System.out.println("Exported " + mine.size() + " link(s) to: " + out.toAbsolutePath());

            return out;
        } catch (Exception e) {
            System.out.println("Export failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Switches the owner context. Future operations (queries/mutations) apply to the new user.
     *
     * @param newUuid target UUID; ignored when null/blank
     */
    public void switchOwner(String newUuid) {
        if (newUuid == null || newUuid.isBlank()) return;
        this.ownerUuid = newUuid;
    }

    // --- Stats DTO ---

    /**
     * Aggregated statistics DTO used by the CLI to render counters and "top by clicks".
     */
    public static class Stats {
        /** Total number of links in the scope. */
        public int total;
        /** Number of {@link Status#ACTIVE} links. */
        public int active;
        /** Number of {@link Status#EXPIRED} links. */
        public int expired;
        /** Number of {@link Status#LIMIT_REACHED} links. */
        public int limitReached;
        /** Number of {@link Status#DELETED} links. */
        public int deleted;
        /** Sum of {@code clickCount} across the scope. */
        public int totalClicks;
        /** Top-N links by clicks, descending. */
        public java.util.List<ShortLink> topByClicks = new java.util.ArrayList<>();
    }

    /**
     * Computes statistics for the current owner.
     *
     * @param topN number of top items to include (clamped to list size)
     * @return stats snapshot
     */
    public Stats statsMine(int topN) {
        autoCleanupIfEnabled();
        var list = repo.listByOwner(ownerUuid);
        return computeStats(list, topN);
    }

    /**
     * Computes global statistics across all links.
     *
     * @param topN number of top items to include (clamped to list size)
     * @return stats snapshot
     */
    public Stats statsGlobal(int topN) {
        var list = repo.listAll();
        return computeStats(list, topN);
    }

    /**
     * Internal reducer for stats computation: counts statuses, sums clicks,
     * and selects top-N by {@code clickCount} descending.
     *
     * @param list input scope
     * @param topN top limit
     * @return populated {@link Stats}
     */
    private Stats computeStats(java.util.List<ShortLink> list, int topN) {
        Stats s = new Stats();
        s.total = list.size();
        for (var l : list) {
            if (l == null) continue;
            s.totalClicks += l.clickCount;
            if (l.status == null) continue;
            switch (l.status) {
                case ACTIVE -> s.active++;
                case EXPIRED -> s.expired++;
                case LIMIT_REACHED -> s.limitReached++;
                case DELETED -> s.deleted++;
            }
        }
        // top by clicks desc
        var sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingInt((ShortLink l) -> l.clickCount).reversed());
        int k = Math.max(0, Math.min(topN, sorted.size()));
        for (int i = 0; i < k; i++) s.topByClicks.add(sorted.get(i));
        return s;
    }

    /**
     * Owner-scoped cleanup of expired links with event logging and a summary line.
     *
     * @return number of affected records
     */
    public int bulkDeleteExpiredMine() {
        int n = cleanupExpiredAndLog(true);
        System.out.println("Mine: expired cleaned: " + n);
        return n;
    }

    /**
     * Owner-scoped cleanup of limit-reached links with event logging and a summary line.
     *
     * @return number of affected records
     */
    public int bulkDeleteLimitReachedMine() {
        int n = cleanupLimitReachedAndLog(true);
        System.out.println("Mine: limit-reached cleaned: " + n);
        return n;
    }

    // --- Validation DTO ---

    /**
     * Validation report DTO for JSON integrity checks.
     */
    public static class ValidationReport {
        /** Total number of links scanned. */
        public int totalLinks;
        /** Number of detected issues. */
        public int issues;
        /** Human-readable list of findings. */
        public java.util.List<String> messages = new java.util.ArrayList<>();
    }

    /**
     * Performs a consistency check over all stored links:
     * presence/uniqueness of short codes, known owners, URL validity (superficial),
     * chronological consistency of timestamps, and counters/limits coherence.
     *
     * @return validation report with totals and messages
     */
    public ValidationReport validateJson() {
        ValidationReport r = new ValidationReport();

        var usersRepo = new UsersRepository();
        var knownUsers = new java.util.HashSet<String>();
        for (var u : usersRepo.list()) {
            if (u != null && u.uuid != null) knownUsers.add(u.uuid);
        }

        var all = repo.listAll();
        r.totalLinks = all.size();

        var seenCodes = new HashSet<String>();

        for (var l : all) {
            if (l == null) continue;

            // 1) shortCode presence + uniqueness
            if (l.shortCode == null || l.shortCode.isBlank()) {
                r.issues++;
                r.messages.add("Missing shortCode for id=" + l.id);
            } else {
                if (!seenCodes.add(l.shortCode)) {
                    r.issues++;
                    r.messages.add("Duplicate shortCode: " + l.shortCode + " (id=" + l.id + ")");
                }
            }

            // 2) owner exists
            if (l.ownerUuid == null || l.ownerUuid.isBlank() || !knownUsers.contains(l.ownerUuid)) {
                r.issues++;
                r.messages.add(
                        "Orphan link: id=" + l.id + " shortCode=" + l.shortCode + " ownerUuid missing/unknown");
            }

            // 3) URL validity (superficial)
            if (!org.example.shortlinkapp.util.UrlValidator.isValidHttpUrl(l.longUrl, cfg.maxUrlLength)) {
                r.issues++;
                r.messages.add("Invalid URL for shortCode=" + safe(l.shortCode) + ": " + l.longUrl);
            }

            // 4) dates
            if (l.createdAt == null) {
                r.issues++;
                r.messages.add("createdAt is null (shortCode=" + safe(l.shortCode) + ")");
            }
            if (l.expiresAt == null) {
                r.issues++;
                r.messages.add("expiresAt is null (shortCode=" + safe(l.shortCode) + ")");
            } else if (l.createdAt != null && l.expiresAt.isBefore(l.createdAt)) {
                r.issues++;
                r.messages.add("expiresAt < createdAt (shortCode=" + safe(l.shortCode) + ")");
            }

            // 5) limits and counters
            if (l.clickCount < 0) {
                r.issues++;
                r.messages.add("clickCount < 0 (shortCode=" + safe(l.shortCode) + ")");
            }
            if (l.clickLimit != null && l.clickLimit <= 0) {
                r.issues++;
                r.messages.add("clickLimit <= 0 (shortCode=" + safe(l.shortCode) + ")");
            }
            if (l.clickLimit != null
                    && l.clickCount > l.clickLimit
                    && l.status != org.example.shortlinkapp.model.Status.LIMIT_REACHED) {
                r.issues++;
                r.messages.add(
                        "clickCount > clickLimit but status != LIMIT_REACHED (shortCode="
                                + safe(l.shortCode)
                                + ")");
            }
        }

        return r;
    }

    /**
     * Null-safe helper for short code rendering in messages.
     *
     * @param s input short code (nullable)
     * @return {@code "-"} if null; otherwise the input
     */
    private static String safe(String s) {
        return s == null ? "-" : s;
    }

    // ---------- Helpers ----------

    /**
     * Normalizes a user-entered code by stripping the configured {@code baseUrl} prefix if present.
     *
     * @param input raw short code or {@code baseUrl + code}
     * @return normalized short code
     */
    private String normalizeCode(String input) {
        String s = input == null ? "" : input.trim();
        String prefix = cfg.baseUrl;
        if (prefix != null && !prefix.isBlank() && s.startsWith(prefix)) {
            return s.substring(prefix.length());
        }
        return s;
    }

    /**
     * Performs automatic maintenance (expired and limit-reached cleanup) if
     * {@code cfg.cleanupOnEachOp} is enabled. No-ops otherwise.
     */
    private void autoCleanupIfEnabled() {
        if (!cfg.cleanupOnEachOp) return;
        cleanupExpiredAndLog(false);
        cleanupLimitReachedAndLog(false);
    }

    /**
     * Replaces the current configuration at runtime.
     *
     * @param newCfg new configuration; ignored when null
     */
    public void reloadConfig(ConfigJson newCfg) {
        if (newCfg != null) this.cfg = newCfg;
    }

    /**
     * Internal routine that cleans up expired links (global or owner-only) and
     * emits events. When {@code cfg.hardDeleteExpired} is {@code true}, the links
     * are removed; otherwise status is set to {@link Status#EXPIRED}.
     *
     * @param onlyMine if {@code true}, restricts the scope to the current owner
     * @return number of affected records
     */
    private int cleanupExpiredAndLog(boolean onlyMine) {
        var now = java.time.LocalDateTime.now();
        int count = 0;
        var scope = onlyMine ? repo.listByOwner(ownerUuid) : repo.listAll();

        // it is not required to create a copy, but this is safe, if we will delete it
        for (var l : new java.util.ArrayList<>(scope)) {
            if (l == null || l.status == org.example.shortlinkapp.model.Status.DELETED) continue;
            if (l.expiresAt != null
                    && org.example.shortlinkapp.util.TimeUtils.isExpired(now, l.expiresAt)) {
                if (cfg.hardDeleteExpired) {
                    // deleting entry and logging the EXPIRED event
                    if (repo.deleteByShortCodeForOwner(l.shortCode, l.ownerUuid)) {
                        count++;
                        if (events != null)
                            events.expired(l.ownerUuid, l.shortCode, "Auto-cleanup: expired at " + l.expiresAt);
                    }
                } else {
                    if (l.status != org.example.shortlinkapp.model.Status.EXPIRED) {
                        l.status = org.example.shortlinkapp.model.Status.EXPIRED;
                        repo.update(l);
                        count++;
                        if (events != null)
                            events.expired(l.ownerUuid, l.shortCode, "Auto-cleanup: expired at " + l.expiresAt);
                    }
                }
            }
        }
        return count;
    }

    /**
     * Internal routine that cleans up links that reached their click limit (global or owner-only)
     * and emits events. Uses the same hard-delete flag as expired cleanup.
     *
     * @param onlyMine if {@code true}, restricts the scope to the current owner
     * @return number of affected records
     */
    private int cleanupLimitReachedAndLog(boolean onlyMine) {
        int count = 0;
        var scope = onlyMine ? repo.listByOwner(ownerUuid) : repo.listAll();

        for (var l : scope) {
            if (l == null || l.status == org.example.shortlinkapp.model.Status.DELETED) continue;
            if (l.clickLimit != null && l.clickCount >= l.clickLimit) {
                if (cfg.hardDeleteExpired) { // using the same flag for cleanup
                    if (repo.deleteByShortCodeForOwner(l.shortCode, l.ownerUuid)) {
                        count++;
                        if (events != null)
                            events.limitReached(
                                    l.ownerUuid,
                                    l.shortCode,
                                    "Auto-cleanup: limit reached " + l.clickCount + "/" + l.clickLimit);
                    }
                } else {
                    if (l.status != org.example.shortlinkapp.model.Status.LIMIT_REACHED) {
                        l.status = org.example.shortlinkapp.model.Status.LIMIT_REACHED;
                        repo.update(l);
                        count++;
                        if (events != null)
                            events.limitReached(
                                    l.ownerUuid,
                                    l.shortCode,
                                    "Auto-cleanup: limit reached " + l.clickCount + "/" + l.clickLimit);
                    }
                }
            }
        }
        return count;
    }
}
