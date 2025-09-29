package io.wliamp.notion.service;

import io.wliamp.notion.Director;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static java.nio.file.Files.*;
import static reactor.core.publisher.Flux.*;
import static reactor.core.publisher.Mono.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class CleanupService {

    private static final Path STORAGE_PATH = Paths.get("storage");
    private final Director director;

    public Mono<Void> cleanup() {
        List<String> validWorkspaces = director.getDirectories();

        if (!exists(STORAGE_PATH) || !isDirectory(STORAGE_PATH)) {
            log.warn("⚠ Storage folder not found at {}", STORAGE_PATH.toAbsolutePath());
            return Mono.empty();
        }

        try {
            return fromStream(list(STORAGE_PATH))
                    .flatMap(path -> {
                        if (Files.isDirectory(path)) {
                            return handleDirectory(path, validWorkspaces);
                        } else {
                            return handleFile(path);
                        }
                    })
                    .then();
        } catch (IOException e) {
            log.error("❌ Failed to scan storage folder", e);
            return Mono.error(e);
        }
    }

    private Mono<Void> handleDirectory(Path path, List<String> validWorkspaces) {
        var folderName = path.getFileName().toString();
        if (validWorkspaces.contains(folderName)) {
            log.info("✅ Keeping valid folder: {}", folderName);
            return Mono.empty();
        }

        log.info("🗑 Deleting non-matching folder: {}", folderName);
        return deleteRecursively(path)
                .doOnError(err -> log.error("❌ Failed to delete folder {}", path, err));
    }

    private Mono<Void> handleFile(Path path) {
        log.info("🗑 Deleting file: {}", path.getFileName());
        return fromRunnable(() -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Mono<Void> deleteRecursively(Path path) {
        if (Files.isDirectory(path)) try {
            return fromStream(list(path))
                    .flatMap(this::deleteRecursively)
                    .then(fromRunnable(() -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }));
        } catch (IOException e) {
            return Mono.error(e);
        }
        return handleFile(path);

    }
}
