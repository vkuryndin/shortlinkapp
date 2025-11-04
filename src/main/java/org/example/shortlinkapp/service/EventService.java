package org.example.shortlinkapp.service;


import org.example.shortlinkapp.model.EventLog;
import org.example.shortlinkapp.model.EventType;
import org.example.shortlinkapp.storage.EventsRepository;


import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;


public class EventService {
    private  boolean enabled;
    private final EventsRepository repo;


    public EventService(boolean enabled) {
        this.enabled = enabled;
        this.repo = enabled ? new EventsRepository() : null;
    }
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void info(String owner, String shortCode, String msg) { log(owner, shortCode, msg, EventType.INFO); }
    public void expired(String owner, String shortCode, String msg) { log(owner, shortCode, msg, EventType.EXPIRED); }
    public void limitReached(String owner, String shortCode, String msg) { log(owner, shortCode, msg, EventType.LIMIT_REACHED); }
    public void error(String owner, String shortCode, String msg) { log(owner, shortCode, msg, EventType.ERROR); }


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
    public List<EventLog> listByOwner(String ownerUuid) {
        if (!enabled) return List.of();
        return repo.listByOwner(ownerUuid);
    }

    public List<EventLog> recentByOwner(String ownerUuid, int limit) {
        if (!enabled) return List.of();
        return repo.listByOwner(ownerUuid).stream()
                .sorted(Comparator.comparing((EventLog e) -> e.ts).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

}