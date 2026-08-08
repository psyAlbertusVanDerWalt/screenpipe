package za.co.albertusvdw.graphiti.ingester.core.data.ontology;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The 18 edge types of the deployed graphiti-kg ontology.
 *
 * <p>Same contract as {@link EntityType}: an allowlist, not a hint. See that class for
 * why out-of-enum values are rejected rather than passed through.
 */
public enum EdgeType {

    WORKS_ON("WORKS_ON"),
    OWNS("OWNS"),
    PART_OF("PART_OF"),
    BLOCKS("BLOCKS"),
    DEPENDS_ON("DEPENDS_ON"),
    DECIDED("DECIDED"),
    SUPERSEDES("SUPERSEDES"),
    CONCERNS("CONCERNS"),
    RESOLVES("RESOLVES"),
    ENCOUNTERED("ENCOUNTERED"),
    PROMISED_TO("PROMISED_TO"),
    ATTENDED("ATTENDED"),
    USES("USES"),
    PRODUCED("PRODUCED"),
    EVIDENCED_BY("EVIDENCED_BY"),
    COLLABORATES_WITH("COLLABORATES_WITH"),
    PREFERS("PREFERS"),
    LEARNED("LEARNED");

    private static final Map<String, EdgeType> BY_WIRE_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(
                    type -> type.wireName.toLowerCase(Locale.ROOT), Function.identity()));

    private final String wireName;

    EdgeType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<EdgeType> fromWireName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_WIRE_NAME.get(candidate.strip().toLowerCase(Locale.ROOT)));
    }
}
