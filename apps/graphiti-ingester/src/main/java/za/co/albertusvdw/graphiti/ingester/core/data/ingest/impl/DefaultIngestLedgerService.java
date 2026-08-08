package za.co.albertusvdw.graphiti.ingester.core.data.ingest.impl;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.albertusvdw.graphiti.ingester.core.data.episode.Episode;
import za.co.albertusvdw.graphiti.ingester.core.data.ingest.IngestLedgerService;
import za.co.albertusvdw.graphiti.ingester.core.data.ingest.IngestReport;
import za.co.albertusvdw.graphiti.ingester.core.data.ingest.IngestStatus;
import za.co.albertusvdw.graphiti.ingester.core.data.ingest.IngestedEpisode;
import za.co.albertusvdw.graphiti.ingester.core.data.ingest.IngestedEpisodeRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultIngestLedgerService implements IngestLedgerService {

    private final IngestedEpisodeRepository repository;

    @Override
    public Set<String> findSettledKeys(List<String> episodeKeys) {
        if (episodeKeys.isEmpty()) {
            return Set.of();
        }
        return repository.findByEpisodeKeyIn(episodeKeys).stream()
                .filter(episode -> episode.getStatus().isTerminal())
                .map(IngestedEpisode::getEpisodeKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional
    public IngestedEpisode claim(Episode episode, String groupId) {
        IngestedEpisode ledgerRow = repository
                .findByEpisodeKey(episode.episodeKey())
                .orElseGet(() -> new IngestedEpisode(
                        episode.episodeKey(), episode.name(), groupId, episode.occurredAt()));
        ledgerRow.recordAttempt();
        return repository.save(ledgerRow);
    }

    @Override
    @Transactional
    public void markPosted(Long id) {
        repository.findById(id).ifPresent(episode -> episode.markPosted(Instant.now()));
    }

    @Override
    @Transactional
    public void markVerified(Long id) {
        repository.findById(id).ifPresent(episode -> episode.markVerified(Instant.now()));
    }

    @Override
    @Transactional
    public void markFailed(Long id, IngestStatus terminalStatus, String error) {
        repository.findById(id).ifPresent(episode -> episode.markFailed(terminalStatus, error));
    }

    @Override
    public IngestReport report() {
        return new IngestReport(
                repository.countByStatus(IngestStatus.PENDING),
                repository.countByStatus(IngestStatus.POSTED),
                repository.countByStatus(IngestStatus.VERIFIED),
                repository.countByStatus(IngestStatus.DROPPED),
                repository.countByStatus(IngestStatus.FAILED),
                repository.countByStatus(IngestStatus.REJECTED));
    }
}
