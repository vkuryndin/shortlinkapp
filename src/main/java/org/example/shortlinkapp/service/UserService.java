package org.example.shortlinkapp.service;

import java.util.List;
import java.util.UUID;
import org.example.shortlinkapp.model.User;
import org.example.shortlinkapp.storage.UsersRepository;

public class UserService {
  private String currentUuid;
  private final UsersRepository repo;

  public UserService(String currentUuid) {
    this.currentUuid = currentUuid;
    this.repo = new UsersRepository();
    repo.upsertCurrent(currentUuid);
  }

  public void touchLastSeen() {
    repo.upsertCurrent(currentUuid);
  }

  public List<User> listAll() {
    return repo.list();
  }

  // making support for multiuser
  public void switchCurrent(String newUuid) {
    if (newUuid == null || newUuid.isBlank()) return;
    this.currentUuid = newUuid;
    repo.upsertCurrent(newUuid); // checking lastSeen paramter
  }

  public String createNewUserAndSwitch() {
    String id = UUID.randomUUID().toString();
    this.currentUuid = id;
    repo.upsertCurrent(id); // adding to  users.json (createdAt/lastSeenAt)
    return id;
  }
}
