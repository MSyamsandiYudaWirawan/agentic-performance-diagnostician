package io.diag.eval.service.impl;

import io.diag.eval.model.MatrixScoreReport;
import io.diag.eval.model.RunScore;
import io.diag.eval.service.EvalScorer;
import io.diag.eval.service.HtmlReportGenerator;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.entity.Run;
import io.diag.evidence.entity.Target;
import io.diag.evidence.entity.TrajectoryEvent;
import io.diag.evidence.repository.IterationRepository;
import io.diag.evidence.repository.LoadReportRepository;
import io.diag.evidence.repository.RunRepository;
import io.diag.evidence.repository.TrajectoryEventRepository;
import io.diag.evidence.service.TargetRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure-Java offline HTML report generator (§10.26, TigerStyle compliant).
 * Produces self-contained reports with embedded CSS, inline SVG charts,
 * timeline tables, trajectory event logs, and zero CDN dependencies.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HtmlReportGeneratorImpl implements HtmlReportGenerator {

    private final RunRepository runRepository;
    private final IterationRepository iterationRepository;
    private final LoadReportRepository loadReportRepository;
    private final TrajectoryEventRepository trajectoryEventRepository;
    private final TargetRegistry targetRegistry;
    private final EvalScorer evalScorer;

    @Override
    public String generateRunReport(String runId) {
        Objects.requireNonNull(runId, "runId must not be null");

        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        RunScore score = evalScorer.scoreRun(runId);
        List<Iteration> iterations = iterationRepository.findByRunIdOrderByNAsc(runId);
        List<LoadReport> loadReports = loadReportRepository.findByRunIdOrderByIdAsc(runId);
        List<TrajectoryEvent> trajectoryEvents = trajectoryEventRepository.findByRunIdOrderByTsAsc(runId);

        Map<Long, LoadReport> loadReportMap = new HashMap<>();
        for (LoadReport lr : loadReports) {
            loadReportMap.put(lr.getId(), lr);
        }

        String targetName = run.getTargetId();
        Optional<Target> targetOpt = targetRegistry.findById(run.getTargetId());
        if (targetOpt.isPresent()) {
            targetName = targetOpt.get().getName() + " (" + run.getTargetId() + ")";
        }

        StringBuilder html = new StringBuilder(16384);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("<title>Run Report — ").append(escapeHtml(runId)).append("</title>\n");
        appendEmbeddedStyles(html);
        html.append("</head>\n<body>\n<div class=\"container\">\n");

        // Header section
        appendRunHeader(html, run, targetName, score);

        // Scorecard summary cards
        appendScorecardCards(html, score);

        // SVG Charts section
        appendVisualCharts(html, score);

        // Iteration Timeline Table
        appendIterationTimeline(html, iterations, loadReportMap);

        // Trajectory Event Log Table
        appendTrajectoryEventLog(html, trajectoryEvents);

        // Footer
        html.append("<div class=\"footer\">\n");
        html.append("<p>Agentic Performance Diagnostician — Report generated offline on ")
                .append(escapeHtml(Instant.now().toString()))
                .append("</p>\n</div>\n");

        html.append("</div>\n</body>\n</html>");
        return html.toString();
    }

    @Override
    public Path exportRunReport(String runId, Path destinationFile) throws IOException {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(destinationFile, "destinationFile must not be null");

        Path parent = destinationFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        String content = generateRunReport(runId);
        Files.writeString(destinationFile, content, StandardCharsets.UTF_8);
        log.info("Exported HTML run report for {} to {}", runId, destinationFile);
        return destinationFile;
    }

    @Override
    public String generateMatrixReport(List<String> runIds) {
        Objects.requireNonNull(runIds, "runIds must not be null");

        MatrixScoreReport matrixReport = evalScorer.scoreRuns(runIds);

        StringBuilder html = new StringBuilder(16384);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("<title>Evaluation Matrix Report</title>\n");
        appendEmbeddedStyles(html);
        html.append("</head>\n<body>\n<div class=\"container\">\n");

        // Matrix Header
        html.append("<div class=\"header\">\n");
        html.append("<div>\n");
        html.append("<h1>Evaluation Matrix Scorecard</h1>\n");
        html.append("<div class=\"subtitle\">Runs evaluated: ").append(runIds.size()).append("</div>\n");
        html.append("</div>\n");
        html.append("<div>\n");
        if (matrixReport.passesV1Threshold()) {
            html.append("<span class=\"badge badge-success badge-lg\">PASSES V1 THRESHOLD</span>\n");
        } else {
            html.append("<span class=\"badge badge-danger badge-lg\">BELOW V1 THRESHOLD</span>\n");
        }
        html.append("</div>\n</div>\n");

        // Summary metric cards
        html.append("<div class=\"metric-grid\">\n");

        html.append("<div class=\"metric-card\">\n");
        html.append("<div class=\"metric-label\">Total Targets</div>\n");
        html.append("<div class=\"metric-value\">").append(matrixReport.totalTargets()).append("</div>\n");
        html.append("<div class=\"metric-subtext\">Distinct targets scored</div>\n");
        html.append("</div>\n");

        html.append("<div class=\"metric-card\">\n");
        html.append("<div class=\"metric-label\">Accuracy Rate</div>\n");
        String accPct = String.format(Locale.US, "%.1f%%", matrixReport.accuracyRate() * 100.0);
        html.append("<div class=\"metric-value\">").append(accPct).append("</div>\n");
        html.append("<div class=\"metric-subtext\">").append(matrixReport.accurateCount())
                .append(" of ").append(matrixReport.totalTargets()).append(" accurate (Threshold: &ge; 70%)</div>\n");
        html.append("</div>\n");

        html.append("<div class=\"metric-card\">\n");
        html.append("<div class=\"metric-label\">Convergence Rate</div>\n");
        String convPct = String.format(Locale.US, "%.1f%%", matrixReport.convergenceRate() * 100.0);
        html.append("<div class=\"metric-value\">").append(convPct).append("</div>\n");
        html.append("<div class=\"metric-subtext\">").append(matrixReport.convergedCount())
                .append(" of ").append(matrixReport.totalTargets()).append(" converged (Threshold: &ge; 2)</div>\n");
        html.append("</div>\n");

        html.append("</div>\n");

        // Runs Table
        html.append("<div class=\"section-title\">Matrix Run Results</div>\n");
        html.append("<table class=\"data-table\">\n<thead>\n<tr>\n");
        html.append("<th>Target</th><th>Run ID</th><th>Status</th><th>Diagnosed vs Ground Truth</th>");
        html.append("<th>Accurate</th><th>Converged</th><th>Baseline p95</th><th>Final p95</th><th>p95 Delta</th>");
        html.append("<th>Baseline RPS</th><th>Final RPS</th><th>Iterations</th><th>Tokens</th><th>Cost</th>\n");
        html.append("</tr>\n</thead>\n<tbody>\n");

        for (RunScore rs : matrixReport.runScores()) {
            html.append("<tr>\n");
            html.append("<td><strong>").append(escapeHtml(rs.targetId())).append("</strong></td>\n");
            html.append("<td><code>").append(escapeHtml(rs.runId())).append("</code></td>\n");
            html.append("<td><span class=\"badge ").append(statusBadgeClass(rs.status())).append("\">")
                    .append(escapeHtml(rs.status())).append("</span></td>\n");
            html.append("<td>").append(escapeHtml(rs.diagnosedCategory())).append(" / ")
                    .append(escapeHtml(rs.groundTruthCategory())).append("</td>\n");

            if (rs.accurate()) {
                html.append("<td><span class=\"badge badge-success\">YES</span></td>\n");
            } else {
                html.append("<td><span class=\"badge badge-danger\">NO</span></td>\n");
            }

            if (rs.converged()) {
                html.append("<td><span class=\"badge badge-success\">YES</span></td>\n");
            } else {
                html.append("<td><span class=\"badge badge-warning\">NO</span></td>\n");
            }

            html.append("<td>").append(String.format(Locale.US, "%.1f ms", rs.baselineP95Ms())).append("</td>\n");
            html.append("<td>").append(String.format(Locale.US, "%.1f ms", rs.finalP95Ms())).append("</td>\n");

            String deltaClass = rs.p95DeltaMs() < 0 ? "text-success" : (rs.p95DeltaMs() > 0 ? "text-danger" : "");
            html.append("<td class=\"").append(deltaClass).append("\">")
                    .append(String.format(Locale.US, "%+.1f ms", rs.p95DeltaMs())).append("</td>\n");

            html.append("<td>").append(String.format(Locale.US, "%.1f", rs.baselineRps())).append("</td>\n");
            html.append("<td>").append(String.format(Locale.US, "%.1f", rs.finalRps())).append("</td>\n");
            html.append("<td>").append(rs.iterationsUsed()).append("</td>\n");
            html.append("<td>").append(String.format(Locale.US, "%,d", rs.totalTokens())).append("</td>\n");
            html.append("<td>$").append(rs.totalCostUsd() != null ? rs.totalCostUsd().toPlainString() : "0.00").append("</td>\n");
            html.append("</tr>\n");
        }

        html.append("</tbody>\n</table>\n");

        html.append("<div class=\"footer\">\n");
        html.append("<p>Agentic Performance Diagnostician — Matrix Report generated offline on ")
                .append(escapeHtml(Instant.now().toString()))
                .append("</p>\n</div>\n");

        html.append("</div>\n</body>\n</html>");
        return html.toString();
    }

    // -------------------------------------------------------------------------
    // Run Report Subcomponents
    // -------------------------------------------------------------------------

    private void appendRunHeader(StringBuilder html, Run run, String targetName, RunScore score) {
        html.append("<div class=\"header\">\n");
        html.append("<div>\n");
        html.append("<h1>Run Diagnostic Report</h1>\n");
        html.append("<div class=\"subtitle\">Run ID: <code>").append(escapeHtml(run.getId())).append("</code></div>\n");
        html.append("</div>\n");
        html.append("<div>\n");
        html.append("<span class=\"badge ").append(statusBadgeClass(run.getStatus())).append(" badge-lg\">")
                .append(escapeHtml(run.getStatus())).append("</span>\n");
        html.append("</div>\n</div>\n");

        html.append("<div class=\"meta-card\">\n");
        html.append("<div class=\"meta-item\"><span class=\"meta-label\">Target:</span> <strong>")
                .append(escapeHtml(targetName)).append("</strong></div>\n");
        html.append("<div class=\"meta-item\"><span class=\"meta-label\">Model / Provider:</span> ")
                .append(escapeHtml(run.getProvider() != null ? run.getProvider() : "default")).append(" / ")
                .append(escapeHtml(run.getModel() != null ? run.getModel() : "default")).append("</div>\n");
        html.append("<div class=\"meta-item\"><span class=\"meta-label\">Prompt Hash:</span> <code>")
                .append(escapeHtml(run.getPromptHash() != null ? run.getPromptHash() : "-")).append("</code></div>\n");

        String durationStr = "-";
        if (run.getStartedAt() != null && run.getFinishedAt() != null) {
            long sec = Duration.between(run.getStartedAt(), run.getFinishedAt()).toSeconds();
            durationStr = sec + " seconds";
        }
        html.append("<div class=\"meta-item\"><span class=\"meta-label\">Duration:</span> ").append(durationStr).append("</div>\n");
        html.append("<div class=\"meta-item\"><span class=\"meta-label\">Started At:</span> ")
                .append(escapeHtml(run.getStartedAt() != null ? run.getStartedAt().toString() : "-")).append("</div>\n");
        html.append("<div class=\"meta-item\"><span class=\"meta-label\">Finished At:</span> ")
                .append(escapeHtml(run.getFinishedAt() != null ? run.getFinishedAt().toString() : "-")).append("</div>\n");
        html.append("</div>\n");
    }

    private void appendScorecardCards(StringBuilder html, RunScore score) {
        html.append("<div class=\"section-title\">Diagnostic Scorecard</div>\n");
        html.append("<div class=\"metric-grid\">\n");

        // Card 1: Accuracy
        html.append("<div class=\"metric-card\">\n");
        html.append("<div class=\"metric-label\">Accuracy (Root Cause)</div>\n");
        if (score.accurate()) {
            html.append("<div class=\"metric-value text-success\">ACCURATE</div>\n");
        } else {
            html.append("<div class=\"metric-value text-danger\">INACCURATE</div>\n");
        }
        html.append("<div class=\"metric-subtext\">Diagnosed: <strong>").append(escapeHtml(score.diagnosedCategory()))
                .append("</strong> | Ground Truth: <strong>").append(escapeHtml(score.groundTruthCategory())).append("</strong></div>\n");
        html.append("</div>\n");

        // Card 2: Convergence
        html.append("<div class=\"metric-card\">\n");
        html.append("<div class=\"metric-label\">Convergence (&gt; Noise Floor)</div>\n");
        if (score.converged()) {
            html.append("<div class=\"metric-value text-success\">CONVERGED</div>\n");
        } else {
            html.append("<div class=\"metric-value text-warning\">NOT CONVERGED</div>\n");
        }
        html.append("<div class=\"metric-subtext\">Noise Floor: ")
                .append(String.format(Locale.US, "%.1f ms", score.noiseFloorMs()))
                .append(" (P95 &Delta;: ")
                .append(String.format(Locale.US, "%+.1f ms", score.p95DeltaMs())).append(")</div>\n");
        html.append("</div>\n");

        // Card 3: Latency & Throughput
        html.append("<div class=\"metric-card\">\n");
        html.append("<div class=\"metric-label\">Latency & Throughput</div>\n");
        String p95Change = String.format(Locale.US, "%.1f ms &rarr; %.1f ms", score.baselineP95Ms(), score.finalP95Ms());
        html.append("<div class=\"metric-value\">").append(p95Change).append("</div>\n");
        html.append("<div class=\"metric-subtext\">RPS: ")
                .append(String.format(Locale.US, "%.1f &rarr; %.1f (%+.1f RPS)", score.baselineRps(), score.finalRps(), score.rpsDelta()))
                .append("</div>\n");
        html.append("</div>\n");

        // Card 4: Efficiency
        html.append("<div class=\"metric-card\">\n");
        html.append("<div class=\"metric-label\">Efficiency & Cost</div>\n");
        html.append("<div class=\"metric-value\">")
                .append(score.iterationsUsed()).append(" iter").append(score.iterationsUsed() == 1 ? "" : "s")
                .append("</div>\n");
        html.append("<div class=\"metric-subtext\">Tokens: ")
                .append(String.format(Locale.US, "%,d", score.totalTokens()))
                .append(" | Cost: $")
                .append(score.totalCostUsd() != null ? score.totalCostUsd().toPlainString() : "0.00")
                .append("</div>\n");
        html.append("</div>\n");

        html.append("</div>\n");
    }

    private void appendVisualCharts(StringBuilder html, RunScore score) {
        html.append("<div class=\"section-title\">Visual Comparison (Before vs After)</div>\n");
        html.append("<div class=\"chart-grid\">\n");

        // SVG Chart 1: Latency
        html.append("<div class=\"chart-card\">\n");
        html.append("<div class=\"chart-title\">p95 Latency (Lower is Better)</div>\n");
        html.append(renderLatencySvg(score.baselineP95Ms(), score.finalP95Ms(), score.noiseFloorMs()));
        html.append("</div>\n");

        // SVG Chart 2: Throughput
        html.append("<div class=\"chart-card\">\n");
        html.append("<div class=\"chart-title\">Throughput RPS (Higher is Better)</div>\n");
        html.append(renderThroughputSvg(score.baselineRps(), score.finalRps()));
        html.append("</div>\n");

        html.append("</div>\n");
    }

    private void appendIterationTimeline(StringBuilder html, List<Iteration> iterations, Map<Long, LoadReport> loadReportMap) {
        html.append("<div class=\"section-title\">Iteration Timeline</div>\n");

        if (iterations.isEmpty()) {
            html.append("<p class=\"text-muted\">No iterations recorded for this run.</p>\n");
            return;
        }

        html.append("<table class=\"data-table\">\n<thead>\n<tr>\n");
        html.append("<th>#</th><th>Hypothesis</th><th>Change Details</th><th>Outcome</th><th>p95 Latency</th><th>RPS</th><th>Finding Summary</th><th>Artifacts</th>\n");
        html.append("</tr>\n</thead>\n<tbody>\n");

        for (Iteration iter : iterations) {
            html.append("<tr>\n");
            html.append("<td><strong>").append(iter.getN()).append("</strong></td>\n");

            // Hypothesis
            html.append("<td>");
            if (iter.getHypothesis() != null) {
                html.append("<span class=\"badge badge-info\">").append(escapeHtml(iter.getHypothesis().category())).append("</span> ");
                String conf = String.format(Locale.US, "%.0f%%", iter.getHypothesis().confidence() * 100.0);
                html.append("<small>(").append(conf).append(")</small><br>");
                html.append("<small class=\"text-muted\">").append(escapeHtml(iter.getHypothesis().rationale())).append("</small>");
            } else {
                html.append("-");
            }
            html.append("</td>\n");

            // Change
            html.append("<td>");
            if (iter.getChange() != null) {
                html.append("<code>").append(escapeHtml(iter.getChange().kind())).append("</code>");
                if (iter.getChange().template() != null) {
                    html.append("<br><small>Template: ").append(escapeHtml(iter.getChange().template())).append("</small>");
                }
            } else {
                html.append("-");
            }
            html.append("</td>\n");

            // Outcome
            html.append("<td><span class=\"badge ").append(outcomeBadgeClass(iter.getOutcome())).append("\">")
                    .append(escapeHtml(iter.getOutcome())).append("</span>");
            if (iter.getKeepType() != null) {
                html.append("<br><small class=\"text-muted\">").append(escapeHtml(iter.getKeepType())).append("</small>");
            }
            html.append("</td>\n");

            // Load Report Metrics
            LoadReport lr = iter.getLoadReportId() != null ? loadReportMap.get(iter.getLoadReportId()) : null;
            if (lr != null && lr.getPayload() != null && lr.getPayload().latency() != null) {
                html.append("<td>").append(String.format(Locale.US, "%.1f ms", lr.getPayload().latency().p95())).append("</td>\n");
                html.append("<td>").append(String.format(Locale.US, "%.1f", lr.getPayload().rps())).append("</td>\n");
            } else {
                html.append("<td>-</td><td>-</td>\n");
            }

            // Finding
            html.append("<td><small>").append(escapeHtml(iter.getFinding() != null ? iter.getFinding() : "-")).append("</small></td>\n");

            // Artifact links
            html.append("<td>");
            if (lr != null && lr.getK6SummaryPath() != null) {
                html.append("<a class=\"artifact-link\" href=\"").append(escapeHtml(lr.getK6SummaryPath())).append("\">k6-summary</a> ");
            }
            if (iter.getJfrReportId() != null) {
                html.append("<span class=\"badge badge-neutral\">JFR #").append(iter.getJfrReportId()).append("</span>");
            }
            html.append("</td>\n");

            html.append("</tr>\n");
        }

        html.append("</tbody>\n</table>\n");
    }

    private void appendTrajectoryEventLog(StringBuilder html, List<TrajectoryEvent> events) {
        html.append("<div class=\"section-title\">Trajectory Event Stream (")
                .append(events.size()).append(" events)</div>\n");

        if (events.isEmpty()) {
            html.append("<p class=\"text-muted\">No trajectory events logged for this run.</p>\n");
            return;
        }

        html.append("<table class=\"data-table\">\n<thead>\n<tr>\n");
        html.append("<th>Timestamp</th><th>Kind</th><th>Tokens (In / Out)</th><th>Cost ($)</th><th>Payload Summary</th>\n");
        html.append("</tr>\n</thead>\n<tbody>\n");

        for (TrajectoryEvent ev : events) {
            html.append("<tr>\n");
            html.append("<td><code>").append(ev.getTs() != null ? escapeHtml(ev.getTs().toString()) : "-").append("</code></td>\n");
            html.append("<td><span class=\"badge badge-info\">").append(escapeHtml(ev.getKind())).append("</span></td>\n");

            String tokens = "-";
            if (ev.getTokensIn() != null || ev.getTokensOut() != null) {
                long in = ev.getTokensIn() != null ? ev.getTokensIn() : 0L;
                long out = ev.getTokensOut() != null ? ev.getTokensOut() : 0L;
                tokens = String.format(Locale.US, "%d / %d", in, out);
            }
            html.append("<td>").append(tokens).append("</td>\n");

            String cost = "-";
            if (ev.getCostUsd() != null) {
                cost = "$" + ev.getCostUsd().toPlainString();
            }
            html.append("<td>").append(cost).append("</td>\n");

            // Payload summary
            String payloadStr = "-";
            if (ev.getPayload() != null) {
                payloadStr = ev.getPayload().toString();
                if (payloadStr.length() > 140) {
                    payloadStr = payloadStr.substring(0, 137) + "...";
                }
            }
            html.append("<td><code>").append(escapeHtml(payloadStr)).append("</code></td>\n");
            html.append("</tr>\n");
        }

        html.append("</tbody>\n</table>\n");
    }

    // -------------------------------------------------------------------------
    // Visual Charts Rendering (Pure Inline SVG, No External CDNs)
    // -------------------------------------------------------------------------

    private String renderLatencySvg(double baselineP95, double finalP95, double noiseFloor) {
        double maxVal = Math.max(Math.max(baselineP95, finalP95), noiseFloor) * 1.25;
        if (maxVal <= 0.001) {
            maxVal = 100.0;
        }

        int svgWidth = 440;
        int svgHeight = 180;
        int chartTop = 20;
        int chartBottom = 140;
        int chartHeight = chartBottom - chartTop;

        int bar1Height = (int) Math.round((baselineP95 / maxVal) * chartHeight);
        int bar2Height = (int) Math.round((finalP95 / maxVal) * chartHeight);

        int bar1Y = chartBottom - bar1Height;
        int bar2Y = chartBottom - bar2Height;

        int floorY = chartBottom - (int) Math.round((noiseFloor / maxVal) * chartHeight);

        StringBuilder svg = new StringBuilder(1024);
        svg.append("<svg viewBox=\"0 0 440 180\" width=\"100%\" height=\"180\" xmlns=\"http://www.w3.org/2000/svg\">\n");

        // Baseline Axis
        svg.append("  <line x1=\"40\" y1=\"").append(chartBottom).append("\" x2=\"400\" y2=\"").append(chartBottom)
                .append("\" stroke=\"#475569\" stroke-width=\"1\"/>\n");

        // Noise floor dashed line
        svg.append("  <line x1=\"40\" y1=\"").append(floorY).append("\" x2=\"400\" y2=\"").append(floorY)
                .append("\" stroke=\"#f59e0b\" stroke-width=\"1.5\" stroke-dasharray=\"4\"/>\n");
        svg.append("  <text x=\"405\" y=\"").append(floorY + 4)
                .append("\" fill=\"#f59e0b\" font-size=\"11\" font-family=\"monospace\">Floor</text>\n");

        // Bar 1: Baseline
        svg.append("  <rect x=\"100\" y=\"").append(bar1Y).append("\" width=\"70\" height=\"").append(bar1Height)
                .append("\" rx=\"4\" fill=\"#64748b\"/>\n");
        svg.append("  <text x=\"135\" y=\"").append(bar1Y - 6)
                .append("\" fill=\"#f8fafc\" font-size=\"12\" font-weight=\"bold\" text-anchor=\"middle\">")
                .append(String.format(Locale.US, "%.1f", baselineP95)).append("</text>\n");
        svg.append("  <text x=\"135\" y=\"160\" fill=\"#94a3b8\" font-size=\"12\" text-anchor=\"middle\">Baseline</text>\n");

        // Bar 2: Final
        String bar2Color = finalP95 <= baselineP95 ? "#22c55e" : "#ef4444";
        svg.append("  <rect x=\"230\" y=\"").append(bar2Y).append("\" width=\"70\" height=\"").append(bar2Height)
                .append("\" rx=\"4\" fill=\"").append(bar2Color).append("\"/>\n");
        svg.append("  <text x=\"265\" y=\"").append(bar2Y - 6)
                .append("\" fill=\"#f8fafc\" font-size=\"12\" font-weight=\"bold\" text-anchor=\"middle\">")
                .append(String.format(Locale.US, "%.1f", finalP95)).append("</text>\n");
        svg.append("  <text x=\"265\" y=\"160\" fill=\"#94a3b8\" font-size=\"12\" text-anchor=\"middle\">Final Kept</text>\n");

        svg.append("</svg>\n");
        return svg.toString();
    }

    private String renderThroughputSvg(double baselineRps, double finalRps) {
        double maxVal = Math.max(baselineRps, finalRps) * 1.25;
        if (maxVal <= 0.001) {
            maxVal = 100.0;
        }

        int svgWidth = 440;
        int svgHeight = 180;
        int chartTop = 20;
        int chartBottom = 140;
        int chartHeight = chartBottom - chartTop;

        int bar1Height = (int) Math.round((baselineRps / maxVal) * chartHeight);
        int bar2Height = (int) Math.round((finalRps / maxVal) * chartHeight);

        int bar1Y = chartBottom - bar1Height;
        int bar2Y = chartBottom - bar2Height;

        StringBuilder svg = new StringBuilder(1024);
        svg.append("<svg viewBox=\"0 0 440 180\" width=\"100%\" height=\"180\" xmlns=\"http://www.w3.org/2000/svg\">\n");

        // Baseline Axis
        svg.append("  <line x1=\"40\" y1=\"").append(chartBottom).append("\" x2=\"400\" y2=\"").append(chartBottom)
                .append("\" stroke=\"#475569\" stroke-width=\"1\"/>\n");

        // Bar 1: Baseline RPS
        svg.append("  <rect x=\"100\" y=\"").append(bar1Y).append("\" width=\"70\" height=\"").append(bar1Height)
                .append("\" rx=\"4\" fill=\"#64748b\"/>\n");
        svg.append("  <text x=\"135\" y=\"").append(bar1Y - 6)
                .append("\" fill=\"#f8fafc\" font-size=\"12\" font-weight=\"bold\" text-anchor=\"middle\">")
                .append(String.format(Locale.US, "%.1f", baselineRps)).append("</text>\n");
        svg.append("  <text x=\"135\" y=\"160\" fill=\"#94a3b8\" font-size=\"12\" text-anchor=\"middle\">Baseline</text>\n");

        // Bar 2: Final RPS
        String bar2Color = finalRps >= baselineRps ? "#38bdf8" : "#ef4444";
        svg.append("  <rect x=\"230\" y=\"").append(bar2Y).append("\" width=\"70\" height=\"").append(bar2Height)
                .append("\" rx=\"4\" fill=\"").append(bar2Color).append("\"/>\n");
        svg.append("  <text x=\"265\" y=\"").append(bar2Y - 6)
                .append("\" fill=\"#f8fafc\" font-size=\"12\" font-weight=\"bold\" text-anchor=\"middle\">")
                .append(String.format(Locale.US, "%.1f", finalRps)).append("</text>\n");
        svg.append("  <text x=\"265\" y=\"160\" fill=\"#94a3b8\" font-size=\"12\" text-anchor=\"middle\">Final Kept</text>\n");

        svg.append("</svg>\n");
        return svg.toString();
    }

    // -------------------------------------------------------------------------
    // CSS & Layout Helper
    // -------------------------------------------------------------------------

    private void appendEmbeddedStyles(StringBuilder html) {
        html.append("<style>\n");
        html.append("  :root {\n");
        html.append("    --bg: #0b0f19;\n");
        html.append("    --surface: #1e293b;\n");
        html.append("    --surface-border: #334155;\n");
        html.append("    --text-primary: #f8fafc;\n");
        html.append("    --text-secondary: #94a3b8;\n");
        html.append("    --color-success: #22c55e;\n");
        html.append("    --color-danger: #ef4444;\n");
        html.append("    --color-warning: #f59e0b;\n");
        html.append("    --color-info: #38bdf8;\n");
        html.append("  }\n");
        html.append("  * { box-sizing: border-box; margin: 0; padding: 0; }\n");
        html.append("  body {\n");
        html.append("    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;\n");
        html.append("    background-color: var(--bg);\n");
        html.append("    color: var(--text-primary);\n");
        html.append("    line-height: 1.5;\n");
        html.append("    padding: 32px 20px;\n");
        html.append("  }\n");
        html.append("  .container { max-width: 1200px; margin: 0 auto; }\n");
        html.append("  .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }\n");
        html.append("  h1 { font-size: 26px; font-weight: 700; color: #fff; }\n");
        html.append("  .subtitle { font-size: 14px; color: var(--text-secondary); margin-top: 4px; }\n");
        html.append("  .meta-card {\n");
        html.append("    background: var(--surface);\n");
        html.append("    border: 1px solid var(--surface-border);\n");
        html.append("    border-radius: 8px;\n");
        html.append("    padding: 16px 20px;\n");
        html.append("    display: grid;\n");
        html.append("    grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));\n");
        html.append("    gap: 12px;\n");
        html.append("    margin-bottom: 28px;\n");
        html.append("  }\n");
        html.append("  .meta-item { font-size: 13px; color: var(--text-primary); }\n");
        html.append("  .meta-label { color: var(--text-secondary); font-weight: 600; margin-right: 6px; }\n");
        html.append("  .section-title { font-size: 18px; font-weight: 600; margin: 28px 0 14px; color: #fff; border-bottom: 1px solid var(--surface-border); padding-bottom: 6px; }\n");
        html.append("  .metric-grid {\n");
        html.append("    display: grid;\n");
        html.append("    grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));\n");
        html.append("    gap: 16px;\n");
        html.append("    margin-bottom: 24px;\n");
        html.append("  }\n");
        html.append("  .metric-card {\n");
        html.append("    background: var(--surface);\n");
        html.append("    border: 1px solid var(--surface-border);\n");
        html.append("    border-radius: 8px;\n");
        html.append("    padding: 18px 20px;\n");
        html.append("  }\n");
        html.append("  .metric-label { font-size: 12px; font-weight: 600; text-transform: uppercase; color: var(--text-secondary); letter-spacing: 0.05em; margin-bottom: 6px; }\n");
        html.append("  .metric-value { font-size: 22px; font-weight: 700; color: #fff; margin-bottom: 4px; }\n");
        html.append("  .metric-subtext { font-size: 12px; color: var(--text-secondary); }\n");
        html.append("  .chart-grid {\n");
        html.append("    display: grid;\n");
        html.append("    grid-template-columns: repeat(auto-fit, minmax(400px, 1fr));\n");
        html.append("    gap: 20px;\n");
        html.append("    margin-bottom: 28px;\n");
        html.append("  }\n");
        html.append("  .chart-card {\n");
        html.append("    background: var(--surface);\n");
        html.append("    border: 1px solid var(--surface-border);\n");
        html.append("    border-radius: 8px;\n");
        html.append("    padding: 20px;\n");
        html.append("  }\n");
        html.append("  .chart-title { font-size: 14px; font-weight: 600; color: var(--text-secondary); margin-bottom: 12px; }\n");
        html.append("  .data-table {\n");
        html.append("    width: 100%;\n");
        html.append("    border-collapse: collapse;\n");
        html.append("    background: var(--surface);\n");
        html.append("    border: 1px solid var(--surface-border);\n");
        html.append("    border-radius: 8px;\n");
        html.append("    overflow: hidden;\n");
        html.append("    margin-bottom: 28px;\n");
        html.append("    font-size: 13px;\n");
        html.append("  }\n");
        html.append("  .data-table th, .data-table td { padding: 12px 14px; text-align: left; border-bottom: 1px solid var(--surface-border); }\n");
        html.append("  .data-table th { background: #151d2f; font-weight: 600; color: var(--text-secondary); font-size: 12px; text-transform: uppercase; }\n");
        html.append("  .data-table tr:hover { background: #26334d; }\n");
        html.append("  .badge { display: inline-block; padding: 3px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; }\n");
        html.append("  .badge-lg { padding: 6px 14px; font-size: 13px; }\n");
        html.append("  .badge-success { background: #064e3b; color: #34d399; border: 1px solid #059669; }\n");
        html.append("  .badge-danger { background: #450a0a; color: #f87171; border: 1px solid #dc2626; }\n");
        html.append("  .badge-warning { background: #451a03; color: #fbbf24; border: 1px solid #d97706; }\n");
        html.append("  .badge-info { background: #082f49; color: #38bdf8; border: 1px solid #0284c7; }\n");
        html.append("  .badge-neutral { background: #334155; color: #cbd5e1; }\n");
        html.append("  .text-success { color: var(--color-success); }\n");
        html.append("  .text-danger { color: var(--color-danger); }\n");
        html.append("  .text-warning { color: var(--color-warning); }\n");
        html.append("  .text-muted { color: var(--text-secondary); }\n");
        html.append("  code { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 12px; background: #0f172a; padding: 2px 6px; border-radius: 4px; border: 1px solid #334155; }\n");
        html.append("  .artifact-link { color: #38bdf8; text-decoration: none; }\n");
        html.append("  .artifact-link:hover { text-decoration: underline; }\n");
        html.append("  .footer { margin-top: 40px; text-align: center; font-size: 12px; color: var(--text-secondary); border-top: 1px solid var(--surface-border); padding-top: 20px; }\n");
        html.append("</style>\n");
    }

    private static String statusBadgeClass(String status) {
        if (status == null) return "badge-neutral";
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "COMPLETED" -> "badge-success";
            case "FAILED" -> "badge-danger";
            case "ABORTED" -> "badge-warning";
            case "RUNNING" -> "badge-info";
            default -> "badge-neutral";
        };
    }

    private static String outcomeBadgeClass(String outcome) {
        if (outcome == null) return "badge-neutral";
        return switch (outcome.toUpperCase(Locale.ROOT)) {
            case "KEPT" -> "badge-success";
            case "REVERTED" -> "badge-warning";
            case "FAILED", "ERROR" -> "badge-danger";
            default -> "badge-neutral";
        };
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
