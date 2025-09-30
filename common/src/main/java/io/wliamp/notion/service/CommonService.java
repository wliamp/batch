package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.wliamp.notion.compa.Title;
import io.wliamp.notion.compa.Utility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static io.wliamp.notion.compa.Utility.extractFirstPlainText;
import static io.wliamp.notion.compa.Utility.generateCode;
import static java.util.Optional.ofNullable;
import static java.util.stream.StreamSupport.stream;
import static reactor.core.publisher.Mono.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommonService {
    private final PathService pathService;

    public Mono<String> safeId(JsonNode node) {
        return fromCallable(() -> node.get("id").asText().replace("-", ""))
                .doOnError(e -> log.error("❌ safeId() FAILED from node={}", node, e));
    }

    public Mono<Title> extractTitle(JsonNode node) {
        return fromCallable(() -> ofNullable(node.get("properties"))
                .flatMap(n -> stream(n.spliterator(), false)
                        .filter(prop -> prop.has("title"))
                        .map(prop -> prop.get("title"))
                        .map(Utility::extractFirstPlainText)
                        .flatMap(Optional::stream)
                        .findFirst())
                .map(s -> new Title(s, "properties.title"))
                .orElseGet(() -> extractFirstPlainText(node.get("title"))
                        .map(s -> new Title(s, "title"))
                        .orElseGet(() -> new Title("untitled-" + generateCode(8), "fallback"))))
                .doOnError(e -> log.error("❌ extractTitle() FAILED from node={}", node, e));
    }

    public Mono<Boolean> isOrphan(JsonNode node, List<String> dirs, Path dir) {
        var workspaceId = node.path("workspace_id").asText(null);
        var parentId = node.path("parent_id").asText(null);
        var archived = node.path("archived").asBoolean(false);
        var parentPath = dir.resolve(ofNullable(parentId).orElse(""));

        return pathService.exists(parentPath)
                .map(parentExists -> {
                    var orphan = workspaceId == null
                            || !dirs.contains(workspaceId)
                            || parentId != null && !parentExists
                            || archived;

                    log.info("🔎 Checking orphan: workspaceId={}, parentId={}, archived={}, parentExists={}, orphan={}",
                            workspaceId, parentId, archived, parentExists, orphan);

                    return orphan;
                })
                .doOnError(e -> log.error("❌ isOrphan() FAILED for dir={}", dir, e));
    }
}

