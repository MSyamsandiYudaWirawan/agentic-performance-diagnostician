package io.diag.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.SignalSummaryDto;
import io.diag.runner.config.Thresholds;
import io.diag.runner.service.JfrAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 6 verify gate (build-steps step 6): the aggregator must independently
 * reproduce the known signatures on REF recordings — no LLM, no Docker, no DB.
 *
 * Validation set (three recordings, two opposite known signatures):
 *   h3              fat-jar lock dominant (~72k JavaMonitorEnter, CRITICAL)
 *   jar-unpack-exp  shifted (UrlJarFiles$Cache absent; JarFile/URLClassPath dominant)
 *   resource-cache  same shifted locks within noise — must read "no change"
 * plus the h3→jar-unpack diff (monitor-class comparison shape).
 *
 * Opt-in (reads the read-only REF tree; ~30s of JFR parsing):
 *   mvn -pl target-runner -am test "-Dstep6.gate=true" "-Dtest=Step6VerifyGate" "-Dsurefire.failIfNoSpecifiedTests=false"
 * PowerShell: quote every -D (the dot in step6.gate splits unquoted).
 */
class Step6VerifyGate {

    static final Path REF = Path.of("C:/study/java-backend-quality-analyzer");
    static final Path H3 = REF.resolve("evidence/advanced/h3/spring-petclinic/profile.jfr");
    static final Path UNPACK = REF.resolve("evidence/advanced/jar-unpack-exp/spring-petclinic/profile.jfr");
    static final Path RESCACHE = REF.resolve("evidence/advanced/resource-cache-exp/spring-petclinic/profile.jfr");
    static final Path OWN_ROOT = Path.of("C:/study/agentic-performance-diagnostician/evidence/artifacts");

    private final JfrAnalyzer analyzer = new JfrAnalyzer(Thresholds.fromEnv());
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aggregatorReproducesKnownSignatures() throws Exception {
        if (!Boolean.getBoolean("step6.gate")) return;

        JfrReportDto h3 = analyzer.analyze(H3, null, "step6-gate", "h3");
        SignalSummaryDto h3Lock = h3.signals().get("JavaMonitorEnter");
        assertNotNull(h3Lock, "h3 must have a JavaMonitorEnter signal");
        assertTrue(h3Lock.count() > 60_000 && h3Lock.count() < 85_000,
                "h3 lock count ~72k, got " + h3Lock.count());
        assertEquals("CRITICAL", h3Lock.severity(), "h3 lock severity");
        assertTrue(containsClass(h3Lock, "UrlJarFiles$Cache"),
                "h3 top monitorClass must contain UrlJarFiles$Cache, got " + h3Lock.topFrames());

        JfrReportDto unpack = analyzer.analyze(UNPACK, null, "step6-gate", "jar-unpack");
        SignalSummaryDto unpackLock = unpack.signals().get("JavaMonitorEnter");
        assertNotNull(unpackLock, "jar-unpack must have a JavaMonitorEnter signal");
        assertTrue(unpackLock.count() > 8_000 && unpackLock.count() < 25_000,
                "jar-unpack lock count ~14k, got " + unpackLock.count());
        assertFalse(containsClass(unpackLock, "UrlJarFiles$Cache"),
                "jar-unpack must eliminate UrlJarFiles$Cache, got " + unpackLock.topFrames());
        assertTrue(containsClass(unpackLock, "JarFile") || containsClass(unpackLock, "URLClassPath"),
                "jar-unpack shifted locks must be JarFile/URLClassPath, got " + unpackLock.topFrames());

        // falsification reference: same shifted signature within noise, not an improvement
        JfrReportDto rescache = analyzer.analyze(RESCACHE, null, "step6-gate", "resource-cache");
        SignalSummaryDto resLock = rescache.signals().get("JavaMonitorEnter");
        assertNotNull(resLock, "resource-cache must have a JavaMonitorEnter signal");
        assertFalse(containsClass(resLock, "UrlJarFiles$Cache"),
                "resource-cache must also lack UrlJarFiles$Cache, got " + resLock.topFrames());
        assertWithinFactor(classCounts(unpackLock), classCounts(resLock), "JarFile", 0.4, 2.5);
        assertWithinFactor(classCounts(unpackLock), classCounts(resLock), "URLClassPath", 0.4, 2.5);

        // diff h3 -> jar-unpack: the verified-mechanism signal keep-rule v2 verifies
        JfrReportDto unpackDiffed = analyzer.analyze(UNPACK, h3, "step6-gate", "jar-unpack");
        assertNotNull(unpackDiffed.diff(), "diff must be present when previous is given");
        assertEquals("h3", unpackDiffed.diff().baseLabel(), "diff base label");
        long lockDelta = unpackDiffed.diff().deltas().get("JavaMonitorEnter").countDelta();
        assertTrue(lockDelta < -40_000, "lock count must collapse h3->unpack, delta=" + lockDelta);
        assertNotNull(unpackDiffed.diff().deltas().get("JavaMonitorEnter").p95Delta(),
                "p95Delta must be present for graded signals");
        // GCPhasePause diff must carry p99Delta — GC severity is keyed on p99, not p95,
        // so a GC shift would be invisible in the diff without it.
        assertNotNull(unpackDiffed.diff().deltas().get("GCPhasePause"),
                "GCPhasePause must appear in diff");
        assertNotNull(unpackDiffed.diff().deltas().get("GCPhasePause").p99Delta(),
                "p99Delta must be present for GCPhasePause");
        assertReportIsCompact(unpackDiffed);

        // own step-5 recording, when one exists (evidence/artifacts is gitignored,
        // so this leg only runs after a real gate produced a recording)
        Path own = latestOwnRecording();
        if (own != null) {
            JfrReportDto ownReport = analyzer.analyze(own, null, "step6-gate", "own-smoke");
            assertTrue(ownReport.signals().containsKey("ExecutionSample"), "own recording must have ExecutionSample");
            assertTrue(ownReport.signals().containsKey("JavaMonitorEnter"), "own recording must have JavaMonitorEnter");
            System.out.println("[step6-gate] own recording OK: " + own);
        } else {
            System.out.println("[step6-gate] no own recording under " + OWN_ROOT + " — skipping that leg");
        }
    }

