package za.co.albertusvdw.graphiti.ingester.core.data.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import za.co.albertusvdw.graphiti.ingester.core.common.domain.base.AbstractAuditedEntity;

/**
 * The ingest ledger: one row per episode this service has ever tried to post.
 *
 * <p>This table <em>is</em> the cursor fork issue #17 asks for. A file-offset cursor would
 * only answer "how far did we get", which is the wrong question when the workstation may be
 * asleep, a post may be retried, and an episode may be accepted but silently dropped. Keying
 * on {@code episodeKey} answers "has this specific activity landed", so a re-run of an
 * already-processed file is a no-op and a partially-failed batch resumes at exactly the
 * episodes that still need work.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "ingested_episode")
public class IngestedEpisode extends AbstractAuditedEntity {

    /** Deterministic identity from {@code EpisodeGrouper}. Unique — the idempotency key. */
    @Column(name = "episode_key", nullable = false, unique = true, length = 512)
    private String episodeKey;

    /** Episode name as posted, used to find it again when verifying. */
    @Column(name = "episode_name", nullable = false, length = 1024)
    private String episodeName;

    @Column(name = "group_id", nullable = false, length = 128)
    private String groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private IngestStatus status = IngestStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** When the activity happened — a business timestamp, not an audit one. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "last_error", length = 2048)
    private String lastError;

    public IngestedEpisode(String episodeKey, String episodeName, String groupId, Instant occurredAt) {
        this.episodeKey = episodeKey;
        this.episodeName = episodeName;
        this.groupId = groupId;
        this.occurredAt = occurredAt;
        this.status = IngestStatus.PENDING;
    }

    public void recordAttempt() {
        this.attempts++;
    }

    public void markPosted(Instant when) {
        this.status = IngestStatus.POSTED;
        this.postedAt = when;
        this.lastError = null;
    }

    public void markVerified(Instant when) {
        this.status = IngestStatus.VERIFIED;
        this.verifiedAt = when;
        this.lastError = null;
    }

    public void markFailed(IngestStatus terminalStatus, String error) {
        this.status = terminalStatus;
        this.lastError = truncate(error);
    }

    /**
     * Gives a permanently-FAILED row a fresh attempt budget.
     *
     * <p>The only path back from FAILED — see {@link IngestStatus#isTerminal()}. Worth calling
     * after something that plausibly changes the outcome, e.g. swapping the extraction model:
     * a row that failed under one model has never actually been retried under a better one.
     */
    public void resetForRetry() {
        this.status = IngestStatus.PENDING;
        this.attempts = 0;
        this.postedAt = null;
        this.lastError = null;
    }

    /** Keeps a long stack trace or model dump from failing the insert on column length. */
    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 2048 ? error : error.substring(0, 2045) + "...";
    }
}
