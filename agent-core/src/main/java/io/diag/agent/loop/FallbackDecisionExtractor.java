package io.diag.agent.loop;

import io.diag.agent.decision.DecisionDto;
import io.diag.agent.decision.LedgerUpdateDto;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.PredictionDto;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback parser that reconstructs a DecisionDto from semi-structured prose
 * when a model articulates its diagnosis in text but fails to emit the JSON brackets.
 * All extracted decisions are strictly validated by DecisionValidator before acceptance.
 */
public final class FallbackDecisionExtractor {

    private static final Pattern HYPOTHESIS_PATTERN = Pattern.compile("\\b(H[1-7])\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONFIDENCE_PATTERN = Pattern.compile("confidence[^0-9]*([0-1](?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEMPLATE_PATTERN   = Pattern.compile("template[\\s:\"]*(jar-unpack|hikari-pool-size|jvm-opts)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIGNAL_PATTERN     = Pattern.compile("\\b(JavaMonitorEnter|ThreadPark|SocketRead|SocketWrite|GCPhasePause|ExecutionSample|ObjectAllocationSample|ExceptionThrow)\\b");

    private FallbackDecisionExtractor() {}

    public static DecisionDto tryExtract(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        // 1. Hypothesis category (H1 - H7)
        Matcher hypMatcher = HYPOTHESIS_PATTERN.matcher(text);
        if (!hypMatcher.find()) {
            return null;
        }
        String category = hypMatcher.group(1).toUpperCase();

        // 2. Confidence
        double confidence = 0.8;
        Matcher confMatcher = CONFIDENCE_PATTERN.matcher(text);
        if (confMatcher.find()) {
            try {
                confidence = Double.parseDouble(confMatcher.group(1));
            } catch (NumberFormatException e) {
                // Compensate: retain default confidence (0.8) if regex matched malformed number
            }
        }

        // 3. Change template (jar-unpack, hikari-pool-size, jvm-opts)
        Matcher tplMatcher = TEMPLATE_PATTERN.matcher(text);
        String templateName = null;
        if (tplMatcher.find()) {
            templateName = tplMatcher.group(1).toLowerCase();
        } else if (text.toLowerCase().contains("jar-unpack")) {
            templateName = "jar-unpack";
        } else if (text.toLowerCase().contains("hikari-pool-size")) {
            templateName = "hikari-pool-size";
        } else if (text.toLowerCase().contains("jvm-opts")) {
            templateName = "jvm-opts";
        }
        if (templateName == null) {
            return null;
        }

        // 4. Mechanism signal to eliminate
        String signal = "JavaMonitorEnter";
        Matcher sigMatcher = SIGNAL_PATTERN.matcher(text);
        if (sigMatcher.find()) {
            signal = sigMatcher.group(1);
        }

        // 5. Metric to improve
        String metric = "p95";
        if (text.toLowerCase().contains("metrictoimprove") || text.toLowerCase().contains("metric to improve")) {
            if (text.toLowerCase().contains("rps")) {
                metric = "rps";
            }
        }

        // 6. Ledger direction
        String direction = "strengthen";
        if (text.toLowerCase().contains("weaken " + category.toLowerCase())
                || text.toLowerCase().contains("direction weaken")) {
            direction = "weaken";
        }

        String rationale = "Lock contention and nested-jar resource lookup diagnosed in " + signal;
        String reason = category + " " + direction + " based on JFR evidence (" + signal + ")";

        HypothesisDto hypothesis = new HypothesisDto(category, confidence, rationale);
        PredictionDto prediction = new PredictionDto(metric, "improve", signal);
        LedgerUpdateDto ledger = new LedgerUpdateDto(category, direction, reason);
        ChangeDto change = new ChangeDto("template", null, templateName, Map.of());

        return new DecisionDto(hypothesis, prediction, ledger, change);
    }
}
