package org.example.shortlinkapp.util;

import com.google.gson.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class JsonUtils {
  private JsonUtils() {}

  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  private static final JsonSerializer<LocalDateTime> LDT_SER =
      (src, t, ctx) -> new JsonPrimitive(ISO.format(src));

  private static final JsonDeserializer<LocalDateTime> LDT_DES =
      (json, t, ctx) -> LocalDateTime.parse(json.getAsString(), ISO);

  public static Gson gson() {
    return new GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(LocalDateTime.class, LDT_SER)
        .registerTypeAdapter(LocalDateTime.class, LDT_DES)
        .create();
  }
}
