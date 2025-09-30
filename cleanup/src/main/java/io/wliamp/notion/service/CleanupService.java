package io.wliamp.notion.service;

import io.wliamp.notion.compo.Director;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static reactor.core.publisher.Mono.*;
import static reactor.core.publisher.Mono.fromRunnable;

@Service
@Slf4j
@RequiredArgsConstructor
public class CleanupService {

    private static final Path STORAGE_PATH = Paths.get("storage");

    private final CommonService commonService;
    private final PathService pathService;
    private final Director director;
    private final JsonService jsonService;

    public Mono<Void> cleanup() {
        return pathService.exists(STORAGE_PATH)
                .filter(Boolean::booleanValue)
                .flatMapMany(_ -> pathService.list(STORAGE_PATH))
                .flatMap(path -> pathHandler(path, director.getDirectories()))
                .then()
                .switchIfEmpty(fromRunnable(() ->
                        log.warn("⚠ Storage folder not found at {}", STORAGE_PATH.toAbsolutePath()))
                )
                .doOnSuccess(v -> log.info("🎉 Global CLEANUP completed successfully"))
                .doOnError(e -> log.error("🔥 Global CLEANUP failed", e));
    }

    private Mono<Void> pathHandler(Path path, List<String> validDirs) {
        return pathService.isDir(path)
                .flatMap(isDir -> isDir ? directoryHandler(path, validDirs)
                        : pathService.removeFile(path));
    }

    private Mono<Void> directoryHandler(Path dirPath, List<String> validDirs) {
        var folderName = dirPath.getFileName().toString();
        return Mono.just(validDirs.contains(folderName))
                .flatMap(valid -> valid
                        ? pathService.list(dirPath)
                        .flatMap(p -> pathService.isDir(p)
                                .flatMap(isDir -> isDir
                                        ? directoryHandler(p, validDirs)
                                        : jsonHandler(p, validDirs)))
                        .then()
                        .doOnSubscribe(s -> log.info("✅ Keeping valid folder: {}", folderName))
                        : pathService.cleanRecursively(dirPath)
                        .doOnSubscribe(s -> log.info("🗑 Deleting non-matching folder: {}", folderName))
                );
    }

    private Mono<Void> jsonHandler(Path filePath, List<String> validDirs) {
        return jsonService.read(filePath)
                .flatMap(node -> commonService.isOrphan(node, validDirs, filePath.getParent()))
                .flatMap(orphan -> orphan
                        ? pathService.removeFile(filePath)
                        .doOnSubscribe(s -> log.info("🗑 Deleting orphan object: {}", filePath.getFileName()))
                        : empty());
    }
}

