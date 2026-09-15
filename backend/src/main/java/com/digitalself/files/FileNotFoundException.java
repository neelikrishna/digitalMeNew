package com.digitalself.files;

import java.util.UUID;

public class FileNotFoundException extends RuntimeException {
    public FileNotFoundException(UUID id) {
        super("No file found with id " + id);
    }
}
