package io.wliamp.notion.compo;

import io.wliamp.notion.service.BackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class BackupRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(BackupRunner.class);

    private final BackupService backupService;

    public BackupRunner(BackupService backupService) {
        this.backupService = backupService;
    }

    @Override
    public void run(String... args) {
        backupService.runBackup();
        log.info("🔚 Finished backup, exiting.");
        System.exit(0);
    }
}
