package io.wliamp.notion.service;

import io.wliamp.notion.compo.Director;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;

import static io.wliamp.notion.constant.Constant.*;
import static reactor.core.publisher.Mono.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class CleanupService {
    private final CommonService commonService;
    private final PathService pathService;
    private final Director director;
    private final JsonService jsonService;

    public Mono<Void> cleanup() {
        return pathService.exists(DIR.getPath())
                .filter(Boolean::booleanValue)
                .flatMapMany(_ -> pathService.list(DIR.getPath()))
                .flatMap(workspace -> cleanDir(workspace, director.getDirectories()))
                .then()
                .switchIfEmpty(fromRunnable(() ->
                        log.warn("⚠ Storage folder not found at {}", DIR.getPath().toAbsolutePath()))
                )
                .doOnSuccess(v -> log.info("🎉 CLEANUP completed successfully"))
                .doOnError(e -> log.error("🔥 CLEANUP failed", e));
    }

    private Mono<Void> cleanDir(Path dir, List<String> validDirs) {
        var folderName = dir.getFileName().toString();

        return pathService.isDir(dir)
                .flatMap(isDir -> !isDir
                        ? pathService.removeFile(dir)
                        : !validDirs.contains(folderName)
                        ? pathService.cleanRecursively(dir).doOnSubscribe(s -> log.info("🗑 Deleting invalid workspace: {}", folderName))
                        : pathService.list(dir)
                        .flatMap(this::cleanSubDir)
                        .then()
                        .doOnSubscribe(s -> log.info("✅ Keeping valid workspace: {}", folderName)));
    }

    private Mono<Void> cleanSubDir(Path folder) {
        var folderName = folder.getFileName().toString();

        return pathService.isDir(folder).flatMap(isDir -> !isDir
                ? pathService
                .removeFile(folder)
                .doOnSubscribe(_ -> log.info("🗑 Removing stray file: {}", folder))
                : folderName.startsWith(INVALID.getName())
                ? pathService
                .cleanRecursively(folder)
                .doOnSubscribe(_ -> log.info("🗑 Removing untitled folder: {}", folderName))
                : pathService.list(folder)
                .flatMap(file -> pathService.isDir(file).flatMap(isDir2 -> {
                    if (isDir2) return pathService.cleanRecursively(file)
                            .doOnSubscribe(s -> log.info("🗑 Unexpected subdirectory inside object, deleting: {}", file));
                    var name = file.getFileName().toString();
                    return !(name.equals(JSON1.getJson()) || name.equals(JSON2.getJson()))
                            ? pathService.removeFile(file)
                            .doOnSubscribe(s -> log.info("🗑 Removing extra file: {}", name))
                            : empty();
                }))
                .then(cleanOrphan(folder)));
    }

    private Mono<Void> cleanOrphan(Path objectDir) {
        var metaPath = objectDir.resolve(JSON1.getJson());

        return jsonService.read(metaPath)
                .flatMap(node -> commonService
                        .isOrphan(node, director.getDirectories(), objectDir.getParent()))
                .flatMap(orphan -> orphan
                        ? pathService
                        .cleanRecursively(objectDir)
                        .doOnSubscribe(s -> log.info("🗑 Deleting orphan folder: {}", objectDir))
                        : empty())
                .onErrorResume(e -> {
                    log.error("❌ Failed to process [{}]: {}", objectDir, e.getMessage());
                    return empty();
                });
    }
}


