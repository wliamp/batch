package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.wliamp.notion.record.TitleResult;
import io.wliamp.notion.util.Extractor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.stream.StreamSupport;

import static java.util.Optional.ofNullable;
import static reactor.core.publisher.Mono.fromCallable;

@Service
public class ExtractService {
    public Mono<String> safeId(JsonNode input) {
        return fromCallable(() -> input.get("id").asText().replace("-", ""));
    }

    public Mono<TitleResult> extractTitle(JsonNode obj, String id) {
        return fromCallable(() -> ofNullable(obj.get("properties"))
                .flatMap(node -> StreamSupport.stream(node.spliterator(), false)
                        .filter(prop -> prop.has("title"))
                        .map(prop -> prop.get("title"))
                        .map(Extractor::extractFirstPlainText)
                        .flatMap(Optional::stream)
                        .findFirst())
                .map(s -> new TitleResult(s, "properties.title"))
                .orElseGet(() -> Extractor.extractFirstPlainText(obj.get("title"))
                        .map(s -> new TitleResult(s, "title"))
                        .orElseGet(() -> new TitleResult("untitled-" + id, "fallback"))));
    }
}

