package za.co.albertusvdw.graphiti.ingester.core.data.export;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * screenpipe's deterministic, LLM-free grouping backbone, mirrored from
 * {@code crates/screenpipe-semantic/src/model.rs}.
 *
 * <p>This is the main cost lever of the whole pipeline (fork issue #17): episodes are
 * assembled from these kinds by plain code, so no model is spent deciding what belongs
 * together. Per-frame LLM extraction would be the obvious alternative and is exactly what
 * this avoids.
 */
public enum SemanticKind {

    @JsonProperty("conversation")
    CONVERSATION,

    @JsonProperty("message")
    MESSAGE,

    @JsonProperty("document")
    DOCUMENT,

    @JsonProperty("task")
    TASK,

    @JsonProperty("calendar_event")
    CALENDAR_EVENT,

    @JsonProperty("page")
    PAGE;

    /**
     * Whether rows of this kind are children that belong inside a parent episode rather
     * than being an episode in their own right.
     *
     * <p>A message is a turn within a conversation; a conversation is the episode. Posting
     * each message separately would fragment one meeting into dozens of episodes and
     * multiply extraction cost for a worse graph.
     */
    public boolean isEpisodeMember() {
        return this == MESSAGE;
    }
}
