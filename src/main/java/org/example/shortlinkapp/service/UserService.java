package org.example.shortlinkapp.service;

import java.util.List;
import java.util.UUID;
import org.example.shortlinkapp.model.User;
import org.example.shortlinkapp.storage.UsersRepository;

/**
 * High-level service for managing the current application user and the list of known users.
 *
 * <p>This service keeps track of the <em>current</em> user UUID (used by the CLI/session) and
 * persists user presence to {@code users.json} via {@link UsersRepository}. On construction and on
 * every switch/touch operation it "upserts" the user entry to ensure that:
 *
 * <ul>
 *   <li>the user exists in storage (created lazily if missing), and
 *   <li>{@code lastSeenAt} is refreshed to the current moment.
 * </ul>
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>The class is intentionally lightweight; it delegates all persistence concerns to
 *       {@link UsersRepository}.
 *   <li>There is no internal synchronization; if you access it from multiple threads, provide your
 *       own external synchronization.
 *   <li>{@code null} / blank UUIDs are ignored by switch operations to avoid corrupting state.
 * </ul>
 */
public class UserService {
    /** UUID of the user considered "current" for the session. */
    private String currentUuid;

    /** Repository facade for reading/updating {@code users.json}. */
    private final UsersRepository repo;

    /**
     * Creates a service bound to the specified current user.
     *
     * <p>As a side effect, the method ensures the user exists in storage and updates their
     * {@code lastSeenAt}.
     *
     * @param currentUuid UUID that represents the current user; must be a valid, non-blank UUID
     */
    public UserService(String currentUuid) {
        this.currentUuid = currentUuid;
        this.repo = new UsersRepository();
        repo.upsertCurrent(currentUuid);
    }

    //**
    // * Refreshes the {@code lastSeenAt} timestamp of the current user.
    // *
    // * <p>Intended to be called on significant user actions to persist activity.
    // */
    //public void touchLastSeen() {
    //    repo.upsertCurrent(currentUuid);
    //}

    /**
     * Returns the full list of known users from persistent storage.
     *
     * @return immutable snapshot list of users
     */
    public List<User> listAll() {
        return repo.list();
    }

    /**
     * Switches the current user to the given UUID (multi-user support).
     *
     * <p>Empty or {@code null} values are ignored. On success, the method also upserts the user in
     * storage and updates {@code lastSeenAt}.
     *
     * @param newUuid UUID of the user to become current; ignored if {@code null} or blank
     */
    // making support for multiuser
    public void switchCurrent(String newUuid) {
        if (newUuid == null || newUuid.isBlank()) return;
        this.currentUuid = newUuid;
        repo.upsertCurrent(newUuid); // checking lastSeen parameter
    }

    /**
     * Creates a brand-new user (random UUID v4), makes it the current user, and persists it.
     *
     * <p>As a side effect, the user record is inserted/updated in {@code users.json} with fresh
     * {@code createdAt} and {@code lastSeenAt} values.
     *
     * @return the generated UUID of the new current user
     */
    public String createNewUserAndSwitch() {
        String id = UUID.randomUUID().toString();
        this.currentUuid = id;
        repo.upsertCurrent(id); // adding to  users.json (createdAt/lastSeenAt)
        return id;
    }
}
