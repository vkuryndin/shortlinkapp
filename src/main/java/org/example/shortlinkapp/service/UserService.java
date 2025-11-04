package org.example.shortlinkapp.service;


import org.example.shortlinkapp.model.User;
import org.example.shortlinkapp.storage.UsersRepository;


import java.util.List;
import java.util.UUID;


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


    public List<User> listAll() { return repo.list(); }

    //making support for multiuser
    public void switchCurrent(String newUuid) {
        if (newUuid == null || newUuid.isBlank()) return;
        this.currentUuid = newUuid;
        repo.upsertCurrent(newUuid); // заодно отметим lastSeen
    }

    public String createNewUserAndSwitch() {
        String id = UUID.randomUUID().toString();
        this.currentUuid = id;
        repo.upsertCurrent(id); // добавит в users.json (createdAt/lastSeenAt)
        return id;
    }


}