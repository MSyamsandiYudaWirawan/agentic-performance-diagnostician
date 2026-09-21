package io.diag.runner.service;

import io.diag.evidence.dto.JfrDiffDto;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.SignalDeltaDto;
import io.diag.evidence.dto.SignalSummaryDto;
import io.diag.runner.config.Thresholds;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

public final class JfrAnalyzer {

    private static final Set<String> JDK_PREFIXES = Set.of(
            "java.", "jdk.", "sun.", "javax.", "com.sun.", "org.graalvm."
    );
    private static final int TOP_N_METHODS  = 15;
    private static final int RAW_FRAMES_PER = 3;
    private static final int TOP_N_CLASSES  = 5;

    private final Thresholds thresholds;

    public JfrAnalyzer(Thresholds thresholds) {
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds must not be null");
    }

    public JfrReportDto analyze(Path jfrPath, JfrReportDto previous, String runId, String label)
            throws IOException {
        Objects.requireNonNull(jfrPath, "jfrPath must not be null");
        Objects.requireNonNull(runId,   "runId must not be null");
        Objects.requireNonNull(label,   "label must not be null");

        List<Duration> lockDur        = new ArrayList<>();
        Map<String, Long> lockClasses = new LinkedHashMap<>();

        List<Duration> parkDur         = new ArrayList<>();
        // park top table: business frame → count (stack-walked, same as ExecutionSample).
        // parkedClass is the synchronizer mechanism (e.g. AQS$ConditionObject) — it appears
        // for every park everywhere and doesn't localize the call site. The business frame
        // shows WHERE in application code the park happened (e.g. HikariPool.getConnection),
        // which is what S2 pool-starvation diagnosis needs.
        Map<String, Long> parkBizFrames = new LinkedHashMap<>();

        List<Duration> socketReadDur  = new ArrayList<>();
        List<Duration> socketWriteDur = new ArrayList<>();

        List<Duration> gcDur = new ArrayList<>();

        // business-frame (first non-JDK frame) → sample count
        Map<String, Long> cpuByBizFrame = new LinkedHashMap<>();
        // top-3-raw-frame signature → sample count
        Map<String, long[]> stackCount  = new LinkedHashMap<>();
        // top-3-raw-frame signature → raw frame list (first occurrence kept)
        Map<String, List<String>> stackFrames = new LinkedHashMap<>();

        Map<String, Long> allocClasses = new LinkedHashMap<>();
        Map<String, Long> exClasses    = new LinkedHashMap<>();

        // Reused per ExecutionSample to avoid allocating a new StringBuilder each event.
        StringBuilder sigBuilder = new StringBuilder(256);

        try (RecordingFile rf = new RecordingFile(jfrPath)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                switch (e.getEventType().getName()) {

                    case "jdk.JavaMonitorEnter" -> {
                        lockDur.add(e.getDuration());
                        lockClasses.merge(className(e, "monitorClass"), 1L, Long::sum);
                    }

                    case "jdk.ThreadPark" -> {
                        parkDur.add(e.getDuration());
                        RecordedStackTrace st = e.getStackTrace();
                        if (st != null && !st.getFrames().isEmpty()) {
                            String bizFrame = firstBizFrame(st.getFrames());
                            parkBizFrames.merge(bizFrame, 1L, Long::sum);
                        }
                    }

                    case "jdk.SocketRead"   -> socketReadDur.add(e.getDuration());
                    case "jdk.SocketWrite"  -> socketWriteDur.add(e.getDuration());
                    case "jdk.GCPhasePause" -> gcDur.add(e.getDuration());

                    case "jdk.ExecutionSample" -> {
                        RecordedStackTrace st = e.getStackTrace();
                        if (st == null) break;
                        List<RecordedFrame> frames = st.getFrames();
                        if (frames.isEmpty()) break;

                        String bizFrame = firstBizFrame(frames);
                        cpuByBizFrame.merge(bizFrame, 1L, Long::sum);

                        sigBuilder.setLength(0);
                        int rawLimit = Math.min(RAW_FRAMES_PER, frames.size());
                        List<String> rawList = new ArrayList<>(rawLimit);

                        for (int i = 0; i < rawLimit; i++) {
                            RecordedFrame f = frames.get(i);
                            String frameName = f.getMethod().getType().getName()
                                    + "." + f.getMethod().getName();
                            rawList.add(frameName);
                            if (i > 0) sigBuilder.append('|');
                            sigBuilder.append(frameName);
                        }

                        String sig = sigBuilder.toString();
                        long[] cnt = stackCount.get(sig);
                        if (cnt == null) {
                            cnt = new long[]{0};
                            stackCount.put(sig, cnt);
                            stackFrames.put(sig, rawList);
                        }
                        cnt[0]++;
                    }

                    case "jdk.ObjectAllocationSample" ->
                            allocClasses.merge(className(e, "objectClass"), 1L, Long::sum);

                    case "jdk.JavaErrorThrow", "jdk.ExceptionThrow" ->
                            exClasses.merge(className(e, "thrownClass"), 1L, Long::sum);
                }
            }
        }

