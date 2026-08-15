package za.co.albertusvdw.graphiti.ingester.application.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import za.co.albertusvdw.graphiti.ingester.core.data.episode.ActivityDomain;

/** Tunables for reading the export and shaping episodes. */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "ingest")
public class IngestProperties {

    /**
     * Directory holding the redacted JSONL files pulled from the workstation.
     *
     * <p>Must point at the redacted export, never at a screenpipe data directory. The
     * pipeline's entire privacy guarantee is that unredacted text never leaves the
     * workstation, and this service has no way to tell the two apart by inspection.
     */
    @NotBlank
    private String exportDir = "/data/redacted-jsonl";

    /**
     * Graphiti partitions episodes are routed into, by {@link za.co.albertusvdw.graphiti.ingester.core.data.episode.ActivityDomain}.
     *
     * <p>Three separate partitions, not one — a work/personal mixup here is exactly the failure
     * a timesheet or a "what did I do yesterday" summary can't tolerate. Unclassified activity
     * gets its own partition rather than falling into either side by default, so anything the
     * classifier wasn't confident about stays visible and reviewable instead of silently
     * miscounted as billable work or silently dropped from the work view.
     */
    @NotBlank
    private String workGroupId = "screenpipe-work";

    @NotBlank
    private String personalGroupId = "screenpipe-personal";

    @NotBlank
    private String unclassifiedGroupId = "screenpipe-unclassified";

    /** The single place that turns a domain decision into the partition it gets posted to. */
    public String groupIdFor(ActivityDomain domain) {
        return switch (domain) {
            case WORK -> workGroupId;
            case PERSONAL -> personalGroupId;
            case UNCLASSIFIED -> unclassifiedGroupId;
        };
    }

    /** Zone used to decide which local day a row belongs to when keying episodes. */
    @NotBlank
    private String dayBoundaryZone = "Africa/Johannesburg";

    /**
     * Ceiling on a single episode body.
     *
     * <p>Not just a context-window guard — graphiti-core's extraction has been observed to
     * silently under-extract (miss entities and relationships, no error) well before the
     * model's actual context limit, somewhere past ~10,000 characters. Kept safely under that.
     */
    @Min(500)
    private int maxEpisodeBodyChars = 8_000;

    /** Rows read per batch when streaming a file. */
    @Min(1)
    private int batchSize = 500;

    /** Skip episodes whose body is shorter than this — too thin to extract anything useful. */
    @Min(0)
    private int minEpisodeBodyChars = 40;

    /**
     * Shared secret the workstation presents when pushing an export file.
     *
     * <p>Blank disables the upload endpoint entirely rather than leaving it open. Failing
     * closed matters more than convenience here: the endpoint writes files that later become
     * graph content, so an unauthenticated one is a content-injection route.
     */
    private String uploadToken = "";

    /** Ceiling on a single uploaded file. A day's real export is a few hundred KB. */
    @Min(1024)
    private long maxUploadBytes = 64L * 1024 * 1024;

    private Schedule schedule = new Schedule();

    @Getter
    @Setter
    public static class Schedule {

        private boolean enabled = true;

        /** Twice a day inside working hours, when the workstation is awake. */
        @NotBlank
        private String cron = "0 0 10,15 * * MON-FRI";
    }
}
