package io.wliamp.notion.compo;

import io.wliamp.notion.service.CleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.AbstractMap.SimpleEntry;
import java.util.List;

import static java.util.Arrays.stream;
import static org.slf4j.event.Level.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class CleanupRunner implements CommandLineRunner {
    private final CleanupService storageCleanupService;

    @Override
    public void run(String... args) {
        String[] envKeys = {
                "PPS_INTEGRATION_NOTION",
                // More GitHub Secrets here...
        };

        log.info("🚀 BackupRunner started. Checking {} environment keys...", envKeys.length);

        List<String> validWorkspaces = stream(envKeys)
                .map(key -> new SimpleEntry<>(key, System.getenv(key)))
                .peek(entry -> log.atLevel(
                                entry.getValue() == null || entry.getValue().isBlank() ? WARN : INFO
                        ).log("{} Env [{}]",
                                entry.getValue() == null || entry.getValue().isBlank() ? "⚠ Missing or blank token for" : "✅ Found token for",
                                entry.getKey()
                        )
                )
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .map(entry -> entry.getKey().split("_", 2)[0].toLowerCase())
                .toList();

        log.info("🔍 Valid workspaces detected: {}", validWorkspaces);

        storageCleanupService.cleanStorage(validWorkspaces).block();

        log.info("🔚 Finished cleanup, exiting.");
        System.exit(0);
    }
}
