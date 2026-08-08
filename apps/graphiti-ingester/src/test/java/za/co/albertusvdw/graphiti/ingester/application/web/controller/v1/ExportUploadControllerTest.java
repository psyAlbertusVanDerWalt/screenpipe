package za.co.albertusvdw.graphiti.ingester.application.web.controller.v1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@TestPropertySource(properties = {
    "ingest.upload-token=correct-horse-battery-staple",
    "ingest.export-dir=target/test-uploads",
})
class ExportUploadControllerTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("a valid token stores the file")
    void acceptsAValidToken() throws Exception {
        mockMvc().perform(post("/api/v1/export/uploads/{name}", "2026-08-07.jsonl")
                        .header("Authorization", "Bearer correct-horse-battery-staple")
                        .content("{\"export_schema_version\":1}\n"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("2026-08-07.jsonl"));
    }

    @Test
    @DisplayName("no Authorization header is rejected")
    void rejectsMissingToken() throws Exception {
        mockMvc().perform(post("/api/v1/export/uploads/{name}", "2026-08-07.jsonl").content("x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a wrong token is rejected")
    void rejectsWrongToken() throws Exception {
        mockMvc().perform(post("/api/v1/export/uploads/{name}", "2026-08-07.jsonl")
                        .header("Authorization", "Bearer wrong")
                        .content("x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token that is a prefix of the real one is rejected")
    void rejectsPrefixToken() throws Exception {
        mockMvc().perform(post("/api/v1/export/uploads/{name}", "2026-08-07.jsonl")
                        .header("Authorization", "Bearer correct-horse")
                        .content("x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("authentication is checked before the file name, so probing costs nothing")
    void authenticatesBeforeValidating() throws Exception {
        // A traversal attempt without a token must come back 401, not 400 — a 400 would
        // confirm to an unauthenticated caller that the path was even considered.
        mockMvc().perform(post("/api/v1/export/uploads/{name}", "evil.jsonl").content("x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an authenticated but malformed name is a 400")
    void rejectsBadNameWhenAuthenticated() throws Exception {
        mockMvc().perform(post("/api/v1/export/uploads/{name}", "evil.jsonl")
                        .header("Authorization", "Bearer correct-horse-battery-staple")
                        .content("x"))
                .andExpect(status().isBadRequest());
    }
}
