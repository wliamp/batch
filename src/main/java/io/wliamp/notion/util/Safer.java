package io.wliamp.notion.util;

import com.fasterxml.jackson.databind.JsonNode;

import static java.util.Optional.ofNullable;

public class Safer {
    public static String safeId(JsonNode input) {
        return input.get("id").asText().replace("-","");
    }

    public static String safeName(String input) {
        return ofNullable(input)
                .map(s -> s.replaceAll("[^a-zA-Z0-9-_.]", "_"))
                .orElse("untitled");
    }
}
