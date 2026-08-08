package za.co.albertusvdw.graphiti.ingester.core.data.ingest;

import java.util.List;
import java.util.Set;
import za.co.albertusvdw.graphiti.ingester.core.data.episode.Episode;

/**
 * Owns every read and write of the ingest ledger.
 *
 * <p>Split out from the ingest orchestrator so that database transactions stay short and
 * database-only. The orchestrator spends minutes at a time inside HTTP calls to the
 * extraction model, and a transaction held open across that would pin a pooled connection
 * for the duration of the whole batch.
 */
public interface IngestLedgerService {

    /** Episode keys already in a terminal state, which a re-run must not touch. */
    Set<String> findSettledKeys(List<String> episodeKeys);

    /** Insert or fetch the ledger row for an episode, and count this attempt against it. */
    IngestedEpisode claim(Episode episode, String groupId);

    void markPosted(Long id);

    void markVerified(Long id);

    void markFailed(Long id, IngestStatus terminalStatus, String error);

    IngestReport report();
}
