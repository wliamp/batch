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
import static reactor.core.publisher.Mono.empty;
import static reactor.core.publisher.Mono.fromCallable;

@Service
@Slf4j
@RequiredArgsConstructor
public class RemoveService {
    private final FileService fileService;
    private final ObjectMapper mapper;

    public Mono<Void> remove(Path outDir, Set<String> activeIds) {
        return fromCallable(() -> list(outDir))
                .flatMapMany(Flux::fromStream)
                .filter(Files::isDirectory)
                .flatMap(dir -> checkAndDelete(dir, activeIds))
                .then();
    }

    private Mono<Void> checkAndDelete(Path dir, Set<String> activeIds) {
        var pageJson = dir.resolve("page.json");
        return !exists(pageJson) ? empty() : fromCallable(() -> mapper.readTree(readString(pageJson)))
                .map(root -> root.get("id").asText().replace("-", ""))
                .flatMap(id -> activeIds.contains(id) ? empty() : fileService.deleteRecursively(dir));
    }
}

