package io.diag.runner.service;

import io.diag.evidence.dto.EditDto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * UNADMITTED CANDIDATE — not in the library until step-10 hand-validation (§10.35).
 * Attempting to apply via the registry returns a §10.35 rejection.
 * <p>
 * Expansion code exists and is testable via {@link FixTemplateRegistry#expandUnchecked}
 * (verify gate step 7). Read-modify-write: reads application.properties, replaces or
 * appends the hikari pool-size property, emits the full file as one EditDto.
 */
public final class HikariPoolSizeTemplate implements FixTemplate {

    static final String PROPERTIES_PATH = "src/main/resources/application.properties";
    static final String PROPERTY_KEY = "spring.datasource.hikari.maximumPoolSize";

    @Override
    public String id() {
        return "hikari-pool-size";
    }

    @Override
    public List<EditDto> expand(Path targetRepo, Map<String, String> params) throws Exception {
        Objects.requireNonNull(targetRepo, "targetRepo must not be null");
        Objects.requireNonNull(params, "params must not be null");

        int poolSize = parsePoolSize(params.get("poolSize"));

        Path propsFile = targetRepo.resolve(PROPERTIES_PATH);
        String newLine = PROPERTY_KEY + "=" + poolSize;

        String current = Files.exists(propsFile) ? Files.readString(propsFile) : "";
        String updated = rewriteProperty(current, newLine);
        return List.of(new EditDto(PROPERTIES_PATH, updated));
    }

    private static int parsePoolSize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("hikari-pool-size requires param 'poolSize'");
        }
        final int poolSize;
        try {
            poolSize = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("poolSize must be an integer, got: " + raw);
        }
        if (poolSize < 1 || poolSize > 200) {
            throw new IllegalArgumentException("poolSize must be 1..200, got " + poolSize);
        }
        return poolSize;
    }

    /**
     * Replaces an existing property line in-place, or appends the line if absent.
     * Unrelated lines are preserved verbatim — full-file replacement, but a minimal
     * git diff for everything except the one property.
     */
    static String rewriteProperty(String current, String newLine) {
        String[] lines = current.split("\n", -1);
        boolean found = false;
        List<String> out = new ArrayList<>(lines.length + 1);
        for (String line : lines) {
            if (isPropertyLine(line)) {
                out.add(newLine);
                found = true;
            } else {
                out.add(line);
            }
        }
        if (found) {
            return String.join("\n", out);
        }
        // Append: drop trailing empties left by split, add the property,
        // restore a single trailing newline.
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        out.add(newLine);
        return String.join("\n", out) + "\n";
    }

    /**
     * Matches the key at line start followed by a separator — so the key itself
     * is replaced, but a longer key sharing the prefix (e.g. maximumPoolSizeIdle)
     * is not, and neither is a commented-out line.
     */
    private static boolean isPropertyLine(String line) {
        if (!line.startsWith(PROPERTY_KEY)) {
            return false;
        }
        String rest = line.substring(PROPERTY_KEY.length());
        return rest.isEmpty() || rest.startsWith("=") || rest.startsWith(":")
                || rest.startsWith(" ") || rest.startsWith("\t");
    }
}
