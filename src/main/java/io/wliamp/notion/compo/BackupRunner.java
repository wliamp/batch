package io.wliamp.notion.compo;

import io.wliamp.notion.service.BackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.AbstractMap.SimpleEntry;

import static java.util.Arrays.stream;
import static org.slf4j.event.Level.*;

@Component
public class BackupRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(BackupRunner.class);

    private final BackupService backupService;

    public BackupRunner(BackupService backupService) {
        this.backupService = backupService;
    }

    @Override
    public void run(String... args) {
        String[] envKeys = {
                "PPS_INTEGRATION_NOTION",
                // More GitHub Secrets here...
        };

        log.info("🚀 BackupRunner started. Checking {} environment keys...", envKeys.length);

        stream(envKeys)
                .map(key -> new SimpleEntry<>(key, System.getenv(key)))
                .peek(entry -> log.atLevel(
                                entry.getValue() == null || entry.getValue().isBlank() ? WARN : INFO
                        ).log("{} Env [{}]",
                                entry.getValue() == null || entry.getValue().isBlank() ? "⚠ Missing or blank token for" : "✅ Found token for",
                                entry.getKey()
                        )
                )
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .forEach(entry -> {
                    String workspaceName = entry.getKey().split("_", 2)[0].toLowerCase();
                    try {
                        backupService.runBackup(entry.getValue(), workspaceName);
                        log.info("🎉 Backup done for workspace [{}]", workspaceName);
                    } catch (Exception e) {
                        log.error("❌ Backup failed for workspace [{}]", workspaceName, e);
                    }
                });

        log.info("🔚 Finished backup for all workspaces, exiting.");
        System.exit(0);
    }
}
