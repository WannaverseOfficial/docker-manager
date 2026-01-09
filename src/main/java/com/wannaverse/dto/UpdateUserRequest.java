package com.wannaverse.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class UpdateUserRequest {
    @Size(min = 3, max = 100, message = "Username must be between 3 and 100 characters")
    private String username;

    @Email(message = "Invalid email format")
    private String email;

    private Boolean admin;
    private Boolean enabled;
}
