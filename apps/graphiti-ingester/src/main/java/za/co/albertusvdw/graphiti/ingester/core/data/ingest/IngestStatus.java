package za.co.albertusvdw.graphiti.ingester.core.data.ingest;

/** Where an episode is in the post-then-verify lifecycle. */
public enum IngestStatus {

    /** Grouped and validated, not yet sent. */
    PENDING,

    /** Accepted into graphiti-mcp's background queue, not yet confirmed to exist. */
    POSTED,

    /** Confirmed present in the graph. The only status that means "done". */
    VERIFIED,

    /**
     * Posted, but absent from the graph when checked, and out of attempts.
     *
     * <p>Distinct from {@link #FAILED} because nothing errored — the call succeeded and the
     * episode still vanished. This is the silent-drop failure mode from fork issue #20, and
     * keeping it separate is what makes its rate measurable instead of invisible.
     */
    DROPPED,

    /** The call itself failed and the attempt budget is exhausted. */
    FAILED,

    /** Rejected before posting because it would have poisoned the graph. */
    REJECTED;

    /**
     * Whether a later run should leave this episode alone.
     *
     * <p>{@link #FAILED} counts as terminal on purpose, even though it is a failure: without
     * this, a run that finds {@code attempts > maxAttempts} re-claims the row, bumps
     * {@code attempts} again, and re-marks it FAILED with the same generic message — forever,
     * every run, overwriting whatever the real last error was. The only way back from FAILED is
     * the explicit retry-failed maintenance operation, which resets attempts to zero — not
     * quietly retrying itself into the same wall on a schedule.
     */
    public boolean isTerminal() {
        return this == VERIFIED || this == REJECTED || this == FAILED;
    }
}
