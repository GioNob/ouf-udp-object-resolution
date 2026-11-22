package it.comune.trieste.ouf.udp;

import java.net.http.HttpHeaders;
import java.time.*;
import java.util.EnumMap;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local lower bounds on the next read of a route. A quota observation grants no permits. */
final class GatewayReadPacing {
  enum Route { PUBLICATION, BATCH, LEGACY }
  private final EnumMap<Route,Instant> next = new EnumMap<>(Route.class);
  private volatile long maxDelayMs = 60_000;
  private volatile long jitterMs = 1_000;

  /** Installation parameters: upper bound of a server-requested deferral and its jitter. */
  void configure(long maxDelayMs, long jitterMs) {
    if (maxDelayMs < 1 || jitterMs < 0) throw new IllegalArgumentException("UDP_GATEWAY_RETRY_CONFIG_INVALID");
    this.maxDelayMs = maxDelayMs;
    this.jitterMs = jitterMs;
  }

  private static final ThreadLocal<Instant> leaseUntil = new ThreadLocal<>();
  private static final Duration EXCHANGE_TIMEOUT = Duration.ofSeconds(5);

  interface Window extends AutoCloseable { @Override void close(); }

  static Window forClaim(ResolutionRepository.Claim claim) {
    Instant previous=leaseUntil.get();
    leaseUntil.set(claim.leaseUntil());
    return () -> { if(previous==null)leaseUntil.remove(); else leaseUntil.set(previous); };
  }

  void before(Route route, int intervalMs) {
    while (true) {
      Instant due;
      Instant now=Instant.now();
      synchronized (this) {
        due=next.get(route);
        Instant end=leaseUntil.get();
        // Leave time for an entire exchange and the fenced transition to durable retry.
        if(end!=null && !later(now,due).plus(EXCHANGE_TIMEOUT.multipliedBy(2)).isBefore(end))
          throw PublicationAccessFailure.deferred(due);
        if (due==null || !now.isBefore(due)) {
          next.put(route,now.plusMillis(intervalMs));
          return;
        }
      }
      Duration delay=Duration.between(now,due);
      // Interactive reads have no job lease. Return long admission delays to the caller.
      if ((leaseUntil.get()==null && delay.compareTo(EXCHANGE_TIMEOUT)>0) || TransactionSynchronizationManager.isActualTransactionActive())
        throw PublicationAccessFailure.deferred(due);
      try { Thread.sleep(delay); }
      catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw PublicationAccessFailure.network();
      }
    }
  }

  /**
   * Admission after a 429 or 503: Retry-After first, then X-RateLimit-Reset (APISIX limit-count sends
   * no Retry-After), otherwise null so the configured durable pacing applies. Capped, with jitter.
   */
  synchronized Instant deferral(Route route, HttpHeaders headers, String retryAfter, Instant now) {
    Instant base = PublicationAccessFailure.retryAfter(retryAfter, now);
    if (base == null) base = quota(headers, now);
    if (base == null) return null;
    Instant cap = now.plusMillis(maxDelayMs);
    if (base.isAfter(cap)) base = cap;
    long window = Math.max(0, Duration.between(now, base).toMillis());
    long spread = Math.min(jitterMs, Math.max(1, window / 10));
    base = base.plusMillis(java.util.concurrent.ThreadLocalRandom.current().nextLong(spread + 1));
    return next.merge(route, base, GatewayReadPacing::later);
  }

  synchronized Instant observe(Route route, HttpHeaders headers, Instant now, Instant retryFloor) {
    Instant due = later(quota(headers,now),retryFloor);
    if (due != null) return next.merge(route,due,GatewayReadPacing::later);
    return null;
  }

  // APISIX limit-count sends seconds remaining in its window, including on 200s.
  static Instant quota(HttpHeaders headers, Instant now) {
    var reset = headers.firstValue("X-RateLimit-Reset");
    if (reset.isEmpty()) return null;
    try {
      // APISIX 3.18 reports fractional seconds (for example "2.5").
      var seconds = new java.math.BigDecimal(reset.get().strip());
      if (seconds.signum() < 0) throw new IllegalArgumentException();
      // Also accept gateways using Unix epoch seconds for this legacy X- header.
      long millis = seconds.compareTo(java.math.BigDecimal.valueOf(1_000_000_000L)) >= 0
          ? Math.max(0, Duration.between(now,Instant.ofEpochMilli(seconds.movePointRight(3).setScale(0, java.math.RoundingMode.CEILING).longValueExact())).toMillis())
          : seconds.movePointRight(3).setScale(0, java.math.RoundingMode.CEILING).longValueExact();
      long remaining = nonnegative(headers.firstValue("X-RateLimit-Remaining").orElse("0"));
      long limit = nonnegative(headers.firstValue("X-RateLimit-Limit").orElse(Long.toString(remaining)));
      long slots = Math.min(remaining,limit);
      return now.plusMillis(slots == 0 ? millis : Math.ceilDiv(millis,slots));
    } catch (RuntimeException malformed) { throw PublicationAccessFailure.invalid(); }
  }

  private static long nonnegative(String raw) {
    long value = Long.parseLong(raw.strip());
    if (value < 0) throw new IllegalArgumentException();
    return value;
  }
  static Instant later(Instant first,Instant second) {
    if (first == null) return second;
    return second == null || first.isAfter(second) ? first : second;
  }
}
