package org.example.shortlinkapp.storage;

import static org.example.shortlinkapp.storage.DataPaths.LINKS_JSON;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.*;
import org.example.shortlinkapp.model.ShortLink;
import org.example.shortlinkapp.model.Status;
import org.example.shortlinkapp.util.TimeUtils;

/**
 * Repository for persisting and querying {@link ShortLink} entities.
 *
 * <p>Data is cached in-memory and flushed to {@code data/links.json} via {@link JsonRepository}.
 * Most mutating operations are {@code synchronized} to provide simple thread-safety around the
 * in-memory cache and file writes.
 *
 * <p>ID generation is based on a monotonically increasing in-memory sequence restored from existing
 * IDs (e.g. {@code L-000123}) on startup.
 */
public class LinksRepository {
  private static final Type LIST_TYPE = new TypeToken<List<ShortLink>>() {}.getType();

  private final List<ShortLink> cache;
  private final java.util.concurrent.atomic.AtomicLong seq =
      new java.util.concurrent.atomic.AtomicLong(0L);

  // private long seq = 0; // local sequence for IDs

  /**
   * Creates a repository instance and loads existing links from the disk. Also restores the
   * internal ID sequence from the maximum numeric suffix of IDs of the form {@code L-######}.
   */
  public LinksRepository() {
    this.cache = JsonRepository.readOrDefault(LINKS_JSON, LIST_TYPE, new ArrayList<>());
    // restore sequence based on existing IDs like L-000123
    long max = 0L;
    for (var l : cache) {
      String id = l.id;
      if (id == null) continue;
      try {
        if (id.startsWith("L-")) {
          long n = Long.parseLong(id.substring(2));
          if (n > max) max = n;
        }
      } catch (NumberFormatException ignored) {
      }
    }
    seq.set(max); // the next ID produced by nextId() will be max+1
  }

  /**
   * Generates the next unique, human-readable identifier for a link.
   *
   * @return an ID in the form {@code L-######} (zero-padded).
   */
  public String nextId() {
    long n = seq.incrementAndGet(); // atomically: max -> max+1
    return String.format("L-%06d", n);
  }

  /**
   * Adds a link to the repository and immediately flushes to disk.
   *
   * @param link a non-null {@link ShortLink}.
   */
  public synchronized void add(ShortLink link) {
    cache.add(link);
    flush();
  }

  /**
   * Persists the current state of a link that already exists in the cache.
   *
   * <p>The object is stored by reference in the cache; this method simply flushes the cache to
   * disk.
   *
   * @param link a {@link ShortLink} previously added to this repository.
   */
  public synchronized void update(ShortLink link) {
    // nothing special; the object is in cache by reference, just flush
    flush();
  }

  /**
   * Returns a snapshot list of all links.
   *
   * @return a new list containing all cached links.
   */
  public List<ShortLink> listAll() {
    return new ArrayList<>(cache);
  }

  /**
   * Finds a link by its short code.
   *
   * @param code short code to search for.
   * @return an {@link Optional} with the matching link, or empty if none.
   */
  public Optional<ShortLink> findByShortCode(String code) {
    return cache.stream().filter(l -> code.equals(l.shortCode)).findFirst();
  }

  /**
   * Lists all links owned by the specified user.
   *
   * @param ownerUuid owner identifier.
   * @return a new list of links for that owner.
   */
  public List<ShortLink> listByOwner(String ownerUuid) {
    List<ShortLink> out = new ArrayList<>();
    for (ShortLink l : cache) if (ownerUuid.equals(l.ownerUuid)) out.add(l);
    return out;
  }

  /** Flushes the in-memory cache to {@code links.json}. Errors are logged to {@code stderr}. */
  public synchronized void flush() {
    try {
      JsonRepository.writeAtomic(LINKS_JSON, cache);
    } catch (Exception e) {
      System.err.println("Failed to write links.json: " + e.getMessage());
    }
  }

  /**
   * Deletes a link by short code but only if it belongs to the specified owner, then flushes.
   *
   * @param code short code to delete.
   * @param ownerUuid expected owner UUID.
   * @return {@code true} if a matching entry was removed; {@code false} otherwise.
   */
  public synchronized boolean deleteByShortCodeForOwner(String code, String ownerUuid) {
    for (int i = 0; i < cache.size(); i++) {
      var l = cache.get(i);
      if (code.equals(l.shortCode) && ownerUuid.equals(l.ownerUuid)) {
        cache.remove(i);
        flush();
        return true;
      }
    }
    return false;
  }

