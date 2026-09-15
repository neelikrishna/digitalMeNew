package com.digitalself.files;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tika.metadata.Geographic;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TIFF;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EXIF in the wild is inconsistent — cameras write malformed dates, phones omit
 * GPS, editing tools strip some blocks and leave others half-written. These
 * tests are mostly about the malformed cases, because those are what actually
 * arrives.
 */
class ImageMetadataReaderTest {

    private final ImageMetadataReader reader = new ImageMetadataReader(new ObjectMapper());

    @Test
    void readsTheFieldsWorthQueryingOn() {
        Metadata metadata = new Metadata();
        // The Date overload rather than a string, so the test is not also a test
        // of Tika's date parsing.
        metadata.set(TIFF.ORIGINAL_DATE, Date.from(Instant.parse("2019-06-03T14:22:00Z")));
        metadata.set(TIFF.IMAGE_WIDTH, 4032);
        metadata.set(TIFF.IMAGE_LENGTH, 3024);
        metadata.set(Geographic.LATITUDE, "51.5074");
        metadata.set(Geographic.LONGITUDE, "-0.1278");

        ImageMetadataReader.Reading reading = reader.read(metadata);

        assertEquals(Instant.parse("2019-06-03T14:22:00Z"), reading.takenAt());
        assertEquals(4032, reading.width());
        assertEquals(3024, reading.height());
        assertEquals(51.5074, reading.latitude(), 0.00001);
        assertEquals(-0.1278, reading.longitude(), 0.00001);
    }

    /**
     * Most photos that arrive through a messaging app have been stripped. That
     * is not a failure and must not read like one.
     */
    @Test
    void anImageWithNoMetadataReadsAsAllNullsRatherThanFailing() {
        ImageMetadataReader.Reading reading = reader.read(new Metadata());

        assertNull(reading.takenAt());
        assertNull(reading.latitude());
        assertNull(reading.exifJson(), "an empty block is null, not \"{}\"");
    }

    /**
     * The database has range constraints on both coordinates. A single bad tag
     * must not turn into a constraint violation that fails the whole ingestion.
     */
    @Test
    void coordinatesOutsideTheValidRangeAreDroppedNotStored() {
        Metadata metadata = new Metadata();
        metadata.set(Geographic.LATITUDE, "91.0");
        metadata.set(Geographic.LONGITUDE, "-181.5");

        ImageMetadataReader.Reading reading = reader.read(metadata);

        assertNull(reading.latitude(), "latitude beyond 90 degrees cannot be real");
        assertNull(reading.longitude());
    }

    @Test
    void unparseableCoordinatesAreDropped() {
        Metadata metadata = new Metadata();
        metadata.set(Geographic.LATITUDE, "51 deg 30' 26.64\" N");

        assertNull(reader.read(metadata).latitude());
    }

    /**
     * One unreadable tag must not cost the others — a photo with a good date and
     * a corrupt coordinate is worth more than no row at all.
     */
    @Test
    void oneBadFieldDoesNotCostTheRest() {
        Metadata metadata = new Metadata();
        metadata.set(TIFF.ORIGINAL_DATE, Date.from(Instant.parse("2019-06-03T14:22:00Z")));
        metadata.set(Geographic.LATITUDE, "not-a-number");

        ImageMetadataReader.Reading reading = reader.read(metadata);

        assertEquals(Instant.parse("2019-06-03T14:22:00Z"), reading.takenAt());
        assertNull(reading.latitude());
    }

    @Test
    void theFullBlockIsKeptSoALaterChangeOfMindCostsNothing() {
        Metadata metadata = new Metadata();
        metadata.set("Make", "Canon");
        metadata.set("Model", "EOS 5D");

        String json = reader.read(metadata).exifJson();

        assertNotNull(json);
        assertTrue(json.contains("Canon"));
        assertTrue(json.contains("EOS 5D"));
    }

    /**
     * Which parser Tika chose is a fact about Tika, not about the photo. Keeping
     * it would make the stored record change on every dependency upgrade.
     */
    @Test
    void parserProvenanceIsNotStoredAsPhotoMetadata() {
        Metadata metadata = new Metadata();
        metadata.set("X-TIKA:Parsed-By", "org.apache.tika.parser.jpeg.JpegParser");

        assertNull(reader.read(metadata).exifJson(),
                "a block containing only parser provenance is not photo metadata");
    }
}
