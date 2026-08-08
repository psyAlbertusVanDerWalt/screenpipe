package za.co.albertusvdw.graphiti.ingester.core.data.graphiti.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import za.co.albertusvdw.graphiti.ingester.application.config.GraphitiProperties;
import za.co.albertusvdw.graphiti.ingester.core.common.exception.GraphitiException;
import za.co.albertusvdw.graphiti.ingester.core.data.episode.Episode;
import za.co.albertusvdw.graphiti.ingester.core.data.graphiti.GraphitiClient;

/**
 * MCP-over-HTTP client for graphiti-mcp.
 *
 * <p>Speaks the streamable-HTTP transport directly rather than pulling in an MCP SDK: this
 * service calls exactly three tools and needs no sampling, no roots and no server-initiated
 * messages, so the JSON-RPC POST leg is the entire surface area.
 *
 * <p>Two transport details the server requires and that are easy to get wrong:
 *
 * <ul>
 *   <li>{@code Accept} must list both {@code application/json} and {@code text/event-stream}.
 *       The server rejects a request that accepts only one, and answers on either depending
 *       on the tool, so both response shapes are parsed below.
 *   <li>The session id from {@code initialize} must be echoed on every later request. A
 *       dropped session comes back as an error rather than a reconnect, so it is re-established
 *       lazily and the call retried once.
 * </ul>
 */
@Slf4j
@Component
public class McpGraphitiClient implements GraphitiClient {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final MediaType EVENT_STREAM = MediaType.TEXT_EVENT_STREAM;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final GraphitiProperties properties;
    private final AtomicReference<String> sessionId = new AtomicReference<>();
    private final AtomicReference<Long> requestId = new AtomicReference<>(0L);

