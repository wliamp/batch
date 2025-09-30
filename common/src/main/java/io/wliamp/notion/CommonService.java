package io.wliamp.notion;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static io.wliamp.notion.Utility.extractFirstPlainText;
import static io.wliamp.notion.Utility.generateCode;
import static java.nio.file.Files.deleteIfExists;
import static java.nio.file.Files.walk;
import static java.util.Comparator.reverseOrder;
import static java.util.Optional.ofNullable;
import static java.util.stream.StreamSupport.stream;
import static reactor.core.publisher.Mono.fromCallable;
import static reactor.core.publisher.Mono.fromRunnable;
import static reactor.core.scheduler.Schedulers.boundedElastic;

@Service
@Slf4j
public class CommonService {

    public Mono<String> safeId(JsonNode node) {
        return fromCallable(() -> node.get("id").asText().replace("-", ""))
                .doOnError(e -> log.error("❌ Failed to extract safeId from node={}", node, e));
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
                .doOnError(e -> log.error("❌ Failed to extractTitle from node={}", node, e));
    }

    public Mono<Void> cleanRecursively(Path path) {
        return fromRunnable(() -> {
            try (var walker = walk(path)) {
                walker.sorted(reverseOrder()).forEach(p -> {
                    try {
                        deleteIfExists(p);
                    } catch (IOException e) {
                        throw new RuntimeException("Delete failed at " + p, e);
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException("Walk failed at " + path, e);
            }
        }).subscribeOn(boundedElastic())
                .doOnError(e -> log.error("❌ Failed to clean path={}", path, e))
                .then();
    }
}

