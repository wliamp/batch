package io.wliamp.notion.util;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

import static java.util.Optional.ofNullable;

public class Extractor {
    public static Optional<String> extractFirstPlainText(JsonNode arr) {
        return ofNullable(arr)
                .filter(JsonNode::isArray)
                .filter(a -> !a.isEmpty())
                .map(a -> a.get(0).path("plain_text").asText(null));
    }
}
