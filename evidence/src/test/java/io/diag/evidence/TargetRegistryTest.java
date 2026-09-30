package io.diag.evidence;

import io.diag.evidence.entity.Target;
import io.diag.evidence.repository.TargetRepository;
import io.diag.evidence.service.TargetRegistry;
import io.diag.evidence.service.impl.TargetRegistryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 10 Milestone 0 Verify Gate (TargetRegistryTest, no Docker, no LLM).
 */
public class TargetRegistryTest {

    private TargetRegistry registry;
    private InMemoryTargetRepository inMemoryRepo;

    @BeforeEach
    void setUp() {
        inMemoryRepo = new InMemoryTargetRepository();
        registry = new TargetRegistryImpl(inMemoryRepo);
    }

    @Test
    void canonicalTargets_containAllFourTargetsWithExpectedMetadata() {
        List<Target> canonical = registry.getCanonicalTargets();
        assertThat(canonical).hasSize(4);

        Map<String, Target> byId = new HashMap<>();
        for (Target t : canonical) {
            byId.put(t.getId(), t);
        }

        // S1: Stock PetClinic
        Target s1 = byId.get("S1");
        assertThat(s1).isNotNull();
        assertThat(s1.getGroundTruthCategory()).isEqualTo("H5");
        assertThat(s1.getGroundTruthFix()).isEqualTo("jar-unpack");
        assertThat(s1.getSeedPatch()).isNull();

        // S2: Hikari pool starvation
        Target s2 = byId.get("S2");
        assertThat(s2).isNotNull();
        assertThat(s2.getGroundTruthCategory()).isEqualTo("H2");
        assertThat(s2.getGroundTruthFix()).isEqualTo("hikari-pool-size");
        assertThat(s2.getSeedPatch()).isEqualTo("benchmarks/seeds/S2-hikari.patch");

        // S3: JVM memory opts
        Target s3 = byId.get("S3");
        assertThat(s3).isNotNull();
        assertThat(s3.getGroundTruthCategory()).isEqualTo("H3");
        assertThat(s3.getGroundTruthFix()).isEqualTo("jvm-opts");
        assertThat(s3.getSeedPatch()).isEqualTo("benchmarks/seeds/S3-jvm.patch");

        // S4: Practice-MVC app monitor lock
        Target s4 = byId.get("S4");
        assertThat(s4).isNotNull();
        assertThat(s4.getGroundTruthCategory()).isEqualTo("H5");
        assertThat(s4.getGroundTruthFix()).isEqualTo("lock-narrowing");
    }

    @Test
    void computeProfileHash_isDeterministicAndUnique() {
        String treeSha = "e7af3361f22a382cfed28f755154219f1718ea15";
        byte[] k6Script = "console.log('k6');".getBytes(StandardCharsets.UTF_8);
        byte[] envelope = "services: ...".getBytes(StandardCharsets.UTF_8);

        String hash1 = registry.computeProfileHash(treeSha, k6Script, envelope);
        String hash2 = registry.computeProfileHash(treeSha, k6Script, envelope);

        assertThat(hash1).isNotNull().hasSize(64);
        assertThat(hash1).isEqualTo(hash2);

        // Perturbing any component changes hash
        String hashDifferentSha = registry.computeProfileHash(
                "0000000000000000000000000000000000000000", k6Script, envelope);
        assertThat(hashDifferentSha).isNotEqualTo(hash1);

        String hashDifferentK6 = registry.computeProfileHash(
                treeSha, "different k6".getBytes(StandardCharsets.UTF_8), envelope);
        assertThat(hashDifferentK6).isNotEqualTo(hash1);
    }

    @Test
    void registerAndFind_roundTripsCleanly() {
        Target target = Target.builder()
                .id("T1")
                .name("Test Target")
                .baseRepo("targets/test")
                .seedPatch(null)
                .baselineSha("abcdef1234567890abcdef1234567890abcdef12")
                .groundTruthCategory("H1")
                .groundTruthFix("none")
                .profileHash("hash123")
                .build();

        registry.register(target);

        Optional<Target> found = registry.findById("T1");
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Test Target");
        assertThat(found.get().getGroundTruthCategory()).isEqualTo("H1");

        List<Target> all = registry.findAll();
        assertThat(all).hasSize(1);
    }

    @Test
    void tigerStyle_failFastOnNulls() {
        assertThatThrownBy(() -> registry.register(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.findById(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.computeProfileHash(null, new byte[0], new byte[0]))
                .isInstanceOf(NullPointerException.class);
    }

    // In-memory fake repository for fast, Docker-free unit testing
    private static class InMemoryTargetRepository implements TargetRepository {
        private final Map<String, Target> store = new HashMap<>();

        @Override
        public <S extends Target> S save(S entity) {
            store.put(entity.getId(), entity);
            return entity;
        }

        @Override
        public <S extends Target> Iterable<S> saveAll(Iterable<S> entities) {
            for (S entity : entities) {
                save(entity);
            }
            return entities;
        }

        @Override
        public Optional<Target> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public boolean existsById(String id) {
            return store.containsKey(id);
        }

        @Override
        public Iterable<Target> findAll() {
            return new ArrayList<>(store.values());
        }

        @Override
        public Iterable<Target> findAllById(Iterable<String> strings) {
            List<Target> result = new ArrayList<>();
            for (String s : strings) {
                if (store.containsKey(s)) {
                    result.add(store.get(s));
                }
            }
            return result;
        }

        @Override
        public long count() {
            return store.size();
        }

        @Override
        public void deleteById(String id) {
            store.remove(id);
        }

        @Override
        public void delete(Target entity) {
            store.remove(entity.getId());
        }

        @Override
        public void deleteAllById(Iterable<? extends String> strings) {
            for (String s : strings) {
                store.remove(s);
            }
        }

        @Override
        public void deleteAll(Iterable<? extends Target> entities) {
            for (Target t : entities) {
                store.remove(t.getId());
            }
        }

        @Override
        public void deleteAll() {
            store.clear();
        }
    }
}
