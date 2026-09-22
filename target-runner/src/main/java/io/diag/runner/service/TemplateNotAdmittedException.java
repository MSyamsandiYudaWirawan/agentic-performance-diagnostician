package io.diag.runner.service;

/**
 * Thrown when a template id is known but not yet admitted to the library (§10.35).
 * The caller receives a parseable error envelope; the loop records a WASTED iteration.
 */
public final class TemplateNotAdmittedException extends Exception {
    public TemplateNotAdmittedException(String id) {
        super("Template '" + id + "' is not admitted (§10.35): no hand-validated measured run on record. " +
                "Candidates under investigation live outside the library until measured.");
    }
}
