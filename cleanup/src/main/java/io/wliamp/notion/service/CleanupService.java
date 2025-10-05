package io.wliamp.notion.service;

import io.wliamp.notion.compo.EnvConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static io.wliamp.notion.constant.Constant.*;
import static java.nio.file.Paths.get;
import static reactor.core.publisher.Mono.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class CleanupService {
    private final EnvConfig envConfig;
    private final CommonService commonService;
    private final PathService pathService;
    private final JsonService jsonService;

    private final AtomicInteger totalScanned = new AtomicInteger(0);
    private final AtomicInteger totalDeletedFiles = new AtomicInteger(0);
    private final AtomicInteger totalDeletedFolders = new AtomicInteger(0);

    public void cleanup() {
        totalScanned.set(0);
        totalDeletedFiles.set(0);
        totalDeletedFolders.set(0);
        var root = get(envConfig.getTmp());
        log.info("🚀 Starting cleanup for workspace: {}", root.getParent().getFileName().toString().toUpperCase());

        pathService.isExists(root)
                .flatMap(exists -> exists
                        ? pathService.listPath(root)
                        .doOnNext(_ -> totalScanned.incrementAndGet())
                        .flatMap(this::cleanObjectDir)
                        .then(fromRunnable(() -> log.info("""
                                        ✅ CLEANUP Summary for workspace [{}]
                                        • Total items scanned: {}
                                        • Files deleted: {}
                                        • Folders deleted: {}
                                        """,
                                root.getFileName(),
                                totalScanned.get(),
                                totalDeletedFiles.get(),
                                totalDeletedFolders.get()
                        )))
                        : fromRunnable(() ->
                        log.warn("⚠ Root folder not found at {}", root.toAbsolutePath())))
                .doOnError(e -> log.error("🔥 CLEANUP failed", e))
                .block();
    }

    private Mono<Void> cleanObjectDir(Path dir) {
        return pathService.isDir(dir)
                .flatMap(isDir -> isDir
                        ? handleDir(dir, dir.getFileName().toString())
                        : pathService.removeFile(dir)
                        .doOnSubscribe(_ -> log.info("🗑 Removing stray file: {}", dir))
                        .doOnSuccess(_ -> totalDeletedFiles.incrementAndGet())
                );
    }

    private Mono<Void> handleDir(Path dir, String name) {
        return just(name)
                .filter(n -> !n.startsWith("."))
                .flatMap(n -> just(n)
                        .filter(nn -> nn.startsWith(INVALID.getName()))
                        .flatMap(nn -> pathService.cleanRecursively(dir)
                                .doOnSubscribe(_ -> log.info("🗑 Removing untitled folder: {}", nn))
                                .doOnSuccess(_ -> totalDeletedFolders.incrementAndGet()))
                        .switchIfEmpty(
                                pathService.listPath(dir)
                                        .doOnNext(_ -> totalScanned.incrementAndGet())
                                        .flatMap(file -> pathService.isDir(file)
                                                .flatMap(isDir2 -> isDir2
                                                        ? pathService.cleanRecursively(file)
                                                        .doOnSubscribe(_ -> log.debug("🗑 Removing unexpected subdirectory: {}", file))
                                                        .doOnSuccess(_ -> totalDeletedFolders.incrementAndGet())
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
                        .doOnSubscribe(_ -> log.debug("🗑 Removing extra file: {}", n))
                        .doOnSuccess(_ -> totalDeletedFiles.incrementAndGet()))
                .switchIfEmpty(empty());
    }

    private Mono<Void> cleanOrphan(Path objectDir) {
        return jsonService.read(objectDir.resolve(JSON1.getJson()))
                .flatMap(node -> commonService.isOrphan(node, objectDir.getParent()))
                .filter(Boolean::booleanValue)
                .flatMap(_ -> pathService.cleanRecursively(objectDir)
                        .doOnSubscribe(_ -> log.info("🗑 Removing orphan folder: {}", objectDir))
                        .doOnSuccess(_ -> totalDeletedFolders.incrementAndGet()))
                .onErrorResume(e -> {
                    log.error("❌ Failed to process [{}]: {}", objectDir, e.getMessage());
                    return empty();
                });
    }
}
