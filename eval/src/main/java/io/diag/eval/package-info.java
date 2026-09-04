/**
 * Eval harness (scope §6): matrix runner over the seeded target registry,
 * automated scoring (diagnosis accuracy vs ground truth, effectiveness vs
 * noise floor, efficiency in iterations/tokens/$), regression table keyed by
 * (prompt_hash, model, gen_params, aggregator_version), HTML run report
 * generated from the DB.
 *
 * <p>Top of the dependency DAG: depends on everything, nothing depends on it.
 * Reads the DB, never loop internals — the judge stays out of the judged.
 */
package io.diag.eval;
