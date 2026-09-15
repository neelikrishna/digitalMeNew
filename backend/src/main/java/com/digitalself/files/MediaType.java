package com.digitalself.files;

/**
 * What kind of media a file is, for the {@code media} table.
 *
 * <p>Only {@link #PHOTO} is populated today. Audio and video are recorded by the
 * transcription work in a later phase — the values exist because the V1 check
 * constraint already names them.
 */
public enum MediaType {
    PHOTO,
    AUDIO,
    VIDEO
}
