package io.wliamp.notion.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum Constant {
    INVALID("untitled"),
    JSON1("meta"),
    JSON2("blocks");

    private final String name;

    public String getJson() {
        return name + ".json";
    }
}
