package com.wannaverse.dto;

import com.wannaverse.persistence.SmtpConfiguration;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmtpConfigurationRequest {

    @NotBlank(message = "SMTP host is required")
    private String host;

    @Min(value = 1, message = "Port must be between 1 and 65535")
    @Max(value = 65535, message = "Port must be between 1 and 65535")
    private int port;

    private String username;

    private String password;

    @NotBlank(message = "From address is required")
    @Email(message = "From address must be a valid email")
    private String fromAddress;

    private String fromName;

    @NotNull(message = "Security type is required")
    private SmtpConfiguration.SecurityType securityType;

    private boolean enabled;
}
