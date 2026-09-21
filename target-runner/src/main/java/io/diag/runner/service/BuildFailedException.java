package io.diag.runner.service;

public final class BuildFailedException extends RuntimeException{
    public BuildFailedException(String message) {
        super(message);
    }
}
