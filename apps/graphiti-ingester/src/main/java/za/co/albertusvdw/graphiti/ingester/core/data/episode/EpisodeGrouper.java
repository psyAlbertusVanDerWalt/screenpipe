package za.co.albertusvdw.graphiti.ingester.core.data.episode;

import java.util.List;
import za.co.albertusvdw.graphiti.ingester.core.data.export.ExportRecord;

/** Assembles raw export rows into the episodes that actually get posted to the graph. */
public interface EpisodeGrouper {

    /**
     * Group one batch of rows into episodes.
     *
     * <p>Deterministic and side-effect free: the same rows always produce the same episodes
     * with the same keys. That is what makes re-running an export file safe — the second run
     * produces keys the ingest ledger already knows about and skips them, rather than
     * duplicating the day's activity in the graph.
     *
     * @param records rows from one or more export files, in any order
     * @return episodes ordered by occurrence time, oldest first
     */
    List<Episode> group(List<ExportRecord> records);
}
