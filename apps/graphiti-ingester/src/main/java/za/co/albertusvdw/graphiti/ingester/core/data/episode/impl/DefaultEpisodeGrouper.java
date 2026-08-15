package za.co.albertusvdw.graphiti.ingester.core.data.episode.impl;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import za.co.albertusvdw.graphiti.ingester.application.config.IngestProperties;
import za.co.albertusvdw.graphiti.ingester.core.data.episode.ActivityDomain;
import za.co.albertusvdw.graphiti.ingester.core.data.episode.ActivityDomainClassifier;
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

    /**
     * Accessibility role names that arrive in the {@code title} field.
     *
     * <p>Non-message rows carry the a11y element's role, not a document title. Measured over a
     * real day's export, every distinct non-message title was one of these: {@code text} (31),
     * {@code button} (3), {@code edit} (2), {@code Default} (2). Naming episodes from that
     * field produced episodes called "text" and "button", which say nothing about what
     * happened and give the extraction model nothing to anchor on.
     */
    private static final Set<String> UI_ROLE_TITLES = Set.of(
            "text", "edit", "button", "default", "list", "listitem", "document", "pane", "group",
            "combobox", "checkbox", "radiobutton", "link", "image", "menu", "menuitem", "menubar",
            "toolbar", "tab", "tabitem", "tree", "treeitem", "table", "cell", "row", "heading",
            "static", "statictext", "generic", "none", "window", "dialog", "scrollbar", "separator");

    private final IngestProperties properties;
    private final ActivityDomainClassifier domainClassifier;
    private final ZoneId dayBoundaryZone;

    public DefaultEpisodeGrouper(IngestProperties properties, ActivityDomainClassifier domainClassifier) {
        this.properties = properties;
        this.domainClassifier = domainClassifier;
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
                domainClassifier.classify(anchor.appName(), anchor.windowName(), anchor.domain()),
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
                            domainClassifier.classify(anchor.appName(), anchor.windowName(), anchor.domain()),
                            anchor.bestEffortOccurredAt(),
                            rows.stream().map(ExportRecord::frameId).distinct().sorted().toList());
                })
                .toList();
    }

    /**
     * Names the episode after the window it happened in, not the a11y role of some element.
     *
     * <p>Window titles are what actually describe the activity — "Inbox (108) - Gmail - Google
     * Chrome", "Email - Outlook", "[CHANNEL] | Hydration Nation 4.0 - Discord". The title field
     * is preferred only when it is a real title rather than one of {@link #UI_ROLE_TITLES}.
     */
    private String nameFor(ExportRecord anchor) {
        String window = cleanWindowName(anchor);
        if (!window.isBlank()) {
            return window;
        }
        String title = anchor.title() == null ? "" : anchor.title().strip();
        if (!title.isBlank() && !UI_ROLE_TITLES.contains(title.toLowerCase(java.util.Locale.ROOT))) {
            return title;
        }
        return anchor.appName();
    }

    /**
     * Trims the trailing application segment from a window title.
     *
     * <p>"Inbox (108) - ... - Gmail - Google Chrome" becomes "Inbox (108) - ... - Gmail": the
     * browser's name is already implied by the app and repeating it in every episode name makes
     * a list of them harder to scan, not easier.
     */
    private String cleanWindowName(ExportRecord anchor) {
        String window = anchor.windowName() == null ? "" : anchor.windowName().strip();
        if (window.isBlank()) {
            return "";
        }
        int lastSeparator = window.lastIndexOf(" - ");
        if (lastSeparator > 0) {
            String tail = window.substring(lastSeparator + 3).strip();
            String app = anchor.appName().replaceFirst("(?i)\\.exe$", "").strip();
            // "Google Chrome" vs "chrome.exe", "Discord" vs "Discord.exe" — compare loosely.
            String normalisedTail = tail.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
            String normalisedApp = app.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
            if (normalisedTail.endsWith(normalisedApp) || normalisedApp.endsWith(normalisedTail)) {
                return window.substring(0, lastSeparator).strip();
            }
        }
        return window;
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

        // Deduplicated across the whole episode, not just adjacent lines.
        //
        // The same on-screen element is re-captured on every frame, so a single window produces
        // the identical line dozens of times: measured 31 rows collapsing to 2 distinct lines,
        // and a terminal episode that was the words "Command Prompt" repeated 32 times. Sending
        // that to the extraction model costs tokens to describe nothing, and an episode padded
        // with repetition also sails past the min-length filter that is supposed to drop
        // contentless activity.
        Set<String> seen = new LinkedHashSet<>();

        for (ExportRecord row : rows) {
            String line = renderRow(row);
            if (line.isBlank() || !seen.add(line)) {
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

        // A role name prefixed onto real content ("text — The Agentic AI Bible...") is noise
        // that the model has to read past on every line. Keep genuine titles, drop roles.
        String title = normalizeWhitespace(row.title());
        boolean titleIsUseful =
                !title.isBlank() && !UI_ROLE_TITLES.contains(title.toLowerCase(java.util.Locale.ROOT));
        String body = normalizeWhitespace(row.body());

        if (titleIsUseful) {
            line.append(title);
            if (!body.isBlank()) {
                line.append(" — ");
            }
        }
        if (!body.isBlank()) {
            line.append(body);
        }
        return line.toString();
    }

    /**
     * Collapses the whitespace runs the accessibility tree leaves in captured text.
     *
     * <p>Screen layout arrives as literal spacing, so a real captured email reached the graph as
     * {@code "I am keen to<47 spaces>buy this one"}. That is not cosmetic: those runs are
     * tokens the extraction model pays for and reads through, and two captures of the same text
     * with different padding look like different lines to the deduplication above, so the
     * repetition survives.
     *
     * <p>Line structure is preserved — paragraph breaks in an email carry meaning — while runs
     * of spaces and tabs within a line collapse to one and blank lines are dropped.
     */
    private String normalizeWhitespace(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.lines()
                .map(line -> line.replaceAll("[\\p{Zs}\\t\\x0B\\f\\r]+", " ").strip())
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    /** Exposed for the ingest service's provenance logging. */
    public Instant dayBoundaryFor(Instant instant) {
        return LocalDate.ofInstant(instant, dayBoundaryZone).atStartOfDay(dayBoundaryZone).toInstant();
    }
}
