package za.co.albertusvdw.graphiti.ingester.core.data.episode.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import za.co.albertusvdw.graphiti.ingester.application.config.IngestProperties;
import za.co.albertusvdw.graphiti.ingester.core.data.episode.Episode;
import za.co.albertusvdw.graphiti.ingester.core.data.export.ExportRecord;
import za.co.albertusvdw.graphiti.ingester.core.data.export.IdentityQuality;
import za.co.albertusvdw.graphiti.ingester.core.data.export.SemanticKind;

class DefaultEpisodeGrouperTest {

    private DefaultEpisodeGrouper grouper;

    @BeforeEach
    void setUp() {
        IngestProperties properties = new IngestProperties();
        properties.setDayBoundaryZone("Africa/Johannesburg");
        properties.setMaxEpisodeBodyChars(200);
        grouper = new DefaultEpisodeGrouper(properties);
    }

    @Test
    @DisplayName("messages collapse into their parent conversation rather than becoming episodes")
    void groupsMessagesUnderTheirParent() {
        ExportRecord conversation = record(
                1, SemanticKind.CONVERSATION, "conv-key", "conv-local", null, "Standup", null);
        ExportRecord first = record(
                2, SemanticKind.MESSAGE, "msg-1", "m1", "conv-local", null, "blocked on CM-12");
        ExportRecord second = record(
                3, SemanticKind.MESSAGE, "msg-2", "m2", "conv-local", null, "I'll take a look");

        List<Episode> episodes = grouper.group(List.of(conversation, first, second));

        assertThat(episodes).hasSize(1);
        assertThat(episodes.getFirst().name()).isEqualTo("Standup");
        assertThat(episodes.getFirst().body()).contains("blocked on CM-12").contains("I'll take a look");
        assertThat(episodes.getFirst().frameIds()).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("the same item seen across many frames is one episode, not one per frame")
    void mergesRepeatedItemKeysOnTheSameDay() {
        ExportRecord seenOnce = record(
                10, SemanticKind.DOCUMENT, "doc-key", "d1", null, "Design doc", "first paragraph");
        ExportRecord seenAgain = record(
                11, SemanticKind.DOCUMENT, "doc-key", "d2", null, "Design doc", "second paragraph");

        List<Episode> episodes = grouper.group(List.of(seenOnce, seenAgain));

        assertThat(episodes).hasSize(1);
        assertThat(episodes.getFirst().frameIds()).containsExactly(10L, 11L);
    }

    @Test
    @DisplayName("episode keys are stable across runs, so a re-read produces the same keys")
    void producesStableKeysAcrossRuns() {
        List<ExportRecord> records =
                List.of(record(1, SemanticKind.DOCUMENT, "doc-key", "d1", null, "Notes", "body text"));

        assertThat(grouper.group(records).getFirst().episodeKey())
                .isEqualTo(grouper.group(records).getFirst().episodeKey());
    }

    @Test
    @DisplayName("messages whose parent is missing are kept, not silently dropped")
    void keepsOrphanedMessages() {
        ExportRecord orphan =
                record(5, SemanticKind.MESSAGE, "msg-9", "m9", "missing-parent", null, "an orphaned line");

        List<Episode> episodes = grouper.group(List.of(orphan));

        assertThat(episodes).hasSize(1);
        assertThat(episodes.getFirst().body()).contains("an orphaned line");
        assertThat(episodes.getFirst().episodeKey()).startsWith("orphan:");
    }

    @Test
    @DisplayName("rows with no title and no body never reach the graph")
    void dropsEmptyRows() {
        ExportRecord empty = record(7, SemanticKind.DOCUMENT, "empty-key", "e1", null, null, null);

        assertThat(grouper.group(List.of(empty))).isEmpty();
    }

    @Test
    @DisplayName("the same on-screen element re-captured across frames appears once")
    void deduplicatesRepeatedLines() {
        // Real shape: a terminal window captured on 32 consecutive frames, identical every time.
        List<ExportRecord> rows = new java.util.ArrayList<>();
        for (int frame = 1; frame <= 32; frame++) {
            rows.add(record(frame, SemanticKind.DOCUMENT, "term", "d" + frame, null, "Command Prompt", null));
        }

        List<Episode> episodes = grouper.group(rows);

        assertThat(episodes).hasSize(1);
        assertThat(episodes.getFirst().body()).isEqualTo("Command Prompt");
        assertThat(episodes.getFirst().frameIds()).hasSize(32);
    }

    @Test
    @DisplayName("episodes are named after the window, not the accessibility role")
    void namesFromTheWindowNotTheRole() {
        ExportRecord row = recordWithWindow(
                1, SemanticKind.DOCUMENT, "mail", "d1", "text", "an email body",
                "chrome.exe", "Inbox (108) - Gmail - Google Chrome");

        List<Episode> episodes = grouper.group(List.of(row));

        // Not "text", and the trailing browser name is dropped as redundant with the app.
        assertThat(episodes.getFirst().name()).isEqualTo("Inbox (108) - Gmail");
    }

    @Test
    @DisplayName("a genuine title is kept when it is not a role name")
    void keepsRealTitles() {
        ExportRecord row = recordWithWindow(
                1, SemanticKind.DOCUMENT, "doc", "d1", "Q3 architecture review", "body text",
                "WINWORD.EXE", "");

        assertThat(grouper.group(List.of(row)).getFirst().name()).isEqualTo("Q3 architecture review");
    }

    @Test
    @DisplayName("a role name is not prefixed onto real content in the body")
    void dropsRoleNamesFromRenderedLines() {
        ExportRecord row = recordWithWindow(
                1, SemanticKind.DOCUMENT, "doc", "d1", "text", "The Agentic AI Bible",
                "chrome.exe", "Reader - Google Chrome");

        assertThat(grouper.group(List.of(row)).getFirst().body()).isEqualTo("The Agentic AI Bible");
    }

    @Test
    @DisplayName("layout whitespace from the accessibility tree is collapsed")
    void collapsesWhitespaceRuns() {
        // Verbatim shape of a real captured email that reached the graph with the run intact.
        ExportRecord row = record(
                1, SemanticKind.DOCUMENT, "mail", "d1", null, null,
                "Hi there, \nI am keen to                              buy this one: link");

        String body = grouper.group(List.of(row)).getFirst().body();

        assertThat(body).isEqualTo("Hi there,\nI am keen to buy this one: link");
    }

    @Test
    @DisplayName("the same text padded differently deduplicates to one line")
    void deduplicatesAcrossDifferentPadding() {
        // Without normalisation these are distinct strings and both survive, so the
        // repetition the deduplication exists to remove comes straight back.
        List<ExportRecord> rows = List.of(
                record(1, SemanticKind.DOCUMENT, "doc", "d1", null, null, "total   due   now"),
                record(2, SemanticKind.DOCUMENT, "doc", "d2", null, null, "total due     now"));

        assertThat(grouper.group(rows).getFirst().body()).isEqualTo("total due now");
    }

    @Test
    @DisplayName("paragraph structure survives — only runs within a line collapse")
    void keepsLineStructure() {
        ExportRecord row = record(
                1, SemanticKind.DOCUMENT, "doc", "d1", null, null, "first  line\n\n\nsecond   line");

        assertThat(grouper.group(List.of(row)).getFirst().body()).isEqualTo("first line\nsecond line");
    }

    @Test
    @DisplayName("an oversized episode is truncated and says so")
    void truncatesOversizedBodies() {
        String longBody = "x".repeat(500);
        ExportRecord huge = record(8, SemanticKind.DOCUMENT, "big-key", "b1", null, "Big", longBody);

        List<Episode> episodes = grouper.group(List.of(huge));

        assertThat(episodes.getFirst().body()).contains("[truncated at 200 characters]");
    }

    private ExportRecord recordWithWindow(
            long frameId,
            SemanticKind kind,
            String itemKey,
            String localId,
            String title,
            String body,
            String appName,
            String windowName) {
        return new ExportRecord(
                ExportRecord.SUPPORTED_SCHEMA_VERSION,
                frameId,
                Instant.parse("2026-08-07T09:00:00Z"),
                "2026-08-07T09:00:00Z",
                "second",
                kind,
                itemKey,
                localId,
                null,
                IdentityQuality.STABLE,
                title,
                body,
                null,
                null,
                appName,
                windowName,
                null,
                Map.of());
    }

    private ExportRecord record(
            long frameId,
            SemanticKind kind,
            String itemKey,
            String localId,
            String parentLocalId,
            String title,
            String body) {
        return new ExportRecord(
                ExportRecord.SUPPORTED_SCHEMA_VERSION,
                frameId,
                Instant.parse("2026-08-07T09:00:00Z"),
                "2026-08-07T09:00:00Z",
                "second",
                kind,
                itemKey,
                localId,
                parentLocalId,
                IdentityQuality.STABLE,
                title,
                body,
                null,
                null,
                "Teams",
                // Real Windows titles use " - " as the separator; the app suffix is trimmed.
                "Standup - Teams",
                null,
                Map.of());
    }
}
