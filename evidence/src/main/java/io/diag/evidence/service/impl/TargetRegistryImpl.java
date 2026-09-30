package io.diag.evidence.service.impl;

import io.diag.evidence.entity.Target;
import io.diag.evidence.repository.TargetRepository;
import io.diag.evidence.service.TargetRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Spring Data JDBC backed TargetRegistry implementation (Step 10 M0, TigerStyle compliant).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TargetRegistryImpl implements TargetRegistry {

    private final TargetRepository targetRepository;

    @Transactional
    @Override
    public Target register(Target target) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(target.getId(), "target.id must not be null");
        Objects.requireNonNull(target.getName(), "target.name must not be null");
        Objects.requireNonNull(target.getBaseRepo(), "target.baseRepo must not be null");
        Objects.requireNonNull(target.getGroundTruthCategory(), "target.groundTruthCategory must not be null");
        Objects.requireNonNull(target.getGroundTruthFix(), "target.groundTruthFix must not be null");

        // Respect isNew contract for assigned IDs: if entity exists, mark isNew=false so JDBC issues UPDATE
        boolean exists = targetRepository.existsById(target.getId());
        target.setNew(!exists);
        return targetRepository.save(target);
    }

    @Override
    public Optional<Target> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        return targetRepository.findById(id);
    }

    @Override
    public List<Target> findAll() {
        List<Target> list = new ArrayList<>();
        targetRepository.findAll().forEach(list::add);
        return list;
    }

    @Override
    public String computeProfileHash(String targetTreeSha, Path k6ScriptPath, Path envelopePath) {
        Objects.requireNonNull(targetTreeSha, "targetTreeSha must not be null");
        Objects.requireNonNull(k6ScriptPath, "k6ScriptPath must not be null");
        Objects.requireNonNull(envelopePath, "envelopePath must not be null");

        try {
            byte[] k6Bytes = Files.readAllBytes(k6ScriptPath);
            byte[] envBytes = Files.readAllBytes(envelopePath);
            return computeProfileHash(targetTreeSha, k6Bytes, envBytes);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read hash inputs from disk: " + e.getMessage(), e);
        }
    }

    @Override
    public String computeProfileHash(String targetTreeSha, byte[] k6ScriptBytes, byte[] envelopeBytes) {
        Objects.requireNonNull(targetTreeSha, "targetTreeSha must not be null");
        Objects.requireNonNull(k6ScriptBytes, "k6ScriptBytes must not be null");
        Objects.requireNonNull(envelopeBytes, "envelopeBytes must not be null");

        String k6Sha = sha256Hex(k6ScriptBytes);
        String envSha = sha256Hex(envelopeBytes);
        String composite = targetTreeSha + ":" + k6Sha + ":" + envSha;
        return sha256Hex(composite.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public List<Target> getCanonicalTargets() {
        // Step 10 target matrix canonical definitions (§3)
        List<Target> list = new ArrayList<>(4);

        list.add(Target.builder()
                .id("S1")
                .name("Stock PetClinic")
                .baseRepo("targets/spring-petclinic")
                .seedPatch(null)
                .baselineSha("e7af3361f22a382cfed28f755154219f1718ea15")
                .groundTruthCategory("H5")
                .groundTruthFix("jar-unpack")
                .profileHash("")
                .isNew(true)
                .build());

        list.add(Target.builder()
                .id("S2")
                .name("PetClinic Hikari Pool Starvation")
                .baseRepo("targets/spring-petclinic")
                .seedPatch("benchmarks/seeds/S2-hikari.patch")
                .baselineSha("e7af3361f22a382cfed28f755154219f1718ea15")
                .groundTruthCategory("H2")
                .groundTruthFix("hikari-pool-size")
                .profileHash("")
                .isNew(true)
                .build());

        list.add(Target.builder()
                .id("S3")
                .name("PetClinic JVM Memory Exhaustion")
                .baseRepo("targets/spring-petclinic")
                .seedPatch("benchmarks/seeds/S3-jvm.patch")
                .baselineSha("e7af3361f22a382cfed28f755154219f1718ea15")
                .groundTruthCategory("H3")
                .groundTruthFix("jvm-opts")
                .profileHash("")
                .isNew(true)
                .build());

        list.add(Target.builder()
                .id("S4")
                .name("Practice-MVC App Monitor Contention")
                .baseRepo("targets/practice-mvc")
                .seedPatch("benchmarks/seeds/S4-monitor.patch")
                .baselineSha("16eb8e29a562c90f4eddd3bfd6eae325065faf69")
                .groundTruthCategory("H5")
                .groundTruthFix("lock-narrowing")
                .profileHash("")
                .isNew(true)
                .build());

        return list;
    }

    @Transactional
    @Override
    public void initCanonicalTargets() {
        List<Target> canonical = getCanonicalTargets();
        for (Target t : canonical) {
            register(t);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm missing in JVM", e);
        }
    }
}
