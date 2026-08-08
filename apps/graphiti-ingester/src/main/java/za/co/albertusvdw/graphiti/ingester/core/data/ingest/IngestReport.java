package za.co.albertusvdw.graphiti.ingester.core.data.ingest;

/**
 * Ledger totals by status.
 *
 * <p>{@code dropped} is the number that matters operationally: episodes the server accepted
 * and then lost. A rising drop count is the signal to revisit the extraction model choice
 * (fork issue #20), and it is invisible unless counted separately from outright failures.
 */
public record IngestReport(long pending, long posted, long verified, long dropped, long failed, long rejected) {

    public long total() {
        return pending + posted + verified + dropped + failed + rejected;
    }

    /** Share of attempted posts that vanished, as a percentage. */
    public double dropRatePercent() {
        long attempted = verified + dropped;
        return attempted == 0 ? 0.0 : (dropped * 100.0) / attempted;
    }
}
