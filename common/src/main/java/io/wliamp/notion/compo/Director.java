package io.wliamp.notion.compo;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.AbstractMap.SimpleEntry;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.wliamp.notion.compa.Utility.mask;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

@Component
@Slf4j
public class Director {
    @Getter
    private final Map<String, String> githubSecrets;

    public Director() {
        String[] envKeys = {
                "PPS_INTEGRATION_NOTION",
                // More GitHub Secrets here...
        };

        log.info("🚀 Checking {} environment keys...", envKeys.length);

        Map<String, String> map = stream(envKeys)
                .map(key -> {
                    String value = System.getenv(key);
                    log.warn(value == null || value.isBlank()
                            ? "⚠ Missing or blank token for Env [{}]"
                            : "✅ Found token for Env [{}]", mask(key, 5));
                    return new SimpleEntry<>(key, value);
                })
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .collect(toMap(
                        entry -> entry.getKey().split("_", 2)[0].toLowerCase(),
                        SimpleEntry::getValue,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));

        map.forEach((workspace, token) -> log.info("Workspace detected: {}", workspace));

        this.githubSecrets = Collections.unmodifiableMap(map);
    }

    public List<String> getDirectories() {
        return List.copyOf(githubSecrets.keySet());
    }
}
