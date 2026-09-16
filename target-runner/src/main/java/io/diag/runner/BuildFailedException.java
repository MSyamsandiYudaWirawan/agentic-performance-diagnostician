package io.diag.runner;

public final class BuildFailedException extends RuntimeException{
    public BuildFailedException(String message) {
        super(message);
    }
}
