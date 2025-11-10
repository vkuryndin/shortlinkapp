package org.example.shortlinkapp.storage;

import static org.example.shortlinkapp.storage.DataPaths.EVENTS_JSON;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.example.shortlinkapp.model.EventLog;

/**
 * Repository for persisting and retrieving {@link EventLog} entries.
 *
 * <p>This implementation keeps an in-memory cache of events and stores them on disk as JSON at
 * {@link DataPaths#EVENTS_JSON}. The cache is initialized from disk on construction. All write
 * operations update both the cache and the backing file (via {@link #flush()}).
 *
 * <h2>Thread-safety</h2>
 * <ul>
 *   <li>{@link #add(EventLog)} and {@link #flush()} are synchronized to ensure that concurrent
 *       writers do not corrupt the in-memory state or on-disk file.
 *   <li>Read methods ({@link #list()} and {@link #listByOwner(String)}) return defensive copies to
 *       protect the internal cache from external mutation.
 * </ul>
 *
 * <h2>Error handling</h2>
 * I/O failures during {@link #flush()} are logged to {@code System.err}; the method does not throw
 * so that callers are not forced to handle persistence errors during normal flow.
 */
public class EventsRepository {

    /** Gson type token describing a {@code List<EventLog>} for JSON (de)serialization. */
    private static final Type LIST_TYPE = new TypeToken<List<EventLog>>() {}.getType();

    /** In-memory cache of all events loaded from and written back to {@code events.json}. */
    private List<EventLog> cache;

    /**
     * Creates a repository and loads existing events from {@link DataPaths#EVENTS_JSON}. If the file
     * is missing or unreadable, an empty list is used.
     */
    public EventsRepository() {
        this.cache = JsonRepository.readOrDefault(EVENTS_JSON, LIST_TYPE, new ArrayList<>());
    }

    /**
     * Appends a single event to the repository and persists the change immediately.
     *
     * @param e the event to add; must not be {@code null}
     */
    public synchronized void add(EventLog e) {
        cache.add(e);
        flush();
    }

    /**
     * Returns a snapshot of all events currently stored.
     *
     * @return a new {@link ArrayList} containing all cached events
     */
    public List<EventLog> list() {
        return new ArrayList<>(cache);
    }

    /**
     * Returns a snapshot of events that belong to the specified owner.
     *
     * @param ownerUuid the owner UUID used to filter events
     * @return a new list containing only events whose {@code ownerUuid} equals the provided value
     */
    public List<EventLog> listByOwner(String ownerUuid) {
        List<EventLog> out = new ArrayList<>();
        for (EventLog e : cache) if (ownerUuid.equals(e.ownerUuid)) out.add(e);
        return out;
    }

    /**
     * Persists the current cache to {@link DataPaths#EVENTS_JSON}. Any I/O exception is caught and
     * reported to {@code System.err} without throwing.
     */
    public synchronized void flush() {
        try {
            JsonRepository.writeAtomic(EVENTS_JSON, cache);
        } catch (Exception ex) {
            System.err.println("Failed to write events.json: " + ex.getMessage());
        }
    }
}