    private void assertReportIsCompact(JfrReportDto report) throws Exception {
        int bytes = mapper.writeValueAsBytes(report.signals()).length;
        // Upper bound 6_000 gives a small margin over the 5KB spec ceiling (v1.0-scope §4.3).
        // 10_000 was too loose — an 8KB report would pass but exceed LLM context budget.
        assertTrue(bytes > 500 && bytes < 6_000, "signals JSON should be ~2-5KB, got " + bytes + " bytes");
        int diffBytes = mapper.writeValueAsBytes(report.diff()).length;
        assertTrue(diffBytes < 2_000, "diff JSON should be ~0.5KB, got " + diffBytes + " bytes");
    }

    private static boolean containsClass(SignalSummaryDto signal, String fragment) {
        return signal.topFrames().stream().anyMatch(s -> s.contains(fragment));
    }

    /** Parses "com.Foo: 1234" topFrames entries into class -> count. */
    private static Map<String, Long> classCounts(SignalSummaryDto signal) {
        Map<String, Long> out = new HashMap<>();
        for (String entry : signal.topFrames()) {
            int sep = entry.lastIndexOf(':');
            if (sep < 0) continue;
            // Let NumberFormatException propagate — a malformed entry means JfrAnalyzer
            // emitted a bad format, which is a bug that should fail the gate loudly.
            out.put(entry.substring(0, sep).trim(),
                    Long.parseLong(entry.substring(sep + 1).trim()));
        }
        return out;
    }

    private static void assertWithinFactor(Map<String, Long> a, Map<String, Long> b,
                                           String fragment, double lo, double hi) {
        long av = findFragment(a, fragment);
        long bv = findFragment(b, fragment);
        assertTrue(av > 0 && bv > 0, fragment + " must appear in both reports");
        double ratio = (double) bv / av;
        assertTrue(ratio >= lo && ratio <= hi,
                fragment + " counts " + av + " vs " + bv + " outside factor [" + lo + "," + hi + "]");
    }

    private static long findFragment(Map<String, Long> counts, String fragment) {
        return counts.entrySet().stream()
                .filter(e -> e.getKey().contains(fragment))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    private static Path latestOwnRecording() throws IOException {
        if (!Files.isDirectory(OWN_ROOT)) return null;
        Path latest = null;
        long latestMtime = 0;
        try (Stream<Path> runDirs = Files.list(OWN_ROOT)) {
            List<Path> dirs = runDirs.filter(Files::isDirectory).toList();
            for (Path dir : dirs) {
                try (Stream<Path> walked = Files.walk(dir, 2)) {
                    List<Path> jfrs = walked
                            .filter(p -> p.getFileName().toString().equals("profile.jfr"))
                            .toList();
                    for (Path jfr : jfrs) {
                        long mtime = jfr.toFile().lastModified();
                        if (mtime > latestMtime) {
                            latestMtime = mtime;
                            latest = jfr;
                        }
                    }
                } catch (IOException e) {
                    // Log and continue — one unreadable run dir should not abort the search.
                    System.out.println("[step6-gate] could not walk " + dir + ": " + e.getMessage());
                }
            }
        }
        return latest;
    }
}
