package dev.ccrec.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PricingTest {

  private static UsageRecord usage(String model, long input, long output, long cacheRead, long write, long write1h) {
    return UsageRecord.of("s", "a", model, new long[] {1, input, output, cacheRead, write, 0, write - write1h, write1h, 0, 0});
  }

  @Test
  void pricesEveryTokenKindAtItsOwnRate() {
    // Sonnet 5.5: $2 in, $10 out. 1M of each kind: 2 + 10 + 0.2 + 2.5 (5m write) + 4 (1h write).
    UsageRecord u = usage("claude-sonnet-5-5", 1_000_000, 1_000_000, 1_000_000, 2_000_000, 1_000_000);
    assertEquals(2 + 10 + 0.2 + 2.5 + 4, Pricing.cost(u).getAsDouble(), 1e-9);
  }

  @Test
  void theLongestKnownPrefixWins() {
    assertEquals(4.0, Pricing.rate("claude-opus-5-5").orElseThrow().input());
    assertEquals(5.0, Pricing.rate("claude-opus-5").orElseThrow().input());
    assertEquals(1.0, Pricing.rate("claude-haiku-4-5-20251001").orElseThrow().input());
    assertEquals(0.1, Pricing.rate("claude-haiku-5-5").orElseThrow().input());
  }

  @Test
  void anUnknownModelHasNoCostRatherThanZero() {
    assertTrue(Pricing.cost(usage("<synthetic>", 1, 1, 1, 1, 0)).isEmpty());
    assertTrue(Pricing.cost(usage(null, 1, 1, 1, 1, 0)).isEmpty());
  }
}
