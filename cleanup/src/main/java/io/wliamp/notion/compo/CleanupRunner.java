package io.wliamp.notion.compo;

import io.wliamp.notion.service.CleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class CleanupRunner implements CommandLineRunner {
    private final CleanupService CleanupService;

    @Override
    public void run(String... args) {
        CleanupService.cleanup().block();
        System.exit(0);
    }
}