        Map<String, SignalSummaryDto> signals = new LinkedHashMap<>();
        buildLockSignal(signals, lockDur, lockClasses);
        buildParkSignal(signals, parkDur, parkBizFrames);
        buildSocketReadSignal(signals, socketReadDur);
        buildSocketWriteSignal(signals, socketWriteDur);
        buildGcSignal(signals, gcDur);
        buildCpuSignal(signals, cpuByBizFrame, stackCount, stackFrames);
        buildAllocSignal(signals, allocClasses);
        buildExSignal(signals, exClasses);

        JfrDiffDto diff = buildDiff(previous, signals);
        return new JfrReportDto(runId, label, signals, diff);
    }

    // --- signal builders ---

    private void buildLockSignal(Map<String, SignalSummaryDto> out,
                                 List<Duration> dur, Map<String, Long> classes) {
        if (dur.isEmpty()) return;
        double[] p = percentiles(dur, 50, 95, 99);
        double maxMs = toMs(Collections.max(dur));
        out.put("JavaMonitorEnter", new SignalSummaryDto(
                dur.size(), p[0], p[1], p[2], maxMs,
                thresholds.lockSeverity(p[1]),
                topN(classes, TOP_N_CLASSES)));
    }

    private void buildParkSignal(Map<String, SignalSummaryDto> out,
                                 List<Duration> dur, Map<String, Long> bizFrames) {
        if (dur.isEmpty()) return;
        double[] p = percentiles(dur, 50, 95, 99);
        double maxMs = toMs(Collections.max(dur));
        out.put("ThreadPark", new SignalSummaryDto(
                dur.size(), p[0], p[1], p[2], maxMs,
                thresholds.parkSeverity(p[1]),
                topN(bizFrames, TOP_N_CLASSES)));
    }

    private void buildSocketReadSignal(Map<String, SignalSummaryDto> out, List<Duration> dur) {
        if (dur.isEmpty()) return;
        double[] p = percentiles(dur, 50, 95, 99);
        double maxMs = toMs(Collections.max(dur));
        out.put("SocketRead", new SignalSummaryDto(
                dur.size(), p[0], p[1], p[2], maxMs,
                thresholds.ioSeverity(p[1]),
                List.of()));
    }

    private void buildSocketWriteSignal(Map<String, SignalSummaryDto> out, List<Duration> dur) {
        if (dur.isEmpty()) return;
        double[] p = percentiles(dur, 50, 95, 99);
        double maxMs = toMs(Collections.max(dur));
        // SocketWrite is not severity-graded: write latency is buffered and not a
        // reliable bottleneck signal (matches jfr-diagnose.sh §Notes).
        out.put("SocketWrite", new SignalSummaryDto(
                dur.size(), p[0], p[1], p[2], maxMs,
                "N/A",
                List.of()));
    }

    private void buildGcSignal(Map<String, SignalSummaryDto> out, List<Duration> dur) {
        if (dur.isEmpty()) return;
        double[] p = percentiles(dur, 50, 95, 99);
        double maxMs = toMs(Collections.max(dur));
        // GC severity is keyed on p99, not p95 — matches jfr-diagnose.sh GC_SEV.
        out.put("GCPhasePause", new SignalSummaryDto(
                dur.size(), p[0], p[1], p[2], maxMs,
                thresholds.gcSeverity(p[2]),
                List.of()));
    }

    private void buildCpuSignal(Map<String, SignalSummaryDto> out,
                                Map<String, Long> cpuByBizFrame,
                                Map<String, long[]> stackCount,
                                Map<String, List<String>> stackFrames) {
        if (cpuByBizFrame.isEmpty()) return;

        List<String> topBizFrames = topN(cpuByBizFrame, TOP_N_METHODS);

        // Top-3 stacks by raw count, each expanded to their RAW_FRAMES_PER raw frames.
        // Appended after the business frames so the model sees both views in one list.
        PriorityQueue<Map.Entry<String, long[]>> heap =
                new PriorityQueue<>(3, Comparator.comparingLong(e -> e.getValue()[0]));

        for (Map.Entry<String, long[]> entry : stackCount.entrySet()) {
            if (heap.size() < 3) {
                heap.offer(entry);
            } else if (entry.getValue()[0] > heap.peek().getValue()[0]) {
                heap.poll();
                heap.offer(entry);
            }
        }

        // Drain ascending from heap — insert at 0 each time to get descending order.
        List<String> topRawFrames = new ArrayList<>();
        List<List<String>> drainedStacks = new ArrayList<>();
        while (!heap.isEmpty()) {
            drainedStacks.add(0, stackFrames.get(heap.poll().getKey()));
        }
        for (List<String> stack : drainedStacks) {
            topRawFrames.addAll(stack);
        }

        List<String> combined = new ArrayList<>(topBizFrames.size() + topRawFrames.size());
        combined.addAll(topBizFrames);
        combined.addAll(topRawFrames);

        long totalSamples = 0;
        for (long v : cpuByBizFrame.values()) totalSamples += v;

        out.put("ExecutionSample", new SignalSummaryDto(
                totalSamples, null, null, null, null, "N/A", combined));
    }

    private void buildAllocSignal(Map<String, SignalSummaryDto> out, Map<String, Long> classes) {
        if (classes.isEmpty()) return;
        long total = 0;
        for (long v : classes.values()) total += v;
        out.put("ObjectAllocationSample", new SignalSummaryDto(
                total, null, null, null, null, "N/A",
                topN(classes, TOP_N_CLASSES)));
    }

    private void buildExSignal(Map<String, SignalSummaryDto> out, Map<String, Long> classes) {
        if (classes.isEmpty()) return;
        long total = 0;
        for (long v : classes.values()) total += v;
        out.put("ExceptionThrow", new SignalSummaryDto(
                total, null, null, null, null, "N/A",
                topN(classes, TOP_N_CLASSES)));
    }

    // --- diff ---

    private static JfrDiffDto buildDiff(JfrReportDto previous,
                                        Map<String, SignalSummaryDto> current) {
        if (previous == null) return null;

        Set<String> allKeys = new LinkedHashSet<>(current.keySet());
        allKeys.addAll(previous.signals().keySet());

        Map<String, SignalDeltaDto> deltas = new LinkedHashMap<>();
        for (String key : allKeys) {
            SignalSummaryDto cur  = current.get(key);
            SignalSummaryDto prev = previous.signals().get(key);

            long curCount  = cur  != null ? cur.count()  : 0L;
            long prevCount = prev != null ? prev.count() : 0L;
            long countDelta = curCount - prevCount;

            Double p95Delta = null;
            if (cur != null && cur.p95ms() != null && prev != null && prev.p95ms() != null) {
                p95Delta = cur.p95ms() - prev.p95ms();
            }

            // p99Delta included because GC severity is keyed on p99 — a GC shift would be
            // invisible in the diff if only p95Delta were present.
            Double p99Delta = null;
            if (cur != null && cur.p99ms() != null && prev != null && prev.p99ms() != null) {
                p99Delta = cur.p99ms() - prev.p99ms();
            }

            deltas.put(key, new SignalDeltaDto(countDelta, p95Delta, p99Delta));
        }
        return new JfrDiffDto(previous.label(), deltas);
    }

    // --- helpers ---

    private static String firstBizFrame(List<RecordedFrame> frames) {
        for (RecordedFrame f : frames) {
            String name = f.getMethod().getType().getName();
            boolean isJdk = false;
            for (String prefix : JDK_PREFIXES) {
                if (name.startsWith(prefix)) {
                    isJdk = true;
                    break;
                }
            }
            if (!isJdk) return name + "." + f.getMethod().getName();
        }
        // All frames are JDK — fall back to the top frame so we never lose the stack.
        RecordedFrame top = frames.get(0);
        return top.getMethod().getType().getName() + "." + top.getMethod().getName();
    }

    private static String className(RecordedEvent e, String field) {
        // Using the specific checked exception rather than a broad catch — a wrong field
        // name (e.g. typo in "monitorClass") will surface as an IllegalArgumentException
        // at runtime and fail the REF validation run visibly rather than silently
        // producing "(unknown): 71738" instead of "UrlJarFiles$Cache: 71193".
        RecordedClass rc = e.getClass(field);
        return rc != null ? rc.getName() : "(unknown)";
    }

    /**
     * Returns percentiles in milliseconds for the requested ranks (0–100).
     * Uses ceil(n * rank/100) - 1 indexing, matching jfr-diagnose.sh:
     *   awk -v n="$COUNT" 'NR==int(n*0.95)+1{print; exit}'
     */
    private static double[] percentiles(List<Duration> durations, int... ranks) {
        long[] sorted = new long[durations.size()];
        for (int i = 0; i < durations.size(); i++) {
            sorted[i] = durations.get(i).toNanos();
        }
        Arrays.sort(sorted);

        double[] result = new double[ranks.length];
        for (int i = 0; i < ranks.length; i++) {
            int idx = (int) Math.ceil(sorted.length * ranks[i] / 100.0) - 1;
            if (idx < 0) idx = 0;
            result[i] = sorted[idx] / 1_000_000.0;
        }
        return result;
    }

    private static double toMs(Duration d) {
        return d.toNanos() / 1_000_000.0;
    }

    private static List<String> topN(Map<String, Long> counts, int n) {
        // Min-heap of size N: smallest count at the top.
        // When a new entry beats the current minimum, evict and insert.
        // O(n log N) vs O(n log n) for full sort — matters at 70k+ lock events.
        PriorityQueue<Map.Entry<String, Long>> heap =
                new PriorityQueue<>(n, Map.Entry.comparingByValue());

        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (heap.size() < n) {
                heap.offer(entry);
            } else if (entry.getValue() > heap.peek().getValue()) {
                heap.poll();
                heap.offer(entry);
            }
        }

        // Heap drains ascending — insert at 0 each time to get descending without a reverse pass.
        List<String> result = new ArrayList<>(heap.size());
        while (!heap.isEmpty()) {
            Map.Entry<String, Long> entry = heap.poll();
            result.add(0, entry.getKey() + ": " + entry.getValue());
        }
        return result;
    }
}
