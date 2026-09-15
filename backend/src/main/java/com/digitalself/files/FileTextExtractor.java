package com.digitalself.files;

import com.digitalself.config.FileExtractionProperties;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.Writer;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Pulls plain text out of a document using Apache Tika.
 *
 * <p>Deliberately free of persistence, encryption and Spring transaction
 * concerns: it takes bytes and returns text, which makes it directly testable
 * against real fixture documents. Everything about *where* the text goes lives
 * in {@link FileIngestionService}.
 *
 * <p>Two limits are enforced here rather than left to the caller, because both
 * protect the process rather than the data. Parsers are an attack surface even
 * when the files are your own: an OOXML document is a zip archive and a small
 * one can expand enormously, and a malformed PDF can send a parser into a very
 * long loop.
 */
@Component
public class FileTextExtractor {

    /** What this extractor is, recorded on every row it writes. */
    public static final String SOURCE = "tika";

    /**
     * Media that contains no text layer to find. Attempting these would burn a
     * parse and record a misleading "empty" result; photos, audio and video are
     * the Python pipeline's job in a later phase, via EXIF and transcription.
     */
    private static final Set<String> UNSUPPORTED_PREFIXES = Set.of("image/", "audio/", "video/");

    private final FileExtractionProperties properties;

    public FileTextExtractor(FileExtractionProperties properties) {
        this.properties = properties;
    }

    /**
     * True when this type is worth handing to a parser at all. Checked against
     * the sniffed type, so it reflects what the file actually is rather than
     * what it was named.
     */
    public boolean supports(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return false;
        }
        String type = mimeType.toLowerCase();
        return UNSUPPORTED_PREFIXES.stream().noneMatch(type::startsWith);
    }

    /**
     * True for images, which have no text worth chasing but do carry EXIF.
     *
     * <p>Separate from {@link #supports(String)} because the two ask different
     * questions — "is there text here?" and "is there capture metadata here?" —
     * and a photo answers no to the first and yes to the second.
     */
    public boolean isImage(String mimeType) {
        return mimeType != null && mimeType.toLowerCase().startsWith("image/");
    }

    /**
     * @return the document's text, possibly empty when there is no text layer
     * @throws FileExtractionException if parsing failed or ran past its timeout
     */
    public Result extract(byte[] content, String filename) {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "file-extract");
            // Daemon so a parser that ignores interruption cannot hold up JVM
            // shutdown. See the abandonment caveat below.
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<Result> parse = executor.submit(() -> parse(content));
            return parse.get(properties.getTimeoutSeconds(), TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            // The parse is abandoned, not stopped: Tika parsers do not reliably
            // respond to interruption, so the thread may run on until it
            // finishes. It is a daemon and its result is discarded, which
            // bounds the damage without pretending we have real cancellation.
            throw new FileExtractionException(
                    "Extraction exceeded " + properties.getTimeoutSeconds() + "s for " + filename, e);

        } catch (ExecutionException e) {
            throw new FileExtractionException(
                    "Could not extract text from " + filename + ": " + rootMessage(e.getCause()), e.getCause());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FileExtractionException("Extraction of " + filename + " was interrupted.", e);

        } finally {
            executor.shutdownNow();
        }
    }

    private Result parse(byte[] content) throws Exception {
        // The limit is enforced by our own Writer rather than Tika's
        // write-limit handler. Tika signals that limit by throwing, and the API
        // for telling that throw apart from a real parse failure moved between
        // Tika 1.x and 2.x — so detecting it correctly would tie this class to a
        // transitive dependency's version. Bounding the buffer ourselves is
        // both simpler and version-proof.
        LimitedWriter writer = new LimitedWriter(properties.getWriteLimitChars());
        BodyContentHandler handler = new BodyContentHandler(writer);
        // Carried out rather than discarded: for an image this is the whole
        // point of the parse, since it is where EXIF arrives.
        Metadata metadata = new Metadata();

        try (ByteArrayInputStream stream = new ByteArrayInputStream(content)) {
            new AutoDetectParser().parse(stream, handler, metadata, new ParseContext());
        }

        return new Result(normalise(writer.text()), writer.isTruncated(), metadata);
    }

    /**
     * Collects parser output up to a hard character ceiling and silently drops
     * the rest.
     *
     * <p>Stored text stays bounded however far the parser runs, which is the
     * protection that matters against a document that expands — an OOXML file is
     * a zip archive. The parse is no longer aborted the moment the limit is hit,
     * so a pathological file keeps consuming CPU; that is what the wall-clock
     * timeout in {@link #extract} is for. Memory is bounded here, time is
     * bounded there.
     */
    private static final class LimitedWriter extends Writer {

        private final StringBuilder out = new StringBuilder();
        private final int limit;
        private boolean truncated;

        LimitedWriter(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(char[] buffer, int offset, int length) {
            int remaining = limit - out.length();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            if (length > remaining) {
                out.append(buffer, offset, remaining);
                truncated = true;
                return;
            }
            out.append(buffer, offset, length);
        }

        @Override
        public void flush() {
            // Nothing buffered beyond the builder.
        }

        @Override
        public void close() {
            // Nothing to release.
        }

        String text() {
            return out.toString();
        }

        boolean isTruncated() {
            return truncated;
        }
    }

    /**
     * Collapses the runs of blank lines and trailing spaces that document
     * parsers emit from page furniture. Purely cosmetic for storage, but it
     * matters for retrieval: the text is embedded and chunked, and whitespace
     * would otherwise consume chunk budget that should hold words.
     */
    private static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\r\n", "\n")
                .replaceAll("[ \t]+\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    private static String rootMessage(Throwable cause) {
        if (cause == null) {
            return "unknown cause";
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /**
     * @param text      extracted text; empty when the document has no text layer
     * @param truncated whether the write limit cut the text short
     * @param metadata  everything the parser learned about the file that was not
     *                  its text — EXIF for a photo, author and title for a
     *                  document. Never null.
     */
    public record Result(String text, boolean truncated, Metadata metadata) {

        /** For callers that only care about the text. */
        public Result(String text, boolean truncated) {
            this(text, truncated, new Metadata());
        }

        public boolean isEmpty() {
            return text == null || text.isBlank();
        }
    }
}
