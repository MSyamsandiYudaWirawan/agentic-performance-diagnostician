package io.diag.agent.loop;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory implementation of BaselineCache (Step 10 M5).
 */
@Component
public class InMemoryBaselineCache implements BaselineCache {

    private final Map<String, BaselineCacheEntry> cache = new ConcurrentHashMap<>();

    @Override
    public Optional<BaselineCacheEntry> lookup(String profileHash) {
        Objects.requireNonNull(profileHash, "profileHash must not be null");
        return Optional.ofNullable(cache.get(profileHash));
    }

    @Override
    public void put(BaselineCacheEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        cache.put(entry.profileHash(), entry);
    }
}
