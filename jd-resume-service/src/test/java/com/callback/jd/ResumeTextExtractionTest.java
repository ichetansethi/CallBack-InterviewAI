package com.callback.jd;

import com.callback.jd.controller.ResumeController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the text-extraction feature end to end — real PDF bytes in, sensible text out —
 * in isolation from compatibility-service. Drives the actual controller/service/repository/
 * storage/extraction stack (H2 in place of Postgres); the JWT filter chain is bypassed via
 * standalone MockMvc since ownership, not authentication, is what this endpoint adds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ResumeTextExtractionTest {

    @Autowired
    private ResumeController resumeController;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(resumeController).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownerCanReadExtractedTextFromRealPdf() throws Exception {
        authenticateAs("owner@example.com");

        String id = uploadSamplePdf();

        mockMvc.perform(get("/resumes/{id}/text", id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Jane Doe")))
                .andExpect(content().string(containsString("Senior Software Engineer")))
                .andExpect(content().string(containsString("Spring Boot")))
                .andExpect(content().string(containsString("Java")));
    }

    @Test
    void ownerCanReadExtractedTextFromRealDocx() throws Exception {
        authenticateAs("owner@example.com");

        byte[] docxBytes = buildSampleDocx(List.of(
                "John Smith",
                "Backend Engineer",
                "Skills: Kafka, PostgreSQL, Kubernetes"
        ));
        MockMultipartFile file = new MockMultipartFile(
                "file", "resume.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxBytes);

        String responseBody = mockMvc.perform(multipart("/resumes").file(file))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(responseBody).get("id").asText();

        mockMvc.perform(get("/resumes/{id}/text", id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("John Smith")))
                .andExpect(content().string(containsString("Backend Engineer")))
                .andExpect(content().string(containsString("Kafka")));
    }

    @Test
    void nonOwnerGetsNotFoundForText() throws Exception {
        authenticateAs("owner@example.com");
        String id = uploadSamplePdf();

        authenticateAs("someone-else@example.com");
        mockMvc.perform(get("/resumes/{id}/text", id))
                .andExpect(status().isNotFound());
    }

    private String uploadSamplePdf() throws Exception {
        byte[] pdfBytes;
        try (InputStream in = new ClassPathResource("fixtures/sample-resume.pdf").getInputStream()) {
            pdfBytes = in.readAllBytes();
        }
        MockMultipartFile file = new MockMultipartFile("file", "sample-resume.pdf", "application/pdf", pdfBytes);

        String responseBody = mockMvc.perform(multipart("/resumes").file(file))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(responseBody);
        return json.get("id").asText();
    }

    private byte[] buildSampleDocx(List<String> lines) throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            for (String line : lines) {
                XWPFParagraph paragraph = document.createParagraph();
                XWPFRun run = paragraph.createRun();
                run.setText(line);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList()));
    }
}
