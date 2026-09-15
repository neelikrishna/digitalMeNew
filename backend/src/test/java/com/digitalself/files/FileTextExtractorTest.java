package com.digitalself.files;

import com.digitalself.config.FileExtractionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercised against documents that are actually assembled, not stubs: the whole
 * value of this class is whether a real PDF yields its text, and a mocked parser
 * would prove nothing about that.
 *
 * <p>The PDFs are built here byte by byte rather than with PDFBox, even though
 * Tika drags PDFBox in anyway. Its API changed between 2.x and 3.x, and a test
 * that stops compiling when a transitive dependency moves is a test that will be
 * deleted rather than fixed.
 */
class FileTextExtractorTest {

    private FileExtractionProperties properties;
    private FileTextExtractor extractor;

    @BeforeEach
    void setUp() {
        properties = new FileExtractionProperties();
        extractor = new FileTextExtractor(properties);
    }

    @Test
    void extractsTextFromARealPdf() {
        byte[] pdf = pdf("BT\n/F1 12 Tf\n72 700 Td\n(The mortgage offer expires on 14 March.) Tj\nET");

        FileTextExtractor.Result result = extractor.extract(pdf, "offer.pdf");

        assertTrue(result.text().contains("mortgage offer expires"),
                "a PDF's text layer must reach search; got: " + result.text());
        assertFalse(result.truncated());
        assertFalse(result.isEmpty());
    }

    @Test
    void extractsPlainText() {
        byte[] text = "Line one.\n\n\n\nLine two.   \n".getBytes(StandardCharsets.UTF_8);

        FileTextExtractor.Result result = extractor.extract(text, "notes.txt");

        assertEquals("Line one.\n\nLine two.", result.text(),
                "runs of blank lines and trailing spaces should collapse, so chunk budget holds words");
    }

    /**
     * A scanned document is images of pages. Tika parses it happily and finds no
     * text, which is a fact about the document rather than a failure — and the
     * distinction is what stops the backfill retrying it forever.
     */
    @Test
    void reportsEmptyForAPdfWithNoTextLayer() {
        FileTextExtractor.Result result = extractor.extract(pdf(""), "scan.pdf");

        assertTrue(result.isEmpty(), "a PDF with no text layer must come back empty, not fail");
    }

    @Test
    void truncatesRatherThanFailingWhenTheWriteLimitIsReached() {
        properties.setWriteLimitChars(50);
        byte[] text = "word ".repeat(200).getBytes(StandardCharsets.UTF_8);

        FileTextExtractor.Result result = extractor.extract(text, "long.txt");

        assertTrue(result.truncated(), "hitting the limit is a boundary, not an error");
        assertFalse(result.isEmpty(), "the text read before the limit must be kept");
        assertTrue(result.text().length() <= 60, "text past the limit must be dropped");
    }

    @Test
    void skipsMediaThatHasNoTextLayerToFind() {
        assertFalse(extractor.supports("image/jpeg"), "photos are the EXIF pipeline's job");
        assertFalse(extractor.supports("audio/mpeg"), "audio is the transcription pipeline's job");
        assertFalse(extractor.supports("video/mp4"));
        assertFalse(extractor.supports(null));

        assertTrue(extractor.supports("application/pdf"));
        assertTrue(extractor.supports("text/plain"));
        assertTrue(extractor.supports(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    /**
     * Assembles a single-page PDF around the given content stream, computing the
     * cross-reference offsets properly so the file is valid rather than merely
     * recoverable. Latin-1 throughout, so one character is one byte and the
     * offsets are simply string lengths.
     */
    private static byte[] pdf(String contentStream) {
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
                        + "/Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + contentStream.length() + " >>\nstream\n" + contentStream + "\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        int[] offsets = new int[objects.size()];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i] = pdf.length();
            pdf.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
        }

        int xrefOffset = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n");
        pdf.append("0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format("%010d 00000 n %n", offset).replace(System.lineSeparator(), "\n"));
        }
        pdf.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\n")
                .append("startxref\n").append(xrefOffset).append("\n%%EOF");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(pdf.toString().getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }
}
