package com.digitalself.files;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tika.metadata.Geographic;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TIFF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns the Tika {@link Metadata} from an image parse into the handful of fields
 * worth querying on.
 *
 * <p>Every field is read defensively and independently. EXIF in the wild is
 * inconsistent — cameras write malformed dates, phones omit GPS, editing tools
 * strip blocks and leave others half-written — and one unparseable tag must
 * never cost the rest. A photo with a readable date and a corrupt coordinate is
 * worth more than no row at all.
 */
@Component
public class ImageMetadataReader {

    private static final Logger log = LoggerFactory.getLogger(ImageMetadataReader.class);

    /**
     * Tags excluded from the stored block. These are the parser describing the
     * parse rather than the camera describing the photo, and keeping them would
     * make the record change whenever Tika is upgraded.
     */
    private static final String PARSER_TAG = "X-TIKA:Parsed-By";

    private final ObjectMapper objectMapper;

    public ImageMetadataReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Reading read(Metadata metadata) {
        return new Reading(
                toJson(metadata),
                instant(metadata),
                integer(metadata, TIFF.IMAGE_WIDTH),
                integer(metadata, TIFF.IMAGE_LENGTH),
                coordinate(metadata, Geographic.LATITUDE, 90),
                coordinate(metadata, Geographic.LONGITUDE, 180));
    }

    /** The whole block, so a later decision about which tag mattered costs nothing. */
    private String toJson(Metadata metadata) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (String name : metadata.names()) {
            if (PARSER_TAG.equals(name)) {
                continue;
            }
            String value = metadata.get(name);
            if (value != null && !value.isBlank()) {
                tags.put(name, value);
            }
        }
        if (tags.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialise image metadata: {}", e.toString());
            return null;
        }
    }

    /**
     * When the shutter fired, not when the file was written. A copied or
     * re-encoded photo keeps the former and loses the latter, and it is the
     * former that says when the memory happened.
     */
    private static Instant instant(Metadata metadata) {
        try {
            Date original = metadata.getDate(TIFF.ORIGINAL_DATE);
            return original == null ? null : original.toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer integer(Metadata metadata, org.apache.tika.metadata.Property property) {
        try {
            return metadata.getInt(property);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Range-checked, because the database check constraint would otherwise turn
     * one malformed tag into a failed ingestion for the whole file.
     */
    private static Double coordinate(Metadata metadata,
                                      org.apache.tika.metadata.Property property,
                                      double bound) {
        String raw = metadata.get(property);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (Double.isNaN(value) || Math.abs(value) > bound) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @param exifJson  the full metadata block, or null when the file carried none
     * @param takenAt   when the photo was taken, if recorded
     * @param latitude  decimal degrees, or null if absent or out of range
     */
    public record Reading(String exifJson, Instant takenAt, Integer width, Integer height,
                          Double latitude, Double longitude) {
    }
}
