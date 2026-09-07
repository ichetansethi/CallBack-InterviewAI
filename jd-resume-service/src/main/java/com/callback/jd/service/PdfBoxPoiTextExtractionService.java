package com.callback.jd.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@Service
public class PdfBoxPoiTextExtractionService implements TextExtractionService {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxPoiTextExtractionService.class);

    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Override
    public String extractText(MultipartFile file) {
        String contentType = file.getContentType();
        try {
            if (PDF_CONTENT_TYPE.equals(contentType)) {
                return extractPdf(file);
            }
            if (DOCX_CONTENT_TYPE.equals(contentType)) {
                return extractDocx(file);
            }
            log.debug("No text extractor for content type {}; skipping extraction", contentType);
            return null;
        } catch (Exception e) {
            // Extraction is best-effort: a corrupt/unsupported file must not block the upload.
            log.warn("Text extraction failed for '{}' ({}): {}", file.getOriginalFilename(), contentType, e.getMessage());
            return null;
        }
    }

    private String extractPdf(MultipartFile file) throws IOException {
        try (InputStream is = file.getInputStream();
             PDDocument document = PDDocument.load(is)) {
            return new PDFTextStripper().getText(document).trim();
        }
    }

    private String extractDocx(MultipartFile file) throws IOException {
        try (InputStream is = file.getInputStream();
             XWPFDocument document = new XWPFDocument(is);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText().trim();
        }
    }
}
