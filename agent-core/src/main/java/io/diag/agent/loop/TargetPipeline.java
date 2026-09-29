package io.diag.agent.loop;

import io.diag.evidence.dto.JfrReportDto;
import io.diag.runner.service.BuildFailedException;
import java.nio.file.Path;

public interface TargetPipeline {
    Path rebuild() throws BuildFailedException, Exception;
    boolean runTests() throws Exception;
    SmokeResult smoke(int n) throws Exception;
    BenchmarkCycle benchmark(String label, JfrReportDto prevJfr) throws Exception;
}
