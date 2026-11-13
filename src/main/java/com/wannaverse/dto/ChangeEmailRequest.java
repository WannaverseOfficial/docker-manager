package com.wannaverse.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class ChangeEmailRequest {
    /** The new address. Empty removes it. */
    @Size(max = 254, message = "Email is too long")
    private String email;

    @NotBlank(message = "Enter your password")
    private String currentPassword;
}
