package za.co.albertusvdw.graphiti.ingester.core.common.exception;

/** Raised when a call to the graphiti-mcp server fails at the transport or protocol level. */
public class GraphitiException extends RuntimeException {

    private final boolean retryable;

    public GraphitiException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public GraphitiException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * Whether retrying the same call could plausibly succeed.
     *
     * <p>Timeouts, 5xx responses and dropped sessions are retryable — the extraction model
     * behind this endpoint is genuinely slow and intermittently unavailable. A malformed
     * request or an unknown tool name is not; retrying those just burns the attempt budget
     * and delays the real error reaching a log.
     */
    public boolean isRetryable() {
        return retryable;
    }
}
