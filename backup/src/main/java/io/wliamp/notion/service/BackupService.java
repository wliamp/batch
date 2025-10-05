package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.wliamp.notion.compo.EnvConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static io.wliamp.notion.compa.Utility.mask;
import static io.wliamp.notion.compa.Utility.safeName;
import static io.wliamp.notion.constant.Constant.*;
import static java.nio.file.Paths.get;
import static reactor.core.publisher.Mono.fromRunnable;

@Service
@Slf4j
@RequiredArgsConstructor
public class BackupService {
    private final EnvConfig envConfig;
    private final FetchService fetchService;
    private final SearchService searchService;
    private final CommonService commonService;
    private final JsonService jsonService;
    private final PathService pathService;

    public void backup() {
        var root = get(envConfig.getTmp());
        log.info("🔐 Using secret: {}", mask(envConfig.getToken(), 5));
        log.info("🚀 Starting workspace backup: {}", root.getParent().getFileName().toString().toUpperCase());

        prepareRoot(root)
                .flatMapMany(this::searchAndBackupObjects)
                .collectList()
                .doOnSuccess(list -> log.info("🎉 Backup completed successfully — {} objects processed", list.size()))
                .doOnError(e -> log.error("🔥 Backup failed", e))
                .block();
    }

    private Mono<Path> prepareRoot(Path root) {
        return pathService.createDir(root)
                .doOnSubscribe(_ -> log.debug("📂 Preparing output directory at {}", root));
    }

    private Flux<JsonNode> searchAndBackupObjects(Path outDir) {
        var totalFound = new AtomicInteger();
        var totalSucceeded = new AtomicInteger();
        var totalFailed = new AtomicInteger();

        return searchService.search(envConfig.getToken())
                .doOnSubscribe(_ -> log.info("🔍 Searching for objects..."))
                .doOnNext(_ -> totalFound.incrementAndGet())
                .flatMapSequential(node ->
                                backupObject(node, outDir)
                                        .doOnSuccess(_ -> totalSucceeded.incrementAndGet())
                                        .onErrorResume(e -> {
                                            totalFailed.incrementAndGet();
                                            log.debug("⚠ Failed to backup one object: {}", e.getMessage());
                                            return Mono.empty();
                                        }),
                        4)
                .doOnComplete(() -> log.info("""
                                ✅ Backup summary:
                                • Total objects found: {}
                                • Successfully backed up: {}
                                • Failed: {}
                                """,
                        totalFound.get(),
                        totalSucceeded.get(),
                        totalFailed.get()
                ));
    }

    private Mono<JsonNode> backupObject(JsonNode node, Path outDir) {
        return commonService.safeId(node)
                .flatMap(id -> commonService.extractTitle(node)
                        .flatMap(title -> {
                            log.debug("➡️ Backing up object [{}] with title [{}]", id, title.name());
                            return fetchAndWrite(id, node, outDir.resolve(safeName(title.name())));
                        })
                );
    }

    private Mono<JsonNode> fetchAndWrite(String id, JsonNode node, Path objDir) {
        return fetchService.fetch(id, envConfig.getToken())
                .doOnSubscribe(_ -> log.debug("📥 Fetching block tree for [{}]", id))
                .collectList()
                .doOnNext(blocks -> log.debug("📦 Fetched {} blocks for [{}]", blocks.size(), id))
                .flatMap(blocks -> pathService.createDir(objDir)
                        .thenMany(
                                jsonService.create(objDir.resolve(JSON1.getJson()), node)
                                        .then(jsonService.create(objDir.resolve(JSON2.getJson()), blocks))
                        )
                        .then(fromRunnable(() ->
                                log.debug("💾 Object [{}] written to {}", id, objDir)))
                )
                .thenReturn(node);
    }
}
