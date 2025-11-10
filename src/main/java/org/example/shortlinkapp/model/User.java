package org.example.shortlinkapp.model;

import java.time.LocalDateTime;

/**
 * Represents a local application user identified by a UUID.
 *
 * <p>This object is stored in {@code users.json} and used by the CLI to track
 * ownership of short links, event logs, and session switching.</p>
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@code uuid} – unique identifier of the user (string-form UUID).</li>
 *   <li>{@code createdAt} – timestamp when the user entry was created.</li>
 *   <li>{@code lastSeenAt} – timestamp of the last time this user was active.</li>
 * </ul>
 *
 * <p>The {@code UserService} updates these timestamps as part of normal operation.</p>
 *
 * @since 1.0
 */
public class User {
    public String uuid;
    public LocalDateTime createdAt;
    public LocalDateTime lastSeenAt;
}
