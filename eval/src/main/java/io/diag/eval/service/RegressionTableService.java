package io.diag.eval.service;

import io.diag.eval.model.RegressionRow;

import java.util.List;

/**
 * Service generating prompt/model regression tables (Step 11 M1, scope §10.20).
 */
public interface RegressionTableService {

    List<RegressionRow> computeTable(List<String> runIds);

    String renderMarkdown(List<RegressionRow> rows);
}
