package io.wliamp.notion.compa;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.UUID;

import static java.util.Optional.ofNullable;

public class Utility {
    public static Optional<String> extractFirstPlainText(JsonNode arr) {
        return ofNullable(arr)
                .filter(JsonNode::isArray)
                .filter(a -> !a.isEmpty())
                .map(a -> a.get(0).path("plain_text").asText(null));
    }

    public static String safeName(String input) {
        return ofNullable(input)
                .map(s -> s.replaceAll("[^a-zA-Z0-9-_.]", "_"))
                .orElse("untitled");
    }

    public static String mask(String token, int displaySize) {
        return token == null || token.length() < displaySize * 2
                ? "****"
                : token.substring(0, displaySize) + "****" + token.substring(token.length() - displaySize);
    }

    public static String generateCode(int size) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, size);
    }
}
