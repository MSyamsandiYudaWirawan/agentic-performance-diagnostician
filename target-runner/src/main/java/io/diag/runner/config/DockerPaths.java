package io.diag.runner.config;

import java.nio.file.Path;
import java.util.Objects;

/** Converts host paths to Docker Desktop bind-mount form (scope §10.10). */
public final class DockerPaths {
    private DockerPaths(){}

    /** @param path absolute host path — relative paths are a caller bug, rejected immediately */
    public static String toMount(Path path){
        Objects.requireNonNull(path, "path must not be null");
        if(!path.isAbsolute()) throw new IllegalArgumentException("path must be absolute: " + path);
        return path.normalize().toString().replace('\\','/');
    }
}