  /**
   * Cleans up links whose TTL has expired.
   *
   * <p>When {@code hardDelete} is {@code true}, expired entries are physically removed from the
   * repository. Otherwise, entries are left in place and their {@link Status} is set to {@link
   * Status#EXPIRED}. A flush occurs if anything changed.
   *
   * @param now the current time to compare against {@code expiresAt}.
   * @param hardDelete whether to hard-delete or soft-mark as expired.
   * @return the number of affected entries.
   */
  public synchronized int cleanupExpired(LocalDateTime now, boolean hardDelete) {
    int count = 0;
    if (hardDelete) {
      // remove records whose TTL has expired
      var it = cache.iterator();
      while (it.hasNext()) {
        var l = it.next();
        if (l.expiresAt != null && TimeUtils.isExpired(now, l.expiresAt)) {
          it.remove();
          count++;
        }
      }
    } else {
      // soft cleanup: mark as EXPIRED
      for (var l : cache) {
        if (l.expiresAt != null && TimeUtils.isExpired(now, l.expiresAt)) {
          if (l.status != Status.DELETED && l.status != Status.EXPIRED) {
            l.status = Status.EXPIRED;
            count++;
          }
        }
      }
    }
    if (count > 0) flush();
    return count;
  }

  /**
   * Cleans up links that have reached their click limit.
   *
   * <p>When {@code hardDelete} is {@code true}, matching entries are removed; otherwise, their
   * {@link Status} is set to {@link Status#LIMIT_REACHED}. A flush occurs if anything changed.
   *
   * @param hardDelete whether to remove or soft-mark the entries.
   * @return the number of affected entries.
   */
  public synchronized int cleanupLimitReached(boolean hardDelete) {
    int count = 0;
    if (hardDelete) {
      var it = cache.iterator();
      while (it.hasNext()) {
        var l = it.next();
        if (l.clickLimit != null && l.clickCount >= l.clickLimit) {
          it.remove();
          count++;
        }
      }
    } else {
      for (var l : cache) {
        if (l.clickLimit != null && l.clickCount >= l.clickLimit) {
          if (l.status != Status.DELETED && l.status != Status.LIMIT_REACHED) {
            l.status = Status.LIMIT_REACHED;
            count++;
          }
        }
      }
    }
    if (count > 0) flush();
    return count;
  }

  /**
   * Cleans up expired links for a specific owner.
   *
   * <p>When {@code hardDelete} is {@code true}, expired entries of the owner are removed; otherwise
   * they are marked as {@link Status#EXPIRED}. A flush occurs if anything changed.
   *
   * @param now the current time.
   * @param ownerUuid owner whose links are targeted.
   * @param hardDelete whether to remove or soft-mark.
   * @return number of affected entries.
   */
  public synchronized int cleanupExpiredForOwner(
      java.time.LocalDateTime now, String ownerUuid, boolean hardDelete) {
    int count = 0;
    if (hardDelete) {
      var it = cache.iterator();
      while (it.hasNext()) {
        var l = it.next();
        if (ownerUuid.equals(l.ownerUuid)
            && l.expiresAt != null
            && org.example.shortlinkapp.util.TimeUtils.isExpired(now, l.expiresAt)) {
          it.remove();
          count++;
        }
      }
    } else {
      for (var l : cache) {
        if (ownerUuid.equals(l.ownerUuid)
            && l.expiresAt != null
            && org.example.shortlinkapp.util.TimeUtils.isExpired(now, l.expiresAt)) {
          if (l.status != org.example.shortlinkapp.model.Status.DELETED
              && l.status != org.example.shortlinkapp.model.Status.EXPIRED) {
            l.status = org.example.shortlinkapp.model.Status.EXPIRED;
            count++;
          }
        }
      }
    }
    if (count > 0) flush();
    return count;
  }

  /**
   * Cleans up limit-reached links for a specific owner.
   *
   * <p>When {@code hardDelete} is {@code true}, matching entries are removed; otherwise they are
   * marked as {@link Status#LIMIT_REACHED}. A flush occurs if anything changed.
   *
   * @param ownerUuid owner whose links are targeted.
   * @param hardDelete whether to remove or soft-mark.
   * @return number of affected entries.
   */
  public synchronized int cleanupLimitReachedForOwner(String ownerUuid, boolean hardDelete) {
    int count = 0;
    if (hardDelete) {
      var it = cache.iterator();
      while (it.hasNext()) {
        var l = it.next();
        if (ownerUuid.equals(l.ownerUuid) && l.clickLimit != null && l.clickCount >= l.clickLimit) {
          it.remove();
          count++;
        }
      }
    } else {
      for (var l : cache) {
        if (ownerUuid.equals(l.ownerUuid) && l.clickLimit != null && l.clickCount >= l.clickLimit) {
          if (l.status != org.example.shortlinkapp.model.Status.DELETED
              && l.status != org.example.shortlinkapp.model.Status.LIMIT_REACHED) {
            l.status = org.example.shortlinkapp.model.Status.LIMIT_REACHED;
            count++;
          }
        }
      }
    }
    if (count > 0) flush();
    return count;
  }
}
