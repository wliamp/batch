package io.wliamp.notion.compo;

import io.wliamp.notion.service.BackupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class BackupRunner implements CommandLineRunner {
    private final BackupService backupService;

    @Override
    public void run(String... args) {
        backupService.backup().block();
        log.info("🔚 Finished cleanup, exiting.");
        System.exit(0);
    }
}
