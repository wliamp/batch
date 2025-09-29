package io.wliamp.notion.compo;

import io.wliamp.notion.service.BackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.AbstractMap;

import static java.util.Arrays.stream;

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

        stream(envKeys)
                .map(key -> new AbstractMap.SimpleEntry<>(key, System.getenv(key)))
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .forEach(entry ->
                        backupService.runBackup(
                                entry.getValue(),
                                entry.getKey().split("_", 2)[0].toLowerCase()
                        ));

        log.info("🔚 Finished backup for all workspaces, exiting.");
        System.exit(0);
    }
}
