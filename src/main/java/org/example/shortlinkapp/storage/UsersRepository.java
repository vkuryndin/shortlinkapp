package org.example.shortlinkapp.storage;

import static org.example.shortlinkapp.storage.DataPaths.USERS_JSON;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.example.shortlinkapp.model.User;

/**
 * Repository for persisting and querying {@link User} entities.
 *
 * <p>This repository keeps an in-memory cache that is loaded from {@code data/users.json} on
 * construction and is saved back to disk via {@link JsonRepository#writeAtomic(java.nio.file.Path,
 * Object)}.
 *
 * <p><strong>Thread-safety:</strong> This implementation is not designed for concurrent mutation
 * from multiple threads. Methods that modify the cache call {@link #flush()} immediately to persist
 * changes.
 */
public class UsersRepository {
  /** Gson runtime type token for {@code List<User>}. */
  private static final Type LIST_TYPE = new TypeToken<List<User>>() {}.getType();

  /** In-memory cache of all users. Persisted to {@code data/users.json}. */
  private List<User> cache;

  /**
   * Creates a repository and loads users from {@code data/users.json}. If the file does not exist
   * or cannot be read, an empty list is used and created on disk when first flushed.
   */
  public UsersRepository() {
    this.cache = JsonRepository.readOrDefault(USERS_JSON, LIST_TYPE, new ArrayList<>());
  }

  /**
   * Returns a defensive copy of all users currently stored.
   *
   * @return list copy of users; never {@code null}
   */
  public List<User> list() {
    return new ArrayList<>(cache);
  }

  /**
   * Finds a user by UUID.
   *
   * @param uuid user identifier to search for (must match exactly)
   * @return {@link Optional} with the user if found; otherwise {@link Optional#empty()}
   */
  public Optional<User> findByUuid(String uuid) {
    return cache.stream().filter(u -> uuid.equals(u.uuid)).findFirst();
  }

  /**
   * Ensures that a user with the given UUID exists and updates their {@code lastSeenAt}.
   *
   * <ul>
   *   <li>If the user does not exist, a new {@link User} is created with {@code createdAt} set to
   *       {@link LocalDateTime#now()} and added to the cache.
   *   <li>In all cases, {@code lastSeenAt} is refreshed to {@link LocalDateTime#now()}.
   *   <li>The repository is immediately {@linkplain #flush() flushed} to disk.
   * </ul>
   *
   * @param uuid UUID of the current user; must be non-null/non-blank
   * @return the existing or newly created {@link User} instance
   */
  public User upsertCurrent(String uuid) {
    User u =
        findByUuid(uuid)
            .orElseGet(
                () -> {
                  User nu = new User();
                  nu.uuid = uuid;
                  nu.createdAt = LocalDateTime.now();
                  cache.add(nu);
                  return nu;
                });
    u.lastSeenAt = LocalDateTime.now();
    flush();
    return u;
  }

  /**
   * Persists the current cache to {@code data/users.json} atomically.
   *
   * <p>If persistence fails, the error is printed to {@code System.err} and the in-memory cache
   * remains intact.
   */
  public void flush() {
    try {
      JsonRepository.writeAtomic(USERS_JSON, cache);
    } catch (Exception e) {
      System.err.println("Failed to write users.json: " + e.getMessage());
    }
  }
}
