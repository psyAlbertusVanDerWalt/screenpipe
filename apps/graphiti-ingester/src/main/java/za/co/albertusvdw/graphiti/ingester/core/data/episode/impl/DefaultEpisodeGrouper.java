package za.co.albertusvdw.graphiti.ingester.core.data.episode.impl;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import za.co.albertusvdw.graphiti.ingester.application.config.IngestProperties;
import za.co.albertusvdw.graphiti.ingester.core.data.episode.Episode;
import za.co.albertusvdw.graphiti.ingester.core.data.episode.EpisodeGrouper;
import za.co.albertusvdw.graphiti.ingester.core.data.export.ExportRecord;
import za.co.albertusvdw.graphiti.ingester.core.data.export.SemanticKind;

/**
 * Groups export rows into episodes using screenpipe's semantic-kind backbone.
 *
 * <p>Three rules, in order:
 *
 * <ol>
 *   <li>Rows of a member kind (currently {@code message}) attach to the parent named by
 *       {@code parent_local_id}.
 *   <li>Every other row is a candidate episode, merged with other rows sharing its
 *       {@code item_key} on the same local day. A document open across forty frames is one
 *       work session, not forty episodes.
 *   <li>Members whose parent is not in the batch are not dropped — they are collected into a
 *       synthetic per-app, per-day episode. Parents legitimately fall outside a daily file
 *       (a conversation started yesterday), and messages are the bulk of the export, so
 *       dropping orphans would silently discard most of the day.
 * </ol>
 */
@Slf4j
@Service
public class DefaultEpisodeGrouper implements EpisodeGrouper {

    private static final String ORPHAN_PARENT_KEY_PREFIX = "orphan";

    private final IngestProperties properties;
    private final ZoneId dayBoundaryZone;

    public DefaultEpisodeGrouper(IngestProperties properties) {
        this.properties = properties;
        this.dayBoundaryZone = ZoneId.of(properties.getDayBoundaryZone());
    }

