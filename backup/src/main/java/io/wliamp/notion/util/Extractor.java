package io.wliamp.notion.util;

import com.fasterxml.jackson.databind.JsonNode;
import io.wliamp.notion.record.TitleResult;

import java.util.Objects;

import static java.util.Optional.ofNullable;
import static java.util.stream.StreamSupport.*;

public class Extractor {
    public static TitleResult extractTitle(JsonNode obj, String id) {
        return ofNullable(obj.get("properties"))
                .flatMap(node -> stream(node.spliterator(), false)
                        .filter(prop -> prop.has("title"))
                        .map(prop -> prop.get("title"))
                        .filter(JsonNode::isArray)
                        .filter(arr -> !arr.isEmpty())
                        .map(arr -> arr.get(0).path("plain_text").asText(null))
                        .filter(Objects::nonNull)
                        .findFirst())
                .map(s -> new TitleResult(s, "properties.title"))
                .orElseGet(() -> ofNullable(obj.get("title"))
                        .filter(JsonNode::isArray)
                        .filter(arr -> !arr.isEmpty())
                        .map(arr -> arr.get(0).path("plain_text").asText(null))
                        .map(s -> new TitleResult(s, "title"))
                        .orElseGet(() -> new TitleResult("untitled-" + id, "fallback")));
    }
}
