package ai.nobi.mcp.ga4;

/**
 * A safe delivery diagnostic that never contains an endpoint, credential, or event payload.
 *
 * @param reason the broad failure category
 * @param eventCount the number of affected events
 * @param httpStatus the response status, or zero when no response was received
 */
public record DeliveryFailure(Reason reason, int eventCount, int httpStatus) {
    /** Safe categories for failures after queue acceptance. */
    public enum Reason {
        /** The receiving endpoint returned a non-success response. */
        HTTP_ERROR,
        /** The request did not complete within the configured timeout. */
        TIMEOUT,
        /** The connection failed with unknown receiving-side processing status. */
        NETWORK_ERROR,
        /** Shutdown stopped delivery before the event received an HTTP success. */
        SHUTDOWN,
        /** The event could not be encoded into an allowed request. */
        INVALID_PAYLOAD
    }
}
