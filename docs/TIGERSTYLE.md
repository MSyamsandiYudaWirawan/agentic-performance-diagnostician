# TigerStyle / TigerBeetle Coding Principles
# Apply to ALL code generated during the hackathon

> "Boring code is good code. Clever code is suspect."
> — TigerBeetle engineering philosophy

---

## 1. Simplicity Over Cleverness

- **No one-liner trick shots.** If it requires a comment to explain WHAT it does, rewrite it.
- **No streams/pipelines that do 5 things in one chain.** Split into named intermediate steps.
- **No AOP / reflection magic.** Every code path must be visible in the file you're reading.

**Bad:**
```java
return bids.stream().filter(b -> b.getAmount().compareTo(current) > 0)
    .max(Comparator.comparing(Bid::getAmount)
        .thenComparing(Bid::getPlacedAt)).orElseThrow();
```

**Good:**
```java
Bid highest = null;
for (Bid bid : bids) {
    if (bid.getAmount().compareTo(current) <= 0) continue;
    if (highest == null || isHigher(bid, highest)) {
        highest = bid;
    }
}
if (highest == null) throw new NoBidsException();
return highest;
```

---

## 2. Explicit Error Handling — No Silent Swallows

- **Every `catch` must either handle, compensate, or rethrow.**
- **No `catch (Exception e) { log.error(e); }` that hides failures.**
- **Checked exceptions are fine.** They force callers to decide.

**Bad:**
```java
try {
    kafkaTemplate.send(topic, event);
} catch (Exception e) {
    log.error("Kafka error", e);
}
```

**Good:**
```java
try {
    kafkaTemplate.send(topic, event).get();
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new PublishException("Interrupted while publishing event", e);
} catch (ExecutionException e) {
    throw new PublishException("Kafka publish failed", e.getCause());
}
```

---

## 3. No Hidden Allocations / No GC Pressure Hot Paths

- **In tight loops, avoid creating objects.** Reuse where possible.
- **No `String.format()` or `+` concatenation in hot paths.** Use `StringBuilder` or pre-formatted templates.
- **No boxing/unboxing in loops.** Use primitives.
- **No autoboxing `BigDecimal` operations in tight loops** if you can use `long` cents instead.

**Bad:**
```java
for (int i = 0; i < 10000; i++) {
    String key = "auction:" + auctionId + ":bid:" + i;  // allocates N strings
    redis.opsForValue().set(key, value);
}
```

**Good:**
```java
StringBuilder keyBuilder = new StringBuilder(48);
for (int i = 0; i < 10000; i++) {
    keyBuilder.setLength(0);
    keyBuilder.append("auction:").append(auctionId).append(":bid:").append(i);
    redis.opsForValue().set(keyBuilder.toString(), value);
}
```

---

## 4. Fail Fast — Validate at the Boundary

- **Null checks at entry points.** Never assume a parameter is non-null.
- **Range checks immediately.** Don't carry invalid data deeper.
- **Illegal states should be unrepresentable.** Use enums, sealed classes, records with validation.

**Bad:**
```java
public void placeBid(UUID auctionId, BigDecimal amount) {
    // amount might be null or negative... handled 3 layers deep
    repository.save(new Bid(auctionId, amount));
}
```

**Good:**
```java
public void placeBid(UUID auctionId, BigDecimal amount) {
    Objects.requireNonNull(auctionId, "auctionId must not be null");
    Objects.requireNonNull(amount, "amount must not be null");
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalArgumentException("amount must be positive");
    }
    repository.save(new Bid(auctionId, amount));
}
```

---

## 5. No Global State / No Static Mutable State

- **No `static` mutable fields.** Every piece of state lives in an instance.
- **No Spring `@Component` with mutable fields accessed by multiple threads without synchronization.**
- **Configuration is injected, not read from static config.**

**Bad:**
```java
@Component
public class BidService {
    private static int counter = 0;  // shared mutable static state
    public void placeBid() { counter++; }
}
```

**Good:**
```java
@Component
public class BidService {
    private final AtomicInteger counter = new AtomicInteger(0);
    public void placeBid() { counter.incrementAndGet(); }
}
```

---

## 6. Deterministic Behavior — No Randomness Without Seeding

- **No `Math.random()` or `new Random()` in business logic.**
- **If you need randomness, seed it explicitly and document why.**
- **Tie-breaking must be deterministic.** `(amount, timestamp, bidderId)` not `(amount, timestamp, random)`.

**Bad:**
```java
if (bid1.getAmount().equals(bid2.getAmount())) {
    return Math.random() > 0.5 ? bid1 : bid2;  // non-deterministic!
}
```

**Good:**
```java
if (bid1.getAmount().equals(bid2.getAmount())) {
    int cmp = bid1.getPlacedAt().compareTo(bid2.getPlacedAt());
    if (cmp == 0) cmp = bid1.getBidderId().compareTo(bid2.getBidderId());
    return cmp < 0 ? bid1 : bid2;
}
```

