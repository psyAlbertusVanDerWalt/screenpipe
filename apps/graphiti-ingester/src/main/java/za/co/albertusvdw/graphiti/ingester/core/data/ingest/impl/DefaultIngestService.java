package za.co.albertusvdw.graphiti.ingester.core.data.ingest.impl;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import za.co.albertusvdw.graphiti.ingester.application.config.GraphitiProperties;
import za.co.albertusvdw.graphiti.ingester.application.config.IngestProperties;
import za.co.albertusvdw.graphiti.ingester.core.common.exception.GraphitiException;
import za.co.albertusvdw.graphiti.ingester.core.data.episode.Episode;
import za.co.albertusvdw.graphiti.ingester.core.data.episode.EpisodeGrouper;
import za.co.albertusvdw.graphiti.ingester.core.data.export.ExportReader;
import za.co.albertusvdw.graphiti.ingester.core.data.graphiti.GraphitiClient;
import za.co.albertusvdw.graphiti.ingester.core.data.ingest.IngestLedgerService;
import za.co.albertusvdw.graphiti.ingester.core.data.ingest.IngestService;
import za.co.albertusvdw.graphiti.ingester.core.data.ingest.IngestStatus;
import za.co.albertusvdw.graphiti.ingester.core.data.ingest.IngestSummary;
import za.co.albertusvdw.graphiti.ingester.core.data.ingest.IngestedEpisode;

/**
 * Orchestrates one ingest run.
 *
 * <p><strong>Deliberately not {@code @Transactional}.</strong> This class spends minutes
 * inside HTTP calls to an extraction model that has been measured taking anywhere from 18s
 * to over 180s per episode. Wrapping that in a transaction — even a read-only one — would
 * pin a pooled database connection for the length of the whole batch. All persistence goes
 * through {@link IngestLedgerService}, whose transactions are short and database-only.
 */
@Slf4j
@Service
public class DefaultIngestService implements IngestService {

    /**
     * How many recent episodes to scan when verifying.
     *
     * <p>Wide enough that a just-posted episode is still inside the window after the other
     * episodes in the same batch have landed on top of it.
     */
    private static final int VERIFY_WINDOW = 100;

    private final ExportReader exportReader;
    private final EpisodeGrouper episodeGrouper;
    private final GraphitiClient graphitiClient;
    private final IngestLedgerService ledger;
    private final IngestProperties ingestProperties;
    private final GraphitiProperties graphitiProperties;

    public DefaultIngestService(
            ExportReader exportReader,
            EpisodeGrouper episodeGrouper,
            GraphitiClient graphitiClient,
            IngestLedgerService ledger,
            IngestProperties ingestProperties,
            GraphitiProperties graphitiProperties) {
        this.exportReader = exportReader;
        this.episodeGrouper = episodeGrouper;
        this.graphitiClient = graphitiClient;
        this.ledger = ledger;
        this.ingestProperties = ingestProperties;
        this.graphitiProperties = graphitiProperties;
    }

    @Override
    public IngestSummary ingestAll() {
        List<Path> files = exportReader.listExportFiles();
        if (files.isEmpty()) {
            log.info("no export files to ingest");
            return IngestSummary.empty();
        }

        int grouped = 0;
        int skippedIngested = 0;
        int skippedThin = 0;
        int verified = 0;
        int dropped = 0;
        int failed = 0;

        for (Path file : files) {
            List<Episode> episodes = episodeGrouper.group(exportReader.read(file));
            grouped += episodes.size();

            Set<String> settled = ledger.findSettledKeys(episodes.stream().map(Episode::episodeKey).toList());

            for (Episode episode : episodes) {
                if (settled.contains(episode.episodeKey())) {
                    skippedIngested++;
                    continue;
                }
                if (episode.bodyLength() < ingestProperties.getMinEpisodeBodyChars()) {
                    skippedThin++;
                    continue;
                }

                switch (ingestOne(episode)) {
                    case VERIFIED -> verified++;
                    case DROPPED -> dropped++;
                    default -> failed++;
                }
            }
        }

        IngestSummary summary = new IngestSummary(
                files.size(), grouped, skippedIngested, skippedThin, verified, dropped, failed);
        log.info(
                "ingest run complete: {} file(s), {} episode(s) grouped, {} verified, {} dropped, {} failed,"
                        + " {} already ingested, {} too thin",
                summary.filesRead(),
                summary.episodesGrouped(),
                summary.verified(),
                summary.dropped(),
                summary.failed(),
                summary.skippedAlreadyIngested(),
                summary.skippedTooThin());
        return summary;
    }

