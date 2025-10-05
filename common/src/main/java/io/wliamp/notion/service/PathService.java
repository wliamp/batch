package io.wliamp.notion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.stream.BaseStream;

import static java.nio.file.Files.*;
import static java.util.Comparator.reverseOrder;
import static reactor.core.publisher.Flux.*;
import static reactor.core.publisher.Mono.fromCallable;
import static reactor.core.scheduler.Schedulers.boundedElastic;

@Service
@Slf4j
public class PathService {
    public Flux<Path> listPath(Path path) {
        return isExists(path)
                .filter(Boolean::booleanValue)
                .flatMapMany(_ -> using(
                        () -> list(path),
                        Flux::fromStream,
                        BaseStream::close
                ))
                .switchIfEmpty(defer(() -> {
                    log.warn("⚠ listPath() skipped, path not found: {}", path.toAbsolutePath());
                    return empty();
                }))
                .onErrorResume(e -> {
                    log.error("❌ listPath() FAILED for path={}", path, e);
                    return empty();
                })
                .subscribeOn(boundedElastic());
    }

    public Mono<Boolean> isExists(Path path) {
        return fromCallable(() -> exists(path))
                .doOnNext(v -> log.info("🔍 isExists({}) => {}", path, v))
                .onErrorResume(e -> {
                    log.error("❌ isExists() FAILED for path={}", path, e);
                    return just(false);
                })
                .subscribeOn(boundedElastic());
    }

    public Mono<Boolean> isDir(Path path) {
        return fromCallable(() -> isDirectory(path))
                .onErrorResume(e -> {
                    log.error("❌ isDir() FAILED for path={}", path, e);
                    return Mono.just(false);
                })
                .subscribeOn(boundedElastic());
    }

    public Mono<Path> createDir(Path path) {
        return fromCallable(() -> createDirectories(path))
                .doOnSuccess(p -> log.info("📂 Directory created at {}", p.toAbsolutePath()))
                .onErrorResume(e -> {
                    log.error("❌ createDir() FAILED for path={}", path, e);
                    return Mono.empty();
                })
                .subscribeOn(boundedElastic());
    }

    @SuppressWarnings("resource")
    public Mono<Void> cleanRecursively(Path path) {
        return isExists(path)
                .filter(Boolean::booleanValue)
                .flatMap(_ -> Mono.using(
                        () -> walk(path).sorted(reverseOrder()),
                        stream -> fromStream(stream)
                                .concatMap(this::removeFile)
                                .then(),
                        BaseStream::close
                ).doOnSuccess(v -> log.debug("🧹 Cleaned {}", path.toAbsolutePath())))
                .switchIfEmpty(defer(() -> {
                    log.warn("⚠ cleanRecursively() skipped, path not found: {}", path.toAbsolutePath());
                    return Mono.empty();
                }).then())
                .onErrorResume(e -> {
                    log.error("❌ cleanRecursively() FAILED for path={}", path, e);
                    return Mono.empty();
                })
                .subscribeOn(boundedElastic());
    }

    public Mono<Void> removeFile(Path path) {
        return fromCallable(() -> {
            deleteIfExists(path);
            return path;
        })
                .doOnSuccess(p -> log.debug("🗑 Removed {}", p))
                .onErrorResume(e -> {
                    log.error("❌ removeFile() FAILED for {}", path, e);
                    return Mono.empty();
                })
                .then()
                .subscribeOn(boundedElastic());
    }
}
