package io.diag.runner.service;

import io.diag.evidence.dto.EditDto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FixTemplate for tuning JVM heap options in compose-service.yml (Step 10 M3, scope §10.23, §10.35).
 * Modifies -Xms and -Xmx memory flags while preserving all other JAVA_OPTS and compose structure.
 */
public final class JvmOptsTemplate implements FixTemplate {

    static final String COMPOSE_PATH = "compose-service.yml";
    private static final Pattern XMS_PATTERN = Pattern.compile("(-Xms)(\\d+[kmgKMG])");
    private static final Pattern XMX_PATTERN = Pattern.compile("(-Xmx)(\\d+[kmgKMG])");

    @Override
    public String id() {
        return "jvm-opts";
    }

    @Override
    public List<EditDto> expand(Path targetRepo, Map<String, String> params) throws Exception {
        Objects.requireNonNull(targetRepo, "targetRepo must not be null");
        Objects.requireNonNull(params, "params must not be null");

        String heapSize = parseHeapSize(params);

        Path composeFile = targetRepo.resolve(COMPOSE_PATH);
        if (!Files.exists(composeFile)) {
            throw new IllegalArgumentException("compose-service.yml not found in target repo: " + targetRepo);
        }

        String current = Files.readString(composeFile);
        String updated = rewriteHeapOpts(current, heapSize);

        return List.of(new EditDto(COMPOSE_PATH, updated));
    }

    private static String parseHeapSize(Map<String, String> params) {
        String val = params.get("heapSize");
        if (val == null || val.isBlank()) {
            val = params.get("xmx");
        }
        if (val == null || val.isBlank()) {
            // Default recommended restoration heap for the 2GB container envelope (§10.7)
            val = "1536m";
        }
        val = val.trim();
        if (!val.matches("\\d+[kmgKMG]")) {
            throw new IllegalArgumentException("Invalid heapSize format (must match \\d+[kmgKMG], e.g. 1536m, 1g): " + val);
        }
        return val;
    }

    static String rewriteHeapOpts(String content, String heapSize) {
        String[] lines = content.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);

        for (String line : lines) {
            String updatedLine = line;
            Matcher mXms = XMS_PATTERN.matcher(updatedLine);
            if (mXms.find()) {
                updatedLine = mXms.replaceFirst("$1" + heapSize);
            }
            Matcher mXmx = XMX_PATTERN.matcher(updatedLine);
            if (mXmx.find()) {
                updatedLine = mXmx.replaceFirst("$1" + heapSize);
            }
            out.add(updatedLine);
        }

        return String.join("\n", out);
    }
}
