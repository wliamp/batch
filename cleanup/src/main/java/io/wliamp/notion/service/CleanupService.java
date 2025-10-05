package io.wliamp.notion.service;

import io.wliamp.notion.compo.EnvConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

import static io.wliamp.notion.constant.Constant.*;
import static java.nio.file.Paths.*;
import static reactor.core.publisher.Mono.*;
import static reactor.core.publisher.Mono.empty;
import static reactor.core.publisher.Mono.fromRunnable;

@Service
@Slf4j
@RequiredArgsConstructor
public class CleanupService {
    private final EnvConfig envConfig;
    private final CommonService commonService;
    private final PathService pathService;
    private final JsonService jsonService;

    public void cleanup() {
        var root = get(envConfig.getTmp());
        log.info("🚀 Starting cleanup Workspace {}", root.getParent().getFileName().toString().toUpperCase());

        pathService.listPath(root)
                .flatMap(this::cleanObjectDir)
                .then()
                .switchIfEmpty(fromRunnable(() ->
                        log.warn("⚠ Root folder not found at {}", root.toAbsolutePath())))
                .doOnSuccess(_ -> log.info("🎉 CLEANUP completed successfully"))
                .doOnError(e -> log.error("🔥 CLEANUP failed", e))
                .block();
    }

    private Mono<Void> cleanObjectDir(Path dir) {
        return pathService.isDir(dir)
                .flatMap(isDir -> isDir
                        ? handleDir(dir, dir.getFileName().toString())
                        : pathService.removeFile(dir)
                        .doOnSubscribe(_ -> log.info("🗑 Removing stray file: {}", dir))
                );
    }

    private Mono<Void> handleDir(Path dir, String name) {
        return just(name)
                .filter(n -> !n.startsWith("."))
                .flatMap(n -> just(n)
                        .filter(nn -> nn.startsWith(INVALID.getName()))
                        .flatMap(nn -> pathService.cleanRecursively(dir)
                                .doOnSubscribe(_ -> log.info("🗑 Removing untitled folder: {}", nn)))
                        .switchIfEmpty(
                                pathService.listPath(dir)
                                        .flatMap(file -> pathService.isDir(file)
                                                .flatMap(isDir2 -> isDir2
                                                        ? pathService.cleanRecursively(file)
                                                        .doOnSubscribe(_ -> log.debug("🗑 Unexpected subdirectory inside object, deleting: {}", file))
                                                        : cleanFile(file)))
                                        .then(cleanOrphan(dir))
                        )
                )
                .switchIfEmpty(empty());
    }

    private Mono<Void> cleanFile(Path file) {
        return just(file.getFileName().toString())
                .filter(n -> !(n.equals(JSON1.getJson()) || n.equals(JSON2.getJson())))
                .flatMap(n -> pathService.removeFile(file)
                        .doOnSubscribe(_ -> log.debug("🗑 Removing extra file: {}", n)))
                .switchIfEmpty(empty());
    }

    private Mono<Void> cleanOrphan(Path objectDir) {
        return jsonService.read(objectDir.resolve(JSON1.getJson()))
                .flatMap(node -> commonService.isOrphan(node, objectDir.getParent()))
                .filter(Boolean::booleanValue)
                .flatMap(_ -> pathService.cleanRecursively(objectDir)
                        .doOnSubscribe(_ -> log.info("🗑 Deleting orphan folder: {}", objectDir)))
                .onErrorResume(e -> {
                    log.error("❌ Failed to process [{}]: {}", objectDir, e.getMessage());
                    return empty();
                });
    }
}
