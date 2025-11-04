package org.example.shortlinkapp.service;

import com.google.gson.Gson;
import org.example.shortlinkapp.model.ShortLink;
import org.example.shortlinkapp.model.Status;
import org.example.shortlinkapp.storage.ConfigJson;
import org.example.shortlinkapp.storage.DataPaths;
import org.example.shortlinkapp.storage.LinksRepository;
import org.example.shortlinkapp.storage.UsersRepository;
import org.example.shortlinkapp.util.JsonUtils;
import org.example.shortlinkapp.util.TimeUtils;
import org.example.shortlinkapp.util.UrlValidator;

import java.awt.Desktop;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ShortLinkService {

    private String ownerUuid;
    private ConfigJson cfg;
    private final LinksRepository repo;
    private final EventService events;
    private final Random rnd = new Random();

    public ShortLinkService(String ownerUuid, ConfigJson cfg, EventService events) {
        this.ownerUuid = ownerUuid;
        this.cfg = cfg;
        this.events = events;
        this.repo = new LinksRepository();
    }

    // ---------- Queries ----------
    public List<ShortLink> listMyLinks() {
        autoCleanupIfEnabled();
        return repo.listByOwner(ownerUuid);
    }

    public Optional<ShortLink> findByShortCode(String shortCode) {
        return repo.findByShortCode(shortCode);
    }

    // ---------- Create ----------
    public ShortLink createShortLink(String longUrl, Integer limitOverride) {
        autoCleanupIfEnabled();
        if (!UrlValidator.isValidHttpUrl(longUrl, cfg.maxUrlLength)) {
            throw new IllegalArgumentException("Invalid URL. Only http/https with host are allowed.");
        }

        int limit = (limitOverride != null)
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
        // если defaultClickLimit == null и limitOverride не задан — безлимит
        l.clickLimit = (cfg.defaultClickLimit == null && limitOverride == null) ? null : limit;
        l.clickCount = 0;
        l.lastAccessAt = null;
        l.status = Status.ACTIVE;

        repo.add(l);
        return l;
    }

    private String generateUniqueCode() {
        while (true) {
            String code = randomBase62(cfg.shortCodeLength);
            if (repo.findByShortCode(code).isEmpty()) return code;
        }
    }

    private static final char[] B62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private String randomBase62(int len) {
        char[] c = new char[len];
        for (int i = 0; i < len; i++) c[i] = B62[rnd.nextInt(B62.length)];
        return new String(c);
    }

    // ---------- Open (redirect) ----------
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
            events.limitReached(l.ownerUuid, l.shortCode,
                    "Click limit reached (" + l.clickCount + "/" + l.clickLimit + ")");
            System.out.println("Click limit reached (" + l.clickCount + "/" + l.clickLimit + "). Link is blocked.");
            return;
        }

        // Proceed to open
        l.clickCount += 1;
        l.lastAccessAt = now;
        if (l.clickLimit != null && l.clickCount >= l.clickLimit) {
            l.status = Status.LIMIT_REACHED; // достигнут после этого клика
        } else {
            l.status = Status.ACTIVE;
        }
        repo.update(l);

        String url = l.longUrl;
        if (Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().browse(new URI(url));
                System.out.println("Opening in browser: " + url);
            } catch (Exception e) {
                System.out.println("Cannot open browser automatically. Copy and open manually: " + url);
            }
        } else {
            System.out.println("Copy and open manually: " + url);
        }
    }

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
        } else {
            System.out.println("Delete failed (race or not found).");
        }
        return ok;
    }

    public boolean editClickLimit(String rawCode, Integer newLimit) {
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

        // 'unlimited' → newLimit == null
        if (newLimit == null) {
            if (!cfg.allowOwnerEditLimit) {
                System.out.println("Editing click limit is disabled by configuration.");
                return false;
            }
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

        // Пересчитать статус (если ранее был LIMIT_REACHED, а новый лимит больше — вернуть ACTIVE, если не истёк TTL)
        var now = java.time.LocalDateTime.now();
        if (l.expiresAt != null && org.example.shortlinkapp.util.TimeUtils.isExpired(now, l.expiresAt)) {
            l.status = Status.EXPIRED;
        } else if (l.clickLimit != null && l.clickCount >= l.clickLimit) {
            l.status = Status.LIMIT_REACHED;
        } else {
            l.status = Status.ACTIVE;
        }

        repo.update(l);
        System.out.println("Limit for " + cfg.baseUrl + l.shortCode + " set to "
                + (l.clickLimit == null ? "unlimited" : l.clickLimit) + ".");
        return true;
    }
    public int cleanupExpired() {
        int n = repo.cleanupExpired(LocalDateTime.now(), cfg.hardDeleteExpired);
        System.out.println("Expired cleaned: " + n);
        return n;
    }
    public int cleanupLimitReached() {
        int n = repo.cleanupLimitReached(cfg.hardDeleteExpired);
        System.out.println("Limit-reached cleaned: " + n);
        return n;
    }
    public Path exportMyLinks() {
        autoCleanupIfEnabled();
        try {
            var mine = repo.listByOwner(ownerUuid);
            Files.createDirectories(DataPaths.DATA_DIR);

            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path out = DataPaths.DATA_DIR.resolve("export_" + ownerUuid + "_" + ts + ".json");

            Gson gson = JsonUtils.gson();
            try (var bw = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                gson.toJson(mine, bw);
            }

            System.out.println("Exported " + mine.size() + " link(s) to: " + out.toAbsolutePath());
            return out;
        } catch (Exception e) {
            System.out.println("Export failed: " + e.getMessage());
            return null;
        }
    }

    public void switchOwner(String newUuid) {
        if (newUuid == null || newUuid.isBlank()) return;
        this.ownerUuid = newUuid;
    }

    // --- Stats DTO ---
    public static class Stats {
        public int total;
        public int active;
        public int expired;
        public int limitReached;
        public int deleted;
        public int totalClicks;
        public java.util.List<ShortLink> topByClicks = new java.util.ArrayList<>();
    }

    public Stats statsMine(int topN) {
        autoCleanupIfEnabled();
        var list = repo.listByOwner(ownerUuid);
        return computeStats(list, topN);
    }

    public Stats statsGlobal(int topN) {
        var list = repo.listAll();
        return computeStats(list, topN);
    }

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

    public int bulkDeleteExpiredMine() {
        int n = repo.cleanupExpiredForOwner(java.time.LocalDateTime.now(), ownerUuid, cfg.hardDeleteExpired);
        System.out.println("Mine: expired cleaned: " + n);
        return n;
    }

    public int bulkDeleteLimitReachedMine() {
        int n = repo.cleanupLimitReachedForOwner(ownerUuid, cfg.hardDeleteExpired);
        System.out.println("Mine: limit-reached cleaned: " + n);
        return n;
    }

    // --- Validation DTO ---
    public static class ValidationReport {
        public int totalLinks;
        public int issues;
        public java.util.List<String> messages = new java.util.ArrayList<>();
    }

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
                r.issues++; r.messages.add("Missing shortCode for id=" + l.id);
            } else {
                if (!seenCodes.add(l.shortCode)) {
                    r.issues++; r.messages.add("Duplicate shortCode: " + l.shortCode + " (id=" + l.id + ")");
                }
            }

            // 2) owner exists
            if (l.ownerUuid == null || l.ownerUuid.isBlank() || !knownUsers.contains(l.ownerUuid)) {
                r.issues++; r.messages.add("Orphan link: id=" + l.id + " shortCode=" + l.shortCode + " ownerUuid missing/unknown");
            }

            // 3) URL validity (поверхностно)
            if (!org.example.shortlinkapp.util.UrlValidator.isValidHttpUrl(l.longUrl, cfg.maxUrlLength)) {
                r.issues++; r.messages.add("Invalid URL for shortCode=" + safe(l.shortCode) + ": " + l.longUrl);
            }

            // 4) dates
            if (l.createdAt == null) {
                r.issues++; r.messages.add("createdAt is null (shortCode=" + safe(l.shortCode) + ")");
            }
            if (l.expiresAt == null) {
                r.issues++; r.messages.add("expiresAt is null (shortCode=" + safe(l.shortCode) + ")");
            } else if (l.createdAt != null && l.expiresAt.isBefore(l.createdAt)) {
                r.issues++; r.messages.add("expiresAt < createdAt (shortCode=" + safe(l.shortCode) + ")");
            }

            // 5) limits and counters
            if (l.clickCount < 0) {
                r.issues++; r.messages.add("clickCount < 0 (shortCode=" + safe(l.shortCode) + ")");
            }
            if (l.clickLimit != null && l.clickLimit <= 0) {
                r.issues++; r.messages.add("clickLimit <= 0 (shortCode=" + safe(l.shortCode) + ")");
            }
            if (l.clickLimit != null && l.clickCount > l.clickLimit
                    && l.status != org.example.shortlinkapp.model.Status.LIMIT_REACHED) {
                r.issues++; r.messages.add("clickCount > clickLimit but status != LIMIT_REACHED (shortCode=" + safe(l.shortCode) + ")");
            }
        }

        return r;
    }

    private static String safe(String s) { return s == null ? "-" : s; }



    // ---------- Helpers ----------
    private String normalizeCode(String input) {
        String s = input == null ? "" : input.trim();
        String prefix = cfg.baseUrl;
        if (prefix != null && !prefix.isBlank() && s.startsWith(prefix)) {
            return s.substring(prefix.length());
        }
        return s;
    }
    private void autoCleanupIfEnabled() {
        if (!cfg.cleanupOnEachOp) return;
        var now = java.time.LocalDateTime.now();
        // Глобальная очистка: TTL + limit-reached
        repo.cleanupExpired(now, cfg.hardDeleteExpired);
        repo.cleanupLimitReached(cfg.hardDeleteExpired);
    }

    public void reloadConfig(ConfigJson newCfg) {
        if (newCfg != null) this.cfg = newCfg;
    }

}
