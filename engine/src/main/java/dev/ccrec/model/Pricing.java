package dev.ccrec.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * What a session's tokens would cost at Anthropic's list prices (USD per million tokens). An estimate:
 * it knows nothing of discounts, batch or fast-mode rates, or a plan that bills by subscription.
 * A model it has no price for costs nothing it can state — {@link #cost} is empty, never zero.
 *
 * <p>Cache reads cost 0.1x the input price, cache writes 1.25x (5-minute) or 2x (1-hour). Thinking
 * tokens are already part of the output tokens, so they are not charged twice.
 */
public final class Pricing {

  private Pricing() {}

  /** Input and output price per million tokens. */
  public record Rate(double input, double output) {}

  public static final double CACHE_READ = 0.1;
  public static final double CACHE_WRITE_5M = 1.25;
  public static final double CACHE_WRITE_1H = 2.0;

  /** Keyed by model id without any date suffix; longer keys are tried first. */
  private static final Map<String, Rate> RATES = new LinkedHashMap<>();

  static {
    RATES.put("claude-fable-5", new Rate(10, 50));
    RATES.put("claude-mythos-5", new Rate(10, 50));
    RATES.put("claude-opus-5-5", new Rate(4, 20));
    RATES.put("claude-opus-5", new Rate(5, 25));
    RATES.put("claude-opus-4-8", new Rate(5, 25));
    RATES.put("claude-opus-4-7", new Rate(5, 25));
    RATES.put("claude-opus-4-6", new Rate(5, 25));
    RATES.put("claude-opus-4-5", new Rate(5, 25));
    RATES.put("claude-opus-4-1", new Rate(15, 75));
    RATES.put("claude-opus-4", new Rate(15, 75));
    RATES.put("claude-sonnet-5", new Rate(2, 10)); // also covers claude-sonnet-5-5
    RATES.put("claude-sonnet-4", new Rate(3, 15)); // 4, 4.5 and 4.6
    RATES.put("claude-3-7-sonnet", new Rate(3, 15));
    RATES.put("claude-haiku-5", new Rate(0.1, 0.5)); // also covers claude-haiku-5-5
    RATES.put("claude-haiku-4-5", new Rate(1, 5));
    RATES.put("claude-3-5-haiku", new Rate(0.8, 4));
  }

  /** The price of a model id, matched by its longest known prefix; empty when it is not known. */
  public static java.util.Optional<Rate> rate(String model) {
    if (model == null) {
      return java.util.Optional.empty();
    }
    Rate best = null;
    int bestLength = -1;
    for (Map.Entry<String, Rate> entry : RATES.entrySet()) {
      String key = entry.getKey();
      if (model.startsWith(key) && key.length() > bestLength) {
        best = entry.getValue();
        bestLength = key.length();
      }
    }
    return java.util.Optional.ofNullable(best);
  }

  /** The cost in USD of what {@code usage} used, or empty when the model has no known price. */
  public static OptionalDouble cost(UsageRecord usage) {
    java.util.Optional<Rate> found = rate(usage.model());
    if (found.isEmpty()) {
      return OptionalDouble.empty();
    }
    Rate rate = found.get();
    long write1h = usage.cacheCreation1hTokens();
    // Whatever the 1-hour lifetime does not account for is billed as 5-minute.
    long write5m = Math.max(0, usage.cacheCreationTokens() - write1h);
    double perMillion =
        usage.inputTokens() * rate.input()
            + usage.outputTokens() * rate.output()
            + usage.cacheReadTokens() * rate.input() * CACHE_READ
            + write5m * rate.input() * CACHE_WRITE_5M
            + write1h * rate.input() * CACHE_WRITE_1H;
    return OptionalDouble.of(perMillion / 1_000_000.0);
  }
}
