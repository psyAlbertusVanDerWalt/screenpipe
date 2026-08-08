package za.co.albertusvdw.graphiti.ingester.application.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Connection and reliability settings for the graphiti-mcp endpoint. */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "graphiti")
public class GraphitiProperties {

    /**
     * MCP endpoint. LAN-only by design.
     *
     * <p>No trailing slash: {@code /mcp/} answers 307 to {@code /mcp}, and following that
     * redirect makes the client recompute the Host header from the new URL — discarding the
     * one set below and turning every call into a 421.
     */
    @NotBlank
    private String mcpUrl = "http://10.0.0.69:18000/mcp";

    /**
     * Host header to present to graphiti-mcp.
     *
     * <p>The server runs FastMCP's DNS-rebinding protection, which validates Host against the
     * address it believes it is served on — the container's internal {@code :8000}, not the
     * published {@code :18000}. Connecting to the published port therefore sends
     * {@code Host: 10.0.0.69:18000} and is rejected with
     * {@code 421 Destination Locked: "Invalid Host header"}. Measured: {@code localhost:8000}
     * and {@code 127.0.0.1:8000} are accepted, everything else is refused.
     *
     * <p>Overriding the header here rather than loosening the server's allowed hosts keeps the
     * rebinding protection intact for every other client of that service. Blank sends no
     * override, for the case where the ingester reaches graphiti-mcp over the Docker network
     * on its real port.
     */
    private String hostHeader = "localhost:8000";

    /**
     * Per-request timeout.
     *
     * <p>Generous on purpose. The extraction model behind this endpoint has been measured
     * between 18s and 180s+ for a single episode — a slow response is normal, not a fault,
     * and a tight timeout turns a working call into a retry storm.
     */
    private Duration requestTimeout = Duration.ofMinutes(5);

    /** Attempts per episode before it is parked as failed. */
    @Min(1)
    private int maxAttempts = 4;

    /** First retry delay; doubles each attempt. */
    private Duration initialBackoff = Duration.ofSeconds(5);

    /** Ceiling on the exponential backoff. */
    private Duration maxBackoff = Duration.ofMinutes(2);

    /**
     * How long to wait before checking that a posted episode actually landed.
     *
     * <p>{@code add_memory} returns as soon as the episode is queued, not once it is
     * processed, so an immediate check would report a false absence for every episode.
     */
    private Duration verifyDelay = Duration.ofSeconds(20);

    /** How many times to re-check for a posted episode before treating it as dropped. */
    @Min(1)
    private int verifyAttempts = 3;

    /**
     * Whether to verify at all.
     *
     * <p>Defaults on because a live 10-episode test against this deployment measured a 40%
     * silent-drop rate: the model returns a null where Graphiti's schema requires a string,
     * the background queue logs it, and the episode never appears — while the caller sees a
     * perfectly normal queued response. See fork issue #20.
     */
    private boolean verifyAfterPost = true;
}
