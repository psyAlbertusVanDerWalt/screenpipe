package za.co.albertusvdw.graphiti.ingester.application.web.controller.v1;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.albertusvdw.graphiti.ingester.application.web.controller.v1.mapper.IngestMapper;
import za.co.albertusvdw.graphiti.ingester.application.web.controller.v1.responses.IngestReportResponse;
import za.co.albertusvdw.graphiti.ingester.application.web.controller.v1.responses.IngestSummaryResponse;
import za.co.albertusvdw.graphiti.ingester.application.web.controller.v1.responses.RetryFailedResponse;
import za.co.albertusvdw.graphiti.ingester.core.data.ingest.IngestLedgerService;
import za.co.albertusvdw.graphiti.ingester.core.data.ingest.IngestService;

/**
 * Manual control surface for the ingest.
 *
 * <p>The scheduler is the normal trigger; these exist so a run can be forced after fixing a
 * failure, and so the drop rate can be read without opening the database.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ingest")
public class IngestController {

    private final IngestService ingestService;
    private final IngestLedgerService ledgerService;
    private final IngestMapper ingestMapper;

    /**
     * Runs the ingest now and returns what it did.
     *
     * <p>Synchronous, and slow by nature — a backlog of episodes takes minutes. It is a
     * maintenance endpoint, not something to put behind a UI button.
     */
    @PostMapping("/runs")
    public ResponseEntity<IngestSummaryResponse> run() {
        return ResponseEntity.ok(ingestMapper.toResponse(ingestService.ingestAll()));
    }

    @GetMapping("/report")
    public ResponseEntity<IngestReportResponse> report() {
        return ResponseEntity.ok(ingestMapper.toResponse(ledgerService.report()));
    }

    /**
     * Gives every FAILED episode a fresh attempt budget, without running an ingest.
     *
     * <p>FAILED is terminal precisely so it never quietly retries itself on a schedule — see
     * {@link za.co.albertusvdw.graphiti.ingester.core.data.ingest.IngestStatus#isTerminal()}.
     * This is the deliberate way back, worth reaching for after something that plausibly
     * changes the outcome for previously-abandoned episodes, e.g. swapping the extraction
     * model. Doesn't post anything itself — follow with a {@code POST /runs} to actually retry.
     */
    @PostMapping("/retry-failed")
    public ResponseEntity<RetryFailedResponse> retryFailed() {
        return ResponseEntity.ok(new RetryFailedResponse(ledgerService.retryFailed()));
    }
}
