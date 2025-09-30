package io.wliamp.notion.util;

import static java.util.Optional.ofNullable;

public class Safer {

    public static String safeName(String input) {
        return ofNullable(input)
                .map(s -> s.replaceAll("[^a-zA-Z0-9-_.]", "_"))
                .orElse("untitled");
    }

    public static String mask(String token) {
        return token == null || token.length() < 8
                ? "****"
                : token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }
}
