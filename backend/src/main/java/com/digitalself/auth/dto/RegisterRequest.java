package com.digitalself.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 12, message = "password must be at least 12 characters") String password,
        @NotBlank String displayName
) {
}
