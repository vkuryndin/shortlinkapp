package org.example.shortlinkapp.storage;

import static org.example.shortlinkapp.storage.DataPaths.EVENTS_JSON;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.example.shortlinkapp.model.EventLog;

public class EventsRepository {
  private static final Type LIST_TYPE = new TypeToken<List<EventLog>>() {}.getType();

  private List<EventLog> cache;

  public EventsRepository() {
    this.cache = JsonRepository.readOrDefault(EVENTS_JSON, LIST_TYPE, new ArrayList<>());
  }

  public synchronized void add(EventLog e) {
    cache.add(e);
    flush();
  }

  public List<EventLog> list() {
    return new ArrayList<>(cache);
  }

  public List<EventLog> listByOwner(String ownerUuid) {
    List<EventLog> out = new ArrayList<>();
    for (EventLog e : cache) if (ownerUuid.equals(e.ownerUuid)) out.add(e);
    return out;
  }

  public synchronized void flush() {
    try {
      JsonRepository.writeAtomic(EVENTS_JSON, cache);
    } catch (Exception ex) {
      System.err.println("Failed to write events.json: " + ex.getMessage());
    }
  }
}
