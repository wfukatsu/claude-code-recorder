package dev.ccrec.model;

/**
 * What a session spent on one model: the API messages it received from it and what they used, summed
 * over the main thread and every sub-agent. {@code sessionId} is null in a total over sessions.
 *
 * <p>{@code thinkingTokens} are part of {@code outputTokens}, and the two {@code cacheCreation…}
 * lifetimes part of {@code cacheCreationTokens}: they break those down, they do not add to them.
 */
public record UsageRecord(
    String sessionId,
    String accountId,
    String model,
    long messages,
    long inputTokens,
    long outputTokens,
    long cacheReadTokens,
    long cacheCreationTokens,
    long thinkingTokens,
    long cacheCreation5mTokens,
    long cacheCreation1hTokens,
    long webSearchRequests,
    long webFetchRequests) {

  /** Stands in for the model of a message that carries usage and names none. */
  public static final String UNKNOWN_MODEL = "unknown";

  /** The number of counters: {@code messages} and the nine that follow it. */
  public static final int COUNTERS = 10;

  public static UsageRecord of(String sessionId, String accountId, String model, long[] c) {
    return new UsageRecord(sessionId, accountId, model, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9]);
  }

  public long[] counters() {
    return new long[] {
      messages, inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens,
      thinkingTokens, cacheCreation5mTokens, cacheCreation1hTokens, webSearchRequests, webFetchRequests
    };
  }

  public UsageRecord plus(UsageRecord other) {
    long[] sum = counters();
    long[] added = other.counters();
    for (int i = 0; i < sum.length; i++) {
      sum[i] += added[i];
    }
    return of(sessionId, accountId, model, sum);
  }
}