    @Override
    public List<Episode> group(List<ExportRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        List<ExportRecord> usable = records.stream()
                .filter(Objects::nonNull)
                .filter(record -> !record.isEmpty())
                .sorted(Comparator.comparing(ExportRecord::bestEffortOccurredAt)
                        .thenComparing(ExportRecord::frameId))
                .toList();

        Map<String, List<ExportRecord>> membersByParent = usable.stream()
                .filter(record -> record.kind().isEpisodeMember())
                .collect(Collectors.groupingBy(
                        record -> record.parentLocalId() == null ? "" : record.parentLocalId(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<ExportRecord> parents = usable.stream()
                .filter(record -> !record.kind().isEpisodeMember())
                .toList();

        Map<String, List<ExportRecord>> parentsByEpisodeKey = parents.stream()
                .collect(Collectors.groupingBy(this::episodeKeyFor, LinkedHashMap::new, Collectors.toList()));

        List<Episode> episodes = new ArrayList<>();
        for (Map.Entry<String, List<ExportRecord>> entry : parentsByEpisodeKey.entrySet()) {
            episodes.add(buildEpisode(entry.getKey(), entry.getValue(), membersByParent));
        }
        episodes.addAll(buildOrphanEpisodes(parents, membersByParent));

        episodes.sort(Comparator.comparing(Episode::occurredAt));
        log.debug("grouped {} rows into {} episodes", usable.size(), episodes.size());
        return List.copyOf(episodes);
    }

    /**
     * Keys on {@code item_key} plus the local day.
     *
     * <p>Never on {@code local_id}: that is only unique within one parse run, so an
     * {@code EPHEMERAL} identity would produce a fresh key on every export and re-post the
     * same activity forever. {@code item_key} is the producer's own deduplication key and is
     * stable by construction.
     */
    private String episodeKeyFor(ExportRecord record) {
        LocalDate day = LocalDate.ofInstant(record.bestEffortOccurredAt(), dayBoundaryZone);
        return record.kind().name().toLowerCase(java.util.Locale.ROOT) + ":" + record.itemKey() + ":" + day;
    }

    private Episode buildEpisode(
            String episodeKey,
            List<ExportRecord> parentRows,
            Map<String, List<ExportRecord>> membersByParent) {

        ExportRecord anchor = parentRows.getFirst();

        List<ExportRecord> members = parentRows.stream()
                .flatMap(parent -> membersByParent
                        .getOrDefault(parent.localId(), List.of())
                        .stream())
                .toList();

        // Members are claimed as they are consumed so the orphan pass below only sees
        // the ones that genuinely have no parent in this batch.
        parentRows.forEach(parent -> membersByParent.remove(parent.localId()));

        List<ExportRecord> all = new ArrayList<>(parentRows);
        all.addAll(members);

        return new Episode(
                episodeKey,
                nameFor(anchor),
                renderBody(all),
                anchor.kind(),
                anchor.bestEffortOccurredAt(),
                all.stream().map(ExportRecord::frameId).distinct().sorted().toList());
    }

    private List<Episode> buildOrphanEpisodes(
            List<ExportRecord> parents, Map<String, List<ExportRecord>> membersByParent) {

        if (membersByParent.isEmpty()) {
            return List.of();
        }

        List<ExportRecord> orphans = membersByParent.values().stream().flatMap(List::stream).toList();
        if (!parents.isEmpty()) {
            log.debug("{} member rows had no parent in this batch, grouping by app and day", orphans.size());
        }

        return orphans.stream()
                .collect(Collectors.groupingBy(
                        record -> ORPHAN_PARENT_KEY_PREFIX
                                + ":" + record.appName()
                                + ":" + LocalDate.ofInstant(record.bestEffortOccurredAt(), dayBoundaryZone),
                        LinkedHashMap::new,
                        Collectors.toList()))
                .entrySet()
                .stream()
                .map(entry -> {
                    List<ExportRecord> rows = entry.getValue();
                    ExportRecord anchor = rows.getFirst();
                    return new Episode(
                            entry.getKey(),
                            anchor.appName() + " activity",
                            renderBody(rows),
                            SemanticKind.CONVERSATION,
                            anchor.bestEffortOccurredAt(),
                            rows.stream().map(ExportRecord::frameId).distinct().sorted().toList());
                })
                .toList();
    }

    private String nameFor(ExportRecord anchor) {
        if (anchor.title() != null && !anchor.title().isBlank()) {
            return anchor.title().strip();
        }
        if (!anchor.windowName().isBlank()) {
            return anchor.windowName().strip();
        }
        return anchor.appName();
    }

    /**
     * Renders rows into the text Graphiti extracts from.
     *
     * <p>Truncates at a configured ceiling rather than letting one pathological episode —
     * a chat log scrolled through for an hour — blow past the model's context and fail the
     * whole post. Truncation is marked so a short episode body is distinguishable from a
     * clipped one when a graph result looks thin.
     */
    private String renderBody(List<ExportRecord> rows) {
        StringBuilder builder = new StringBuilder();
        int limit = properties.getMaxEpisodeBodyChars();

        for (ExportRecord row : rows) {
            String line = renderRow(row);
            if (line.isBlank()) {
                continue;
            }
            if (builder.length() + line.length() > limit) {
                builder.append("\n[truncated at ").append(limit).append(" characters]");
                break;
            }
            builder.append(line).append('\n');
        }
        return builder.toString().strip();
    }

    private String renderRow(ExportRecord row) {
        StringBuilder line = new StringBuilder();
        if (row.actorToken() != null && !row.actorToken().isBlank()) {
            line.append(row.actorToken()).append(": ");
        }
        if (row.title() != null && !row.title().isBlank()) {
            line.append(row.title().strip());
            if (row.body() != null && !row.body().isBlank()) {
                line.append(" — ");
            }
        }
        if (row.body() != null && !row.body().isBlank()) {
            line.append(row.body().strip());
        }
        return line.toString();
    }

    /** Exposed for the ingest service's provenance logging. */
    public Instant dayBoundaryFor(Instant instant) {
        return LocalDate.ofInstant(instant, dayBoundaryZone).atStartOfDay(dayBoundaryZone).toInstant();
    }
}
