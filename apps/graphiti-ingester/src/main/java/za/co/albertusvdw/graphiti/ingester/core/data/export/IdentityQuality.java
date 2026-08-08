package za.co.albertusvdw.graphiti.ingester.core.data.export;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * How trustworthy a row's identity is across capture runs, mirrored from
 * {@code crates/screenpipe-semantic/src/model.rs}.
 *
 * <p>Matters here because episode keys are derived from row identity. An
 * {@link #EPHEMERAL} id is only valid within a single parse run, so keying an episode
 * on one would make the same episode look new on every export and re-post it forever.
 */
public enum IdentityQuality {

    /** Backed by an app-native identifier that survives screen changes. Safe to key on. */
    @JsonProperty("stable")
    STABLE,

    /** Deterministically derived from visible fields. Grouping across runs is approximate. */
    @JsonProperty("derived")
    DERIVED,

    /** Valid only within one parse run, e.g. a positional message index. Never key on this. */
    @JsonProperty("ephemeral")
    EPHEMERAL;

    /** Whether an episode key built from this row stays stable across export runs. */
    public boolean isStableAcrossRuns() {
        return this != EPHEMERAL;
    }
}
