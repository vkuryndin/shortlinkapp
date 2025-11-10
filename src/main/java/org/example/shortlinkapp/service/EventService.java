package org.example.shortlinkapp.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.example.shortlinkapp.model.EventLog;
import org.example.shortlinkapp.model.EventType;
import org.example.shortlinkapp.storage.EventsRepository;

/**
 * Centralized service for recording and reading application events.
 *
 * <p>This service is intentionally lightweight and safe to call from the CLI layer. When the
 * service is <em>disabled</em> (see the {@code enabled} flag), all write and read operations turn
 * into no-ops (writers do nothing; readers return empty lists). This allows the rest of the code
 * to call the logging API unconditionally without guarding each invocation.
 *
 * <p>Events are persisted via {@link EventsRepository}. Each event entry contains a timestamp,
 * type, owner UUID, short code (if applicable), and a free-form message. Convenience helpers
 * ({@link #info(String, String, String)}, {@link #expired(String, String, String)},
 * {@link #limitReached(String, String, String)}, {@link #error(String, String, String)})
 * set the {@link EventType} for you and delegate to {@link #log(String, String, String, EventType)}.
 *
 * <p>Read helpers provide simple views:
 * <ul>
 *   <li>{@link #listByOwner(String)} – all events for a given owner (in repository order)</li>
 *   <li>{@link #recentByOwner(String, int)} – newest first, limited by {@code limit}</li>
 *   <li>{@link #recentGlobal(int)} – newest first across all owners</li>
 * </ul>
 */
public class EventService {
    /**
     * Global on/off switch. When {@code false}, writers are no-ops and readers return empty lists.
     */
    private boolean enabled;

    /**
     * Backing repository. It is initialized only when the service is created in the enabled state.
     * When disabled, this field is {@code null}.
     */
    private final EventsRepository repo;

    /**
     * Creates a new event service.
     *
     * @param enabled initially enabled state; if {@code false}, logging is disabled and the repository
     *                is not instantiated
     */
    public EventService(boolean enabled) {
        this.enabled = enabled;
        this.repo = enabled ? new EventsRepository() : null;
    }

    /**
     * Dynamically toggles logging on/off.
     *
     * <p>Note: If the service was constructed in a disabled state, {@link #repo} remains
     * {@code null} even after enabling. This is acceptable for the current usage pattern where
     * toggling is used to suppress writes; if you need repository-backed writes after enabling,
     * create a new {@code EventService} instance with {@code enabled=true}.
     *
     * @param enabled {@code true} to enable logging and reads; {@code false} to make all operations
     *                no-ops
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Records an informational event for the given owner and short code.
     *
     * @param owner     owner UUID (may be {@code null} if not applicable)
     * @param shortCode short code (may be {@code null} if not applicable)
     * @param msg       human-readable message
     */
    public void info(String owner, String shortCode, String msg) {
        log(owner, shortCode, msg, EventType.INFO);
    }

    /**
     * Records an event indicating that a link has expired.
     *
     * @param owner     owner UUID
     * @param shortCode short code
     * @param msg       human-readable message
     */
    public void expired(String owner, String shortCode, String msg) {
        log(owner, shortCode, msg, EventType.EXPIRED);
    }

    /**
     * Records an event indicating that a click limit was reached.
     *
     * @param owner     owner UUID
     * @param shortCode short code
     * @param msg       human-readable message
     */
    public void limitReached(String owner, String shortCode, String msg) {
        log(owner, shortCode, msg, EventType.LIMIT_REACHED);
    }

    /**
     * Records an error event.
     *
     * @param owner     owner UUID (may be {@code null})
     * @param shortCode short code (may be {@code null})
     * @param msg       human-readable message describing the error
     */
    public void error(String owner, String shortCode, String msg) {
        log(owner, shortCode, msg, EventType.ERROR);
    }

    /**
     * Appends a new event to the repository if logging is enabled.
     *
     * <p>The timestamp is set to {@link LocalDateTime#now()} at the time of the call.
     *
     * @param owner     owner UUID
     * @param shortCode short code
     * @param msg       message
     * @param type      event type
     */
    private void log(String owner, String shortCode, String msg, EventType type) {
        if (!enabled) return;
        EventLog e = new EventLog();
        e.ts = LocalDateTime.now();
        e.type = type;
        e.ownerUuid = owner;
        e.shortCode = shortCode;
        e.message = msg;
        repo.add(e);
    }

    /**
     * Returns all events for a specific owner as provided by the repository.
     *
     * <p>If logging is disabled, returns an empty list.
     *
     * @param ownerUuid owner UUID
     * @return list of events (possibly empty)
     */
    public List<EventLog> listByOwner(String ownerUuid) {
        if (!enabled) return List.of();
        return repo.listByOwner(ownerUuid);
    }

    /**
     * Returns the most recent events for the given owner, sorted by timestamp descending.
     *
     * <p>If logging is disabled, returns an empty list.
     *
     * @param ownerUuid owner UUID
     * @param limit     maximum number of events to return; values &lt;= 0 result in an empty list
     * @return newest-first list of events (up to {@code limit})
     */
    public List<EventLog> recentByOwner(String ownerUuid, int limit) {
        if (!enabled) return List.of();
        return repo.listByOwner(ownerUuid).stream()
                .sorted(Comparator.comparing((EventLog e) -> e.ts).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Returns the most recent events across all owners, sorted by timestamp descending.
     *
     * <p>If logging is disabled, returns an empty list. The {@code limit} is clamped to a minimum
     * of 1 to avoid accidental requests for zero items.
     *
     * @param limit maximum number of events to return
     * @return newest-first list of events (up to {@code limit})
     */
    public java.util.List<EventLog> recentGlobal(int limit) {
        if (!enabled) return java.util.List.of();
        return repo.list().stream()
                .sorted(java.util.Comparator.comparing((EventLog e) -> e.ts).reversed())
                .limit(Math.max(1, limit))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Indicates whether logging is currently enabled.
     *
     * @return {@code true} if enabled; {@code false} otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }
}
