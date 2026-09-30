package io.diag.agent.loop;

import java.util.Optional;

/**
 * Contract for caching baseline runs by profile_hash (Step 10 D2, scope §10.24, §10.30).
 * Prevents redundant 3-cycle baseline execution (~6 min saved per repeat run).
 */
public interface BaselineCache {

    Optional<BaselineCacheEntry> lookup(String profileHash);

    void put(BaselineCacheEntry entry);
}
