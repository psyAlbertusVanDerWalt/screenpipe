package za.co.albertusvdw.graphiti.ingester.core.data.graphiti.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import za.co.albertusvdw.graphiti.ingester.application.config.GraphitiProperties;
import za.co.albertusvdw.graphiti.ingester.core.common.exception.GraphitiException;
import za.co.albertusvdw.graphiti.ingester.core.data.episode.Episode;
import za.co.albertusvdw.graphiti.ingester.core.data.export.SemanticKind;

class McpGraphitiClientTest {

    private McpGraphitiClient client;

    @BeforeEach
    void setUp() {
        GraphitiProperties properties = new GraphitiProperties();
        // Port 9 is the discard port — reliably refuses, so this exercises the unreachable path
        // without depending on anything being down.
        properties.setMcpUrl("http://127.0.0.1:9/mcp/");
        properties.setRequestTimeout(Duration.ofSeconds(2));
        client = new McpGraphitiClient(RestClient.builder(), new ObjectMapper(), properties);
    }

    @Test
    @DisplayName("an unreachable endpoint surfaces as a retryable GraphitiException, not a raw transport error")
    void wrapsTransportFailures() {
        // Regression: RestClient's ResourceAccessException used to escape ensureSession()
        // unwrapped. Callers catch GraphitiException, so it sailed past the per-episode handler
        // and aborted the whole batch with a 500 — the opposite of resume-from-cursor.
        Episode episode = new Episode(
                "key", "name", "body", SemanticKind.DOCUMENT, Instant.parse("2026-08-07T09:00:00Z"), List.of(1L));

        assertThatThrownBy(() -> client.addMemory(episode, "group"))
                .isInstanceOf(GraphitiException.class)
                .satisfies(thrown -> assertThat(((GraphitiException) thrown).isRetryable())
                        .as("an unreachable endpoint is worth retrying")
                        .isTrue());
    }

    @Test
    @DisplayName("episodeNames wraps transport failures the same way")
    void wrapsTransportFailuresWhenVerifying() {
        assertThatThrownBy(() -> client.episodeNames("group", 10)).isInstanceOf(GraphitiException.class);
    }

    @Test
    @DisplayName("an unreachable server reports unhealthy rather than throwing")
    void reportsUnhealthyWhenUnreachable() {
        assertThat(client.isHealthy()).isFalse();
    }
}
