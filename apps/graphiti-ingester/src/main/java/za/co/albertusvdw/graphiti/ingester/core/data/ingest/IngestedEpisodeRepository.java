package za.co.albertusvdw.graphiti.ingester.core.data.ingest;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestedEpisodeRepository extends JpaRepository<IngestedEpisode, Long> {

    Optional<IngestedEpisode> findByEpisodeKey(String episodeKey);

    /** Ledger rows for a batch of keys, so a re-run can skip what is already terminal. */
    List<IngestedEpisode> findByEpisodeKeyIn(List<String> episodeKeys);

    List<IngestedEpisode> findByStatusOrderByOccurredAtAsc(IngestStatus status);

    long countByStatus(IngestStatus status);
}
