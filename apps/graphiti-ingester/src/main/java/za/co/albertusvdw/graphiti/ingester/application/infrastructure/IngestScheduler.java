package za.co.albertusvdw.graphiti.ingester.application.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.co.albertusvdw.graphiti.ingester.core.data.ingest.IngestService;

/**
 * Runs the ingest on a schedule.
 *
 * <p>The schedule must land inside working hours. Both the extraction model and the export
 * files themselves live on the workstation, so a run at 03:00 finds an asleep machine and
 * burns its whole attempt budget on connection failures.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ingest.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class IngestScheduler {

    private final IngestService ingestService;

    @Scheduled(cron = "${ingest.schedule.cron:0 0 10,15 * * MON-FRI}", zone = "${ingest.day-boundary-zone}")
    public void run() {
        log.info("scheduled ingest starting");
        try {
            ingestService.ingestAll();
        } catch (RuntimeException exception) {
            // Never let a scheduled run's exception escape: an uncaught one here kills nothing
            // else but produces a bare stack trace with no run context, and the next run would
            // pick the work up anyway.
            log.error("scheduled ingest failed: {}", exception.getMessage(), exception);
        }
    }
}
