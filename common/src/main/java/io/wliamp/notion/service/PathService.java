package io.wliamp.notion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
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
    public Flux<Path> list(Path path) {
        return using(
                () -> Files.list(path),
                Flux::fromStream,
                BaseStream::close
        ).doOnError(e -> log.error("❌ list() FAILED for path={}", path, e));
    }

    public Mono<Boolean> exists(Path path) {
        return fromCallable(() -> Files.exists(path))
                .doOnError(e -> log.error("❌ exists() FAILED for path={}", path, e));
    }

    public Mono<Boolean> isDir(Path path) {
        return fromCallable(() -> Files.isDirectory(path))
                .doOnError(e -> log.error("❌ isDir() FAILED for path={}", path, e));
    }

    public Mono<Path> createDir(Path path) {
        return fromCallable(() -> createDirectories(path))
                .doOnSuccess(p -> log.info("📂 Directory created at {}", p.toAbsolutePath()))
                .doOnError(e -> log.error("❌ createDir() FAILED for path={}", path, e));
    }

    @SuppressWarnings("resource")
    public Mono<Void> cleanRecursively(Path path) {
        return Mono.using(() -> walk(path).sorted(reverseOrder()),
                        stream -> Flux.fromStream(stream)
                                .concatMap(this::removeFile)
                                .then(),
                        BaseStream::close
                )
                .doOnError(e -> log.error("❌ cleanRecursively() FAILED for path={}", path, e))
                .subscribeOn(boundedElastic());
    }

    public Mono<Void> removeFile(Path path) {
        return fromCallable(() -> {
            deleteIfExists(path);
            return path;
        })
                .doOnSuccess(p -> log.info("🗑 Removed {}", p))
                .then()
                .subscribeOn(boundedElastic());
    }
}
