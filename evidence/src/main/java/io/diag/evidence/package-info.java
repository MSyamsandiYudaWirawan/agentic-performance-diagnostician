/**
 * Postgres evidence store (scope §4.4): Flyway-owned schema, JPA repositories,
 * artifact store (disk + sha256 — the DB stores paths and hashes, never blobs),
 * run state machine and resume semantics.
 *
 * <p>Payload contract: {@code LoadReport}, {@code JfrReport},
 * {@code ExperimentRecord} (with {@code prediction} and {@code finding}) are
 * Jackson records; the DB persists them verbatim as JSONB.
 *
 * <p>Runtime fence: this module and its Postgres live on the controller side —
 * own always-on compose project {@code diag-evidence}, resource-capped — never
 * inside the measured envelope (scope §5, §10.16).
 */
package io.diag.evidence;
