package com.digitalself.memory;

import java.util.UUID;

public class MemoryNotFoundException extends RuntimeException {
    public MemoryNotFoundException(UUID id) {
        super("No memory found with id " + id);
    }
}
