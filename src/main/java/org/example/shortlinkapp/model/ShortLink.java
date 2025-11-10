package org.example.shortlinkapp.model;

import java.time.LocalDateTime;

/**
 * Represents a single short link entry stored in the application's repository.
 * <p>
 * A {@code ShortLink} binds an original long URL to a generated short code,
 * tracks its lifecycle (creation, expiration, limit reach, deletion),
 * and stores usage statistics.
 *
 * <p>All fields are public by design to simplify JSON serialization
 * and avoid boilerplate. Callers must treat the object as a mutable
 * data holder.</p>
 *
 * <h2>Fields overview</h2>
 * <ul>
 *   <li>{@code id} – internal unique identifier (UUID-like string).</li>
 *   <li>{@code ownerUuid} – the user who owns this short link.</li>
 *   <li>{@code longUrl} – the original full URL provided by the user.</li>
 *   <li>{@code shortCode} – generated short code (Base62) appended to {@code baseUrl}.</li>
 *   <li>{@code createdAt} – timestamp when the link was created.</li>
 *   <li>{@code expiresAt} – timestamp when the TTL ends; may be {@code null} if unlimited.</li>
 *   <li>{@code clickLimit} – the maximum allowed number of opens; {@code null} means unlimited.</li>
 *   <li>{@code clickCount} – number of successful opens.</li>
 *   <li>{@code lastAccessAt} – timestamp of the last open; may be {@code null}.</li>
 *   <li>{@code status} – lifecycle state (ACTIVE, EXPIRED, LIMIT_REACHED, DELETED).</li>
 * </ul>
 *
 * <h2>Lifecycle notes</h2>
 * <ul>
 *   <li>{@code status} is recalculated automatically during create/open/cleanup operations.</li>
 *   <li>A link becomes {@code EXPIRED} when {@code expiresAt} is in the past.</li>
 *   <li>A link becomes {@code LIMIT_REACHED} when {@code clickCount} hits {@code clickLimit}.</li>
 *   <li>{@code DELETED} means user-requested removal.</li>
 * </ul>
 *
 * @since 1.0
 */
public class ShortLink {

    /** Internal unique identifier for persistence. */
    public String id;

    /** UUID of the user who owns this short link. */
    public String ownerUuid;

    /** The original long URL that the user shortened. */
    public String longUrl;

    /** Generated short code appended to {@code baseUrl}. */
    public String shortCode;

    /** Timestamp when the link was created. */
    public LocalDateTime createdAt;

    /** Expiration timestamp (TTL). May be {@code null} for unlimited lifetime. */
    public LocalDateTime expiresAt;

    /** Maximum allowed clicks. {@code null} means unlimited. */
    public Integer clickLimit;

    /** Number of times the short link has been opened. */
    public int clickCount;

    /** Timestamp of the last successful open. May be {@code null}. */
    public LocalDateTime lastAccessAt;

    /** Current lifecycle state of the link. */
    public Status status;
}
