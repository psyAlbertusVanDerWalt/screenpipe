package za.co.albertusvdw.graphiti.ingester.core.data.graphiti;

import java.util.List;
import za.co.albertusvdw.graphiti.ingester.core.common.exception.GraphitiException;
import za.co.albertusvdw.graphiti.ingester.core.data.episode.Episode;

/** Talks to the graphiti-mcp server over MCP-on-HTTP. */
public interface GraphitiClient {

    /**
     * Queue an episode for ingestion.
     *
     * <p>A normal return means the server accepted the episode into its background queue —
     * <strong>not</strong> that it was processed or that anything reached the graph. The
     * queue has no retry and surfaces extraction failures only in its own container log, so
     * a successful call here must always be followed by {@link #episodeNames} verification
     * before the episode is considered ingested.
     *
     * @throws GraphitiException if the call itself failed
     */
    void addMemory(Episode episode, String groupId);

    /**
     * Names of the most recent episodes in a group, newest first.
     *
     * <p>Used to confirm a posted episode actually landed, since that cannot be inferred
     * from {@link #addMemory} returning normally.
     */
    List<String> episodeNames(String groupId, int maxEpisodes);

    /** True when the server is up and connected to its database. */
    boolean isHealthy();
}
