package dev.smppowers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class DataStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, PlayerData>>() {}.getType();

    private Map<String, PlayerData> map = new LinkedHashMap<>();

    private Path file() {
        return Config.dir().resolve("players.json");
    }

    void load() {
        map = new LinkedHashMap<>();
        Path f = file();
        if (!Files.exists(f)) return;
        try {
            Map<String, PlayerData> read = GSON.fromJson(Files.readString(f), MAP_TYPE);
            if (read != null) map = read;
        } catch (Exception e) {
            try {
                Files.copy(f, Config.dir().resolve("players.json.broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
                // nothing more we can do
            }
            PowersMod.LOGGER.error("players.json could not be read. A copy was saved as players.json.broken", e);
        }
    }

    PlayerData get(UUID id) {
        return map.computeIfAbsent(id.toString(), k -> new PlayerData());
    }

    PlayerData peek(UUID id) {
        return map.get(id.toString());
    }

    void save() {
        try {
            Files.createDirectories(Config.dir());
            Path tmp = Config.dir().resolve("players.json.tmp");
            Files.writeString(tmp, GSON.toJson(map));
            Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            PowersMod.LOGGER.error("Could not save players.json", e);
        }
    }
}
