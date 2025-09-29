package io.wliamp.notion.util;

import com.fasterxml.jackson.databind.JsonNode;
import io.wliamp.notion.record.TitleResult;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.StreamSupport;

public class Extractor {
    public static TitleResult extractTitle(JsonNode obj, String shortId) {
        // 1. Try properties.title
        Optional<String> fromProps = Optional.ofNullable(obj.get("properties"))
                .flatMap(node -> StreamSupport.stream(node.spliterator(), false)
                        .filter(prop -> prop.has("title"))
                        .map(prop -> prop.get("title"))
                        .filter(JsonNode::isArray)
                        .filter(arr -> !arr.isEmpty())
                        .map(arr -> arr.get(0).path("plain_text").asText(null))
                        .filter(Objects::nonNull)
                        .findFirst());

        if (fromProps.isPresent()) {
            return new TitleResult(fromProps.get(), "properties.title");
        }

        // 2. Try obj.title
        Optional<String> fromTitle = Optional.ofNullable(obj.get("title"))
                .filter(JsonNode::isArray)
                .filter(arr -> !arr.isEmpty())
                .map(arr -> arr.get(0).path("plain_text").asText(null));

        return fromTitle.map(s -> new TitleResult(s, "title"))
                .orElseGet(() -> new TitleResult("untitled-" + shortId, "fallback"));
    }
}
