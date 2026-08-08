package za.co.albertusvdw.graphiti.ingester.application.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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

    /** Graphiti partition to write into. Keeps this data separate from other graph sources. */
    @NotBlank
    private String groupId = "screenpipe";

    /** Zone used to decide which local day a row belongs to when keying episodes. */
    @NotBlank
    private String dayBoundaryZone = "Africa/Johannesburg";

    /** Ceiling on a single episode body, to stay inside the extraction model's context. */
    @Min(500)
    private int maxEpisodeBodyChars = 12_000;

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
