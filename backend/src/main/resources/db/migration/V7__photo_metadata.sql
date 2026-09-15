-- Photo EXIF (Phase 4). See docs/media-ingestion.md.
--
-- The media table has existed since V1 but was never mapped or written to. It
-- holds exif_json, taken_at, width and height already; what it lacks is anywhere
-- to put coordinates as numbers.

-- Latitude and longitude as columns, not only inside exif_json. A photo has to
-- be findable by where it was taken, and "where" means range queries — which
-- JSONB extraction can serve but not index usefully at this scale. The full
-- metadata block still goes to exif_json verbatim, so nothing is lost by
-- promoting these two.
ALTER TABLE media ADD COLUMN latitude  DOUBLE PRECISION;
ALTER TABLE media ADD COLUMN longitude DOUBLE PRECISION;

ALTER TABLE media ADD CONSTRAINT media_latitude_range
    CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90);
ALTER TABLE media ADD CONSTRAINT media_longitude_range
    CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180);

-- Distinguishes "metadata was read and there was none" from "never read",
-- the same distinction file_metadata.extraction_status draws for text.
ALTER TABLE media ADD COLUMN extracted_at TIMESTAMPTZ;

-- The point of all this: browsing photos by when they were taken.
CREATE INDEX idx_media_taken_at ON media(taken_at);
