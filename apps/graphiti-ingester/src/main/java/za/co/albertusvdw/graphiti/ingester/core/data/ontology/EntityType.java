package za.co.albertusvdw.graphiti.ingester.core.data.ontology;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The 13 entity types of the deployed graphiti-kg ontology.
 *
 * <p>This enum is the allowlist referenced by fork issue #17: both local and cloud
 * models invent out-of-enum types under load — one measured run against a large cloud
 * model produced roughly ten extra types that were never in the ontology. Anything not
 * named here is rejected before it reaches the graph rather than being silently
 * absorbed into it.
 *
 * <p>The source of truth is the {@code config-init} blob in the graphiti-kg Coolify
 * service's compose file, mirrored in {@code C:\dev\graphiti-kg\scripts\test-ontology.ps1}.
 * If the deployed ontology changes, this enum must change with it — a drift here means
 * valid extractions get rejected, which is quieter and worse than the reverse.
 */
public enum EntityType {

    PERSON("Person"),
    ORGANIZATION("Organization"),
    PROJECT("Project"),
    TASK("Task"),
    COMMITMENT("Commitment"),
    DECISION("Decision"),
    PROBLEM("Problem"),
    SOLUTION("Solution"),
    ARTIFACT("Artifact"),
    TECHNOLOGY("Technology"),
    MEETING("Meeting"),
    PREFERENCE("Preference"),
    INSIGHT("Insight");

    private static final Map<String, EntityType> BY_WIRE_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(
                    type -> type.wireName.toLowerCase(Locale.ROOT), Function.identity()));

    private final String wireName;

    EntityType(String wireName) {
        this.wireName = wireName;
    }

    /** The exact spelling the ontology uses on the wire, e.g. {@code Person}. */
    public String wireName() {
        return wireName;
    }

    /**
     * Resolve a model-supplied type name, or empty if it is not in the ontology.
     *
     * <p>Matching is case-insensitive on purpose. Models routinely return {@code person}
     * or {@code PERSON} for what is unambiguously the ontology's {@code Person}; rejecting
     * those would discard good extractions over a casing difference. Anything beyond a
     * casing difference is a genuine out-of-enum type and is not resolved here.
     */
    public static Optional<EntityType> fromWireName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_WIRE_NAME.get(candidate.strip().toLowerCase(Locale.ROOT)));
    }
}
