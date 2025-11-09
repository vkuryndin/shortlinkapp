package org.example.shortlinkapp.storage;

import static org.example.shortlinkapp.storage.DataPaths.USERS_JSON;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.example.shortlinkapp.model.User;

public class UsersRepository {
  private static final Type LIST_TYPE = new TypeToken<List<User>>() {}.getType();

  private List<User> cache;

  public UsersRepository() {
    this.cache = JsonRepository.readOrDefault(USERS_JSON, LIST_TYPE, new ArrayList<>());
  }

  public List<User> list() {
    return new ArrayList<>(cache);
  }

  public Optional<User> findByUuid(String uuid) {
    return cache.stream().filter(u -> uuid.equals(u.uuid)).findFirst();
  }

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

  public void flush() {
    try {
      JsonRepository.writeAtomic(USERS_JSON, cache);
    } catch (Exception e) {
      System.err.println("Failed to write users.json: " + e.getMessage());
    }
  }
}
