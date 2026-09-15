package com.digitalself.crypto;

/**
 * A data-encryption key after being encrypted under the master key. Safe to
 * persist; useless without the master key.
 */
public record WrappedKey(byte[] wrappedKey, byte[] iv, String masterKeyLabel) {
}
