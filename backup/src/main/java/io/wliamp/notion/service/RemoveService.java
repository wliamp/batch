package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static java.nio.file.Files.*;
import static reactor.core.publisher.Mono.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class RemoveService {
    private final CommonService commonService;
    private final PathService pathService;

    private final ObjectMapper mapper;

    public Mono<Void> remove(Path outDir, Set<String> activeIds) {
        log.info("🧹 Starting cleanup in [{}], keeping {} active IDs", outDir, activeIds.size());

        return fromCallable(() -> list(outDir))
                .flatMapMany(Flux::fromStream)
                .filter(Files::isDirectory)
                .doOnNext(dir -> log.debug("🔎 Scanning directory [{}]", dir))
                .flatMap(dir -> scanPageJson(dir, activeIds))
                .then()
                .doOnSuccess(v -> log.info("✅ Cleanup completed in [{}]", outDir))
                .doOnError(e -> log.error("❌ Cleanup failed in [{}]", outDir, e));
    }

    private Mono<Void> scanPageJson(Path dir, Set<String> activeIds) {
        var pageJson = dir.resolve("page.json");

        return defer(() -> !exists(pageJson)
                        ? empty()
                        : fromCallable(() -> mapper.readTree(readString(pageJson))))
                .doOnSubscribe(sub -> log.debug("📄 Reading page.json in [{}]", dir))
                .flatMap(commonService::safeId)
                .filter(id -> !activeIds.contains(id))
                .flatMap(id -> pathService.cleanRecursively(dir)
                        .doOnSubscribe(sub -> log.info("🗑️ Directory [{}] (id={}) not active, cleaning up", dir, id))
                        .doOnSuccess(v -> log.debug("🧽 Directory [{}] cleaned", dir))
                )
                .switchIfEmpty(fromRunnable(() ->
                        log.debug("✅ Directory [{}] is still active or has no page.json, skipping", dir)
                ))
                .doOnError(e -> log.error("❌ Failed to process [{}]", dir, e));
    }

}