---

## 7. Minimal Dependencies — Prefer Boring Libraries

- **No dependency that solves a problem you can solve in 20 lines.**
- **No experimental libraries.** Only well-tested, widely-used deps.
- **If you add a dependency, write why in a comment next to it in pom.xml.**

**Bad:**
```xml
<!-- pulled in for fancy functional utilities -->
<dependency>
    <groupId>io.vavr</groupId>
    <artifactId>vavr</artifactId>
</dependency>
```

**Good:**
```xml
<!-- resilience4j: circuit breaker + retry. Used in AccountServiceClient.
     Replacing with custom impl would be ~200 lines and less tested. -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-reactor</artifactId>
</dependency>
```

---

## 8. Comments Explain WHY, Code Explains WHAT

- **No comments that restate the obvious.**
- **Every non-obvious decision gets a comment.**
- **Every workaround gets a comment with a TODO to fix it properly.**

**Bad:**
```java
// increment counter
counter++;
```

**Good:**
```java
// We intentionally do NOT release the hold here.
// Per the two-phase model, holds are batch-released at auction close
// to avoid N compensation sagas during the hot bidding window.
// See TIGERSTYLE.md section 2 and spec ADR-04.
```

---

## 9. No Unnecessary Generics / Metaprogramming

- **No `AbstractGenericBaseService<T extends Something & Else>`** unless truly necessary.
- **Concrete types are easier to read, debug, and optimize.**
- **Copy-paste 20 lines of similar code before introducing an abstraction.**

**Bad:**
```java
public abstract class AbstractSagaOrchestrator<S extends SagaState, C extends SagaCommand> {
    protected abstract Mono<S> executeStep(C command);
}
```

**Good:**
```java
public class PlaceBidSagaOrchestrator {
    // Concrete. One job. Easy to trace.
    public Mono<SagaInstance> holdFunds(PlaceBidCommand cmd) { ... }
    public Mono<SagaInstance> reserveBid(SagaInstance saga) { ... }
}
```

---

## 10. Test the Failure Modes, Not Just the Happy Path

- **Every `if` branch must have a test.**
- **Every `catch` must have a test.**
- **Chaos tests are not optional.** They are the proof your code works.

**Minimum test matrix per method:**
1. Normal input → expected output
2. Null input → throws / rejects
3. Invalid input → throws / rejects
4. Concurrent access → deterministic result
5. Dependency failure → graceful degrade or compensate

---

## 11. No Resource Leaks — Explicit Lifecycle

- **Every resource opened must be closed.** Use try-with-resources.
- **Every subscription must be disposed.** In reactive code, chain `.doFinally()` or use `Disposable`.
- **Every thread pool must be shut down.**

**Bad:**
```java
ExecutorService executor = Executors.newFixedThreadPool(10);
executor.submit(() -> doWork());  // never shut down
```

**Good:**
```java
ExecutorService executor = Executors.newFixedThreadPool(10);
try {
    executor.submit(() -> doWork());
} finally {
    executor.shutdown();
    if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
        executor.shutdownNow();
    }
}
```

---

## 12. Metrics Are Code, Not an Afterthought

- **Every significant operation must be measurable.**
- **Latency histograms, not just counters.**
- **Label metrics with the outcome (success/failure), not just the operation.**

**Bad:**
```java
public void placeBid(...) {
    // ... logic ...
    metrics.increment("bids.placed");  // no latency, no failure signal
}
```

**Good:**
```java
public void placeBid(...) {
    Timer.Sample sample = Timer.start(registry);
    try {
        // ... logic ...
        sample.stop(registry.timer("bid.latency", "outcome", "success"));
    } catch (Exception e) {
        sample.stop(registry.timer("bid.latency", "outcome", "failure", "type", e.getClass().getSimpleName()));
        throw e;
    }
}
```

---

## Enforcement Checklist (Before Submitting Code)

- [ ] No stream chains longer than 3 operations without intermediate variables
- [ ] No catch blocks that only log
- [ ] No static mutable state
- [ ] No `Math.random()` in business logic
- [ ] Every public method validates inputs with `Objects.requireNonNull` or equivalent
- [ ] Every comment explains WHY, not WHAT
- [ ] Every dependency in pom.xml has a comment explaining why it exists
- [ ] Every significant method has a failure-mode test
- [ ] No resource leaks (connections, threads, subscriptions)

---

## When I (Kimi) Generate Code That Violates These Principles

**Notify me immediately.** Use this exact format:

```
TIGERSTYLE VIOLATION:
- File: [file]
- Line: [line]
- Rule broken: [which rule above]
- Issue: [what's wrong]
- Fix: [what it should look like]
```

I will rewrite the code to comply.