    private IngestStatus ingestOne(Episode episode) {
        String groupId = ingestProperties.getGroupId();
        IngestedEpisode ledgerRow = ledger.claim(episode, groupId);

        if (ledgerRow.getAttempts() > graphitiProperties.getMaxAttempts()) {
            log.warn(
                    "episode '{}' exhausted its {} attempt(s), parking as failed",
                    episode.name(),
                    graphitiProperties.getMaxAttempts());
            ledger.markFailed(ledgerRow.getId(), IngestStatus.FAILED, "attempt budget exhausted");
            return IngestStatus.FAILED;
        }

        try {
            postWithBackoff(episode, groupId);
        } catch (GraphitiException exception) {
            log.warn("episode '{}' failed to post: {}", episode.name(), exception.getMessage());
            // Left non-terminal so the next scheduled run retries it — a failure here is
            // usually the model or the workstation being unavailable, not bad data.
            ledger.markFailed(ledgerRow.getId(), IngestStatus.FAILED, exception.getMessage());
            return IngestStatus.FAILED;
        }
        ledger.markPosted(ledgerRow.getId());

        if (!graphitiProperties.isVerifyAfterPost()) {
            return IngestStatus.VERIFIED;
        }
        if (confirmLanded(episode, groupId)) {
            ledger.markVerified(ledgerRow.getId());
            return IngestStatus.VERIFIED;
        }

        log.warn(
                "episode '{}' was accepted but never appeared in the graph — silent drop (see fork issue #20)",
                episode.name());
        ledger.markFailed(ledgerRow.getId(), IngestStatus.DROPPED, "accepted but absent from the graph");
        return IngestStatus.DROPPED;
    }

    /** Posts, retrying only on failures that could plausibly succeed next time. */
    private void postWithBackoff(Episode episode, String groupId) {
        Duration backoff = graphitiProperties.getInitialBackoff();
        GraphitiException last = null;

        for (int attempt = 1; attempt <= graphitiProperties.getMaxAttempts(); attempt++) {
            try {
                graphitiClient.addMemory(episode, groupId);
                return;
            } catch (GraphitiException exception) {
                last = exception;
                if (!exception.isRetryable() || attempt == graphitiProperties.getMaxAttempts()) {
                    throw exception;
                }
                log.debug(
                        "post attempt {}/{} for '{}' failed ({}), retrying in {}s",
                        attempt,
                        graphitiProperties.getMaxAttempts(),
                        episode.name(),
                        exception.getMessage(),
                        backoff.toSeconds());
                sleep(backoff);
                backoff = nextBackoff(backoff);
            }
        }
        throw last;
    }

    /**
     * Checks that a posted episode actually exists.
     *
     * <p>{@code add_memory} returns once the episode is queued, so absence immediately after
     * posting means nothing. Each check is spaced by {@code verifyDelay} to give the queue
     * time to work through it before concluding it was dropped.
     */
    private boolean confirmLanded(Episode episode, String groupId) {
        for (int attempt = 1; attempt <= graphitiProperties.getVerifyAttempts(); attempt++) {
            sleep(graphitiProperties.getVerifyDelay());
            try {
                if (graphitiClient.episodeNames(groupId, VERIFY_WINDOW).contains(episode.name())) {
                    return true;
                }
            } catch (GraphitiException exception) {
                // A failed check is not evidence of a drop — keep checking rather than
                // condemning an episode that may well have landed.
                log.debug("verification attempt {} failed: {}", attempt, exception.getMessage());
            }
        }
        return false;
    }

    private Duration nextBackoff(Duration current) {
        Duration doubled = current.multipliedBy(2);
        return doubled.compareTo(graphitiProperties.getMaxBackoff()) > 0
                ? graphitiProperties.getMaxBackoff()
                : doubled;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GraphitiException("ingest run interrupted while waiting", exception, false);
        }
    }
}
