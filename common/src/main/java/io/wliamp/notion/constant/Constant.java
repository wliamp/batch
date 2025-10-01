package io.wliamp.notion.constant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Constant {
    JSON1("meta"),
    JSON2("blocks"),
    INVALID("untitled");

    private final String name;

    public String getJson() {
        return name + ".json";
    }
}
