package io.diag.runner.service;

import io.diag.evidence.dto.EditDto;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Server-side template registry (scope §10.23, §10.35).
 *
 * Admitted set = only hand-validated, measured templates (§10.35).
 * Currently: jar-unpack only.
 *
 * Unadmitted candidates (registry rejects apply; §10.35):
 *   hikari-pool-size (expansion code exists — testable via expandUnchecked),
 *   jvm-opts (code arrives with step 10), disable-resource-url-filter,
 *   resource-url-cache-warmup, classpath-launch.
 * spring-resources-cache is FALSIFIED (REF resource-cache-exp) and never enters.
 */
public final class FixTemplateRegistry {

    // §10.35: only templates with a measured hand-validation run are admitted.
    private static final Set<String> ADMITTED = Set.of("jar-unpack");

    private final Map<String, FixTemplate> templates;

    public FixTemplateRegistry() {
        Map<String, FixTemplate> map = new LinkedHashMap<>();
        register(map, new JarUnpackTemplate());
        register(map, new HikariPoolSizeTemplate());
        this.templates = Map.copyOf(map);
    }

    private static void register(Map<String, FixTemplate> map, FixTemplate t) {
        map.put(t.id(), t);
    }

    /**
     * Expands the template into edits, enforcing the admission rule.
     * Unknown ids are rejected as such (hallucinated template); known-but-unadmitted
     * candidates throw {@link TemplateNotAdmittedException} — the expansion code is
     * exercised in tests, but the loop never applies it.
     */
    public List<EditDto> expand(String id, Map<String, String> params, Path targetRepo) throws Exception {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(params, "params must not be null");
        Objects.requireNonNull(targetRepo, "targetRepo must not be null");

        FixTemplate template = templates.get(id);
        if (template == null) {
            throw new IllegalArgumentException("Unknown template id: " + id);
        }
        if (!ADMITTED.contains(id)) {
            // §10.35: unadmitted candidate — expansion code exists but the library rejects it.
            throw new TemplateNotAdmittedException(id);
        }
        return template.expand(targetRepo, params);
    }

    /**
     * Expands WITHOUT the admission gate — for verify-gate tests that need to
     * exercise the expansion logic of unadmitted candidates (§10.35 note).
     */
    public List<EditDto> expandUnchecked(String id, Map<String, String> params, Path targetRepo) throws Exception {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(params, "params must not be null");
        Objects.requireNonNull(targetRepo, "targetRepo must not be null");
        FixTemplate template = templates.get(id);
        if (template == null) {
            throw new IllegalArgumentException("Unknown template id: " + id);
        }
        return template.expand(targetRepo, params);
    }

    public boolean isAdmitted(String id) {
        return ADMITTED.contains(id);
    }
}
