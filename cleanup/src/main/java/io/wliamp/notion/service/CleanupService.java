package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static reactor.core.publisher.Flux.fromStream;
import static reactor.core.publisher.Mono.defer;
import static reactor.core.publisher.Mono.fromRunnable;

@Service
@Slf4j
@RequiredArgsConstructor
public class CleanupService {

    private static final Path STORAGE_PATH = Paths.get("storage");
    private final Director director;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Mono<Void> cleanup() {
        var validDirs = director.getDirectories();
        return defer(() -> {
                    try {
                        return Files.exists(STORAGE_PATH) && Files.isDirectory(STORAGE_PATH)
                                ? fromStream(Files.list(STORAGE_PATH))
                                .flatMap(path -> pathHandler(path, validDirs))
                                .then()
                                : Mono.fromRunnable(() -> log.warn("⚠ Storage folder not found at {}", STORAGE_PATH.toAbsolutePath()));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
        );
    }

    private Mono<Void> pathHandler(Path path, List<String> validDirs) {
        return Files.isDirectory(path)
                ? directoryHandler(path, validDirs)
                : fileHandler(path);
    }

    private Mono<Void> directoryHandler(Path dirPath, List<String> validDirs) {
        var folderName = dirPath.getFileName().toString();
        return validDirs.contains(folderName)
                ? defer(() -> {
                    try {
                        return fromStream(list(dirPath))
                        .flatMap(p -> isDirectory(p)
                                ? directoryHandler(p, validDirs)
                                : jsonFileHandler(p, validDirs))
                        .then();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
        ).doOnSubscribe(s -> log.info("✅ Keeping valid folder: {}", folderName))
                : deleteRecursively(dirPath)
                .doOnSubscribe(s -> log.info("🗑 Deleting non-matching folder: {}", folderName));
    }

    private Mono<Void> fileHandler(Path filePath) {
        return defer(() -> fromRunnable(() -> {
            try {
                deleteIfExists(filePath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        })).doOnSubscribe(s -> log.info("🗑 Deleting file: {}", filePath.getFileName())).then();
    }

    private Mono<Void> jsonFileHandler(Path filePath, List<String> validDirs) {
        return defer(() -> {
            JsonNode json;
            try {
                json = objectMapper.readTree(filePath.toFile());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return isOrphan(json, validDirs)
                    ? fileHandler(filePath)
                    .doOnSubscribe(s -> log.info("🗑 Deleting orphan object: {}", filePath.getFileName()))
                    : Mono.empty();
        });
    }

    private boolean isOrphan(JsonNode objectJson, List<String> validDirs) {
        var workspaceId = objectJson.path("workspace_id").asText(null);
        if (workspaceId == null || !validDirs.contains(workspaceId)) return true;

        var parentId = objectJson.path("parent_id").asText(null);
        if (parentId != null && !Files.exists(STORAGE_PATH.resolve(parentId))) return true;

        return objectJson.path("archived").asBoolean(false);
    }

    private Mono<Void> deleteRecursively(Path path) {
        return Files.isDirectory(path)
                ? defer(() -> {
                    try {
                        return fromStream(Files.list(path))
                                .flatMap(this::deleteRecursively)
                                .then(fileHandler(path));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
        ) : fileHandler(path);
    }
}
