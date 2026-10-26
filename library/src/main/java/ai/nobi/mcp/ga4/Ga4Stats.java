package ai.nobi.mcp.ga4;

/**
 * A snapshot of event counts, rather than HTTP request counts.
 * A delivered event only received HTTP 2xx; this does not prove GA4 processed or reported it.
 *
 * @param accepted events accepted into the local queue
 * @param delivered events included in HTTP 2xx requests
 * @param rejected events refused because the queue is full, the client is closed, or the time is invalid
 * @param failed accepted events that ended without HTTP 2xx
 */
public record Ga4Stats(long accepted, long delivered, long rejected, long failed) {
}
