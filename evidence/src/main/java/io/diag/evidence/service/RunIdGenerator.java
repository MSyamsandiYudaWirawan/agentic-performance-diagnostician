package io.diag.evidence.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * Run ids follow scope §10.9: yyyyMMdd-HHmmss-&lt;8 hex&gt;. The id doubles as
 * the per-run compose project name (-p &lt;run-id&gt;), so it must be safe as a
 * docker/filesystem name. Minted once per run and passed around — never
 * regenerated.
 */
@Component
public class RunIdGenerator {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    // SecureRandom (OS entropy, no explicit seed): uniqueness is the whole job of
    // the suffix; TIGERSTYLE #6 requires documenting the randomness — this is it.
    private final SecureRandom random = new SecureRandom();

    public String next() {
        byte[] suffix = new byte[4];
        random.nextBytes(suffix);
        return LocalDateTime.now().format(TIMESTAMP) + "-" + HexFormat.of().formatHex(suffix);
    }
}
