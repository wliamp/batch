package io.wliamp.notion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.Files.*;
import static reactor.core.publisher.Mono.*;

@Service
@Slf4j
public class CleanupService {

    private static final Path STORAGE_PATH = Paths.get("storage");

    public Mono<Void> cleanStorage(List<String> validWorkspaces) {
        return fromRunnable(() -> {
            if (notAStorageDir()) {
                log.warn("⚠ Storage folder not found at {}", STORAGE_PATH.toAbsolutePath());
                return;
            }

            try (Stream<Path> stream = list(STORAGE_PATH)) {
                var partitioned = stream.collect(Collectors.partitioningBy(Files::isDirectory));

                partitioned.getOrDefault(true, List.of())
                        .forEach(path -> handleDirectory(path, validWorkspaces));

                partitioned.getOrDefault(false, List.of())
                        .forEach(this::handleFile);

            } catch (IOException e) {
                log.error("❌ Failed to scan storage folder", e);
            }
        }).then();
    }

    private boolean notAStorageDir() {
        return !exists(STORAGE_PATH) || !isDirectory(STORAGE_PATH);
    }

    private void handleDirectory(Path path, List<String> validWorkspaces) {
        var folderName = path.getFileName().toString();
        if (validWorkspaces.contains(folderName)) {
            log.info("✅ Keeping valid folder: {}", folderName);
            return;
        }

        log.info("🗑 Deleting non-matching folder: {}", folderName);
        try {
            deleteRecursively(path);
        } catch (IOException e) {
            log.error("❌ Failed to delete folder {}", path, e);
        }
    }

    private void handleFile(Path path) {
        log.info("🗑 Deleting file: {}", path.getFileName());
        try {
            deleteIfExists(path);
        } catch (IOException e) {
            log.error("❌ Failed to delete file {}", path, e);
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (isDirectory(path)) try (var entries = list(path)) {
            entries.forEach(p -> {
                try {
                    deleteRecursively(p);
                } catch (IOException e) {
                    log.error("❌ Failed to delete nested path {}", p, e);
                }
            });
        }
        deleteIfExists(path);
    }
}
