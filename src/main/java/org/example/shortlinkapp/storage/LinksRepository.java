package org.example.shortlinkapp.storage;

import static org.example.shortlinkapp.storage.DataPaths.LINKS_JSON;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.*;
import org.example.shortlinkapp.model.ShortLink;
import org.example.shortlinkapp.model.Status;
import org.example.shortlinkapp.util.TimeUtils;

public class LinksRepository {
  private static final Type LIST_TYPE = new TypeToken<List<ShortLink>>() {}.getType();

  private List<ShortLink> cache;
  private final java.util.concurrent.atomic.AtomicLong seq =
      new java.util.concurrent.atomic.AtomicLong(0L);

  // private long seq = 0; // local sequence for IDs

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
    seq.set(max); // следующий будет max+1 в nextId()
  }

  // fixing spot bugs error
  // public synchronized String nextId() {
  //     seq += 1;
  //     return String.format("L-%06d", seq);
  // }

  public String nextId() {
    long n = seq.incrementAndGet(); // атомарно: max -> max+1
    return String.format("L-%06d", n);
  }

  public synchronized void add(ShortLink link) {
    cache.add(link);
    flush();
  }

  public synchronized void update(ShortLink link) {
    // nothing special; object is in cache by reference, just flush
    flush();
  }

  public List<ShortLink> listAll() {
    return new ArrayList<>(cache);
  }

  public Optional<ShortLink> findByShortCode(String code) {
    return cache.stream().filter(l -> code.equals(l.shortCode)).findFirst();
  }

  public List<ShortLink> listByOwner(String ownerUuid) {
    List<ShortLink> out = new ArrayList<>();
    for (ShortLink l : cache) if (ownerUuid.equals(l.ownerUuid)) out.add(l);
    return out;
  }

  public synchronized void flush() {
    try {
      JsonRepository.writeAtomic(LINKS_JSON, cache);
    } catch (Exception e) {
      System.err.println("Failed to write links.json: " + e.getMessage());
    }
  }

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

  public synchronized int cleanupExpired(LocalDateTime now, boolean hardDelete) {
    int count = 0;
    if (hardDelete) {
      // удаляем записи, чей TTL истёк
      var it = cache.iterator();
      while (it.hasNext()) {
        var l = it.next();
        if (l.expiresAt != null && TimeUtils.isExpired(now, l.expiresAt)) {
          it.remove();
          count++;
        }
      }
    } else {
      // мягкая очистка: помечаем EXPIRED
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
