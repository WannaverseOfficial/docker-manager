package com.wannaverse.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestEmailRequest {

    @NotBlank(message = "Recipient email is required")
    @Email(message = "Recipient must be a valid email address")
    private String recipientEmail;
}
