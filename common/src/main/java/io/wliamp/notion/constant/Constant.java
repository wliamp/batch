package io.wliamp.notion.constant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;
import java.nio.file.Paths;

@Getter
@RequiredArgsConstructor
public enum Constant {
    DIR("storage"),
    JSON1("meta"),
    JSON2("blocks"),
    INVALID("untitled");

    private final String name;

    public Path getPath() {
        return Paths
                .get(System.getProperty("user.dir"))
                .getParent()
                .resolve(name);
    }

    public String getJson() {
        return name + ".json";
    }
}
