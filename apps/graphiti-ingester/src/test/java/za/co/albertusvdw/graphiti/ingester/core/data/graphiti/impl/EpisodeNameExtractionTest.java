package za.co.albertusvdw.graphiti.ingester.core.data.graphiti.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import za.co.albertusvdw.graphiti.ingester.application.config.GraphitiProperties;
import za.co.albertusvdw.graphiti.ingester.core.data.graphiti.EpisodeSnapshot;

/**
 * Pins the shapes {@code get_episodes} actually answers with.
 *
 * <p>Regression: the parser only looked for a top-level {@code episodes} array. The live server
 * nests it under {@code result}, so verification found nothing, and five episodes that had
 * genuinely landed in the graph were recorded as DROPPED — reported as a 100% server drop rate
 * when the server had done nothing wrong.
 */
class EpisodeNameExtractionTest {

    private McpGraphitiClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        GraphitiProperties properties = new GraphitiProperties();
        properties.setMcpUrl("http://127.0.0.1:9/mcp");
        properties.setRequestTimeout(Duration.ofSeconds(1));
        objectMapper = new ObjectMapper();
        client = new McpGraphitiClient(RestClient.builder(), objectMapper, properties);
    }

    @SuppressWarnings("unchecked")
    private List<EpisodeSnapshot> extract(String json) throws Exception {
        Method method = McpGraphitiClient.class.getDeclaredMethod(
                "extractEpisodeSnapshots", com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);
        return (List<EpisodeSnapshot>) method.invoke(client, objectMapper.readTree(json));
    }

    private List<String> names(String json) throws Exception {
        return extract(json).stream().map(EpisodeSnapshot::name).toList();
    }

    @Test
    @DisplayName("the live server's shape: episodes nested under a result wrapper")
    void readsTheNestedResultShape() throws Exception {
        String body =
                """
                {"structuredContent":{"result":{"message":"Episodes retrieved successfully",
                "episodes":[{"uuid":"a","name":"edit","group_id":"screenpipe"},
                {"uuid":"b","name":"Command Prompt","group_id":"screenpipe"}]}}}
                """;

        assertThat(names(body)).containsExactly("edit", "Command Prompt");
    }

    @Test
    @DisplayName("a top-level episodes array still works")
    void readsTheFlatShape() throws Exception {
        String body = "{\"structuredContent\":{\"episodes\":[{\"name\":\"standup\"}]}}";

        assertThat(names(body)).containsExactly("standup");
    }

    @Test
    @DisplayName("episodes delivered as JSON inside a text content block still work")
    void readsTheTextContentShape() throws Exception {
        String body =
                "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result\\\":{\\\"episodes\\\":"
                        + "[{\\\"name\\\":\\\"from-text\\\"}]}}\"}]}";

        assertThat(names(body)).containsExactly("from-text");
    }

    @Test
    @DisplayName("an unrecognised shape yields empty rather than a wrong answer")
    void unrecognisedShapeIsEmpty() throws Exception {
        assertThat(extract("{\"structuredContent\":{\"unexpected\":true}}")).isEmpty();
    }

    @Test
    @DisplayName("episodes without names are skipped, not returned as nulls")
    void skipsNamelessEpisodes() throws Exception {
        String body = "{\"structuredContent\":{\"result\":{\"episodes\":[{\"uuid\":\"a\"},{\"name\":\"kept\"}]}}}";

        assertThat(names(body)).containsExactly("kept");
    }

    @Test
    @DisplayName("content travels alongside name, so callers can tell two same-named episodes apart")
    void carriesContentAlongsideName() throws Exception {
        String body = "{\"structuredContent\":{\"episodes\":[{\"name\":\"Email - Outlook\",\"content\":\"body one\"}]}}";

        assertThat(extract(body)).containsExactly(new EpisodeSnapshot("Email - Outlook", "body one"));
    }
}