    public McpGraphitiClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            GraphitiProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.getMcpUrl()).build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void addMemory(Episode episode, String groupId) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("name", episode.name());
        arguments.put("episode_body", episode.body());
        arguments.put("group_id", groupId);
        arguments.put("source", "text");
        arguments.put("source_description", "screenpipe redacted export");
        arguments.put(
                "reference_time",
                DateTimeFormatter.ISO_INSTANT.format(episode.occurredAt()));

        JsonNode result = callTool("add_memory", arguments);
        if (isToolError(result)) {
            throw new GraphitiException("add_memory reported an error: " + textOf(result), true);
        }
        log.debug("queued episode '{}' into group {}", episode.name(), groupId);
    }

    @Override
    public List<String> episodeNames(String groupId, int maxEpisodes) {
        JsonNode result = callTool(
                "get_episodes", Map.of("group_ids", List.of(groupId), "max_episodes", maxEpisodes));
        if (isToolError(result)) {
            throw new GraphitiException("get_episodes reported an error: " + textOf(result), true);
        }
        return extractEpisodeNames(result);
    }

    @Override
    public boolean isHealthy() {
        try {
            JsonNode result = callTool("get_status", Map.of());
            return !isToolError(result);
        } catch (GraphitiException exception) {
            log.warn("graphiti health check failed: {}", exception.getMessage());
            return false;
        }
    }

    /**
     * Pulls episode names out of whichever shape the server answered with.
     *
     * <p>get_episodes has been observed returning both a structured payload and a JSON blob
     * wrapped in a text content block, so both are handled rather than assuming one. An
     * unparseable response yields an empty list, which the caller reads as "not found" and
     * retries — the safe direction, since treating it as found would mark a dropped episode
     * as ingested.
     */
    private List<String> extractEpisodeNames(JsonNode result) {
        List<String> names = new ArrayList<>();
        JsonNode payload = result.path("structuredContent");
        if (payload.isMissingNode() || payload.isNull()) {
            payload = parseTextContent(result);
        }

        JsonNode episodes = payload.isArray() ? payload : payload.path("episodes");
        if (!episodes.isArray()) {
            log.debug("get_episodes returned an unrecognised shape, treating as empty");
            return List.of();
        }
        for (JsonNode episode : episodes) {
            String name = episode.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    private JsonNode parseTextContent(JsonNode result) {
        String text = textOf(result);
        if (text.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return objectMapper.nullNode();
        }
    }

    private JsonNode callTool(String toolName, Map<String, Object> arguments) {
        Map<String, Object> params = Map.of("name", toolName, "arguments", arguments);
        try {
            return sendRequest("tools/call", params);
        } catch (GraphitiException exception) {
            if (!exception.isRetryable()) {
                throw exception;
            }
            // A session that expired server-side fails every subsequent call identically,
            // so re-initialising once here is the difference between a transient blip and
            // every episode in the batch failing.
            log.debug("retrying {} after re-establishing the MCP session", toolName);
            sessionId.set(null);
            return sendRequest("tools/call", params);
        }
    }

    private JsonNode sendRequest(String method, Map<String, Object> params) {
        ensureSession();
        JsonNode response = post(buildRequest(method, params), sessionId.get());
        return unwrapResult(response, method);
    }

    /**
     * Establishes the MCP session, translating every transport failure into a
     * {@link GraphitiException}.
     *
     * <p>The translation is the point. {@code RestClient} throws {@code ResourceAccessException}
     * when the endpoint is unreachable, and callers up the stack catch only
     * {@code GraphitiException} — so an unwrapped one escapes the per-episode handler and aborts
     * the entire batch, which is exactly the failure mode this service is supposed to survive.
     */
    private synchronized void ensureSession() {
        if (sessionId.get() != null) {
            return;
        }
        Map<String, Object> params = Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "graphiti-ingester", "version", "0.1.0"));

        String issued;
        try {
            var response = restClient
                    .post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, EVENT_STREAM)
                    .body(buildRequest("initialize", params))
                    .retrieve()
                    .toEntity(String.class);

            issued = response.getHeaders().getFirst(SESSION_HEADER);
        } catch (RestClientException exception) {
            throw new GraphitiException(
                    "MCP initialize failed: " + exception.getMessage(), exception, true);
        }

        if (issued == null || issued.isBlank()) {
            throw new GraphitiException("MCP initialize returned no " + SESSION_HEADER, true);
        }

        try {
            // The spec requires this notification before any tool call; the server rejects
            // tools/call on a session that never completed the handshake.
            restClient
                    .post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, EVENT_STREAM)
                    .header(SESSION_HEADER, issued)
                    .body(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new GraphitiException(
                    "MCP initialized notification failed: " + exception.getMessage(), exception, true);
        }

        sessionId.set(issued);
        log.debug("established MCP session {}", issued);
    }

    private Map<String, Object> buildRequest(String method, Map<String, Object> params) {
        return Map.of(
                "jsonrpc", "2.0",
                "id", requestId.updateAndGet(current -> current + 1),
                "method", method,
                "params", params);
    }

    private JsonNode post(Map<String, Object> body, String session) {
        try {
            var request = restClient
                    .post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, EVENT_STREAM);
            if (session != null) {
                request = request.header(SESSION_HEADER, session);
            }
            String raw = request.body(body).retrieve().body(String.class);
            return objectMapper.readTree(extractJsonPayload(raw));
        } catch (RestClientException exception) {
            throw new GraphitiException("MCP call failed: " + exception.getMessage(), exception, true);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new GraphitiException(
                    "MCP returned an unparseable body: " + exception.getMessage(), exception, false);
        }
    }

    /**
     * Returns the JSON object from either a plain body or an SSE frame.
     *
     * <p>The same endpoint answers as {@code application/json} for some calls and as
     * {@code text/event-stream} for others, so the payload is located by shape rather than by
     * trusting the content type.
     */
    private String extractJsonPayload(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new GraphitiException("MCP returned an empty body", true);
        }
        String trimmed = raw.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        for (String line : trimmed.split("\\R")) {
            if (line.startsWith("data:")) {
                String data = line.substring("data:".length()).strip();
                if (!data.isEmpty() && !"[DONE]".equals(data)) {
                    return data;
                }
            }
        }
        throw new GraphitiException("MCP returned no JSON payload in its response", false);
    }

    private JsonNode unwrapResult(JsonNode response, String method) {
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            // JSON-RPC -32000 and friends are server-side faults worth retrying; anything in
            // the -32600..-32602 band is a malformed request that will fail identically forever.
            int code = error.path("code").asInt();
            boolean retryable = code <= -32000 && code > -32100;
            throw new GraphitiException(
                    method + " failed: " + error.path("message").asText("unknown error"), retryable);
        }
        return response.path("result");
    }

    private boolean isToolError(JsonNode result) {
        return result.path("isError").asBoolean(false);
    }

    private String textOf(JsonNode result) {
        JsonNode content = result.path("content");
        if (!content.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                builder.append(block.path("text").asText(""));
            }
        }
        return builder.toString();
    }
}
