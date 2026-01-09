package com.wannaverse.dto;

import com.wannaverse.persistence.SmtpConfiguration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmtpConfigurationResponse {

    private String id;
    private String host;
    private int port;
    private String username;
    private boolean hasPassword;
    private String fromAddress;
    private String fromName;
    private SmtpConfiguration.SecurityType securityType;
    private boolean enabled;
    private long createdAt;
    private Long updatedAt;

    public static SmtpConfigurationResponse fromEntity(SmtpConfiguration config) {
        return SmtpConfigurationResponse.builder()
                .id(config.getId())
                .host(config.getHost())
                .port(config.getPort())
                .username(config.getUsername())
                .hasPassword(
                        config.getEncryptedPassword() != null
                                && !config.getEncryptedPassword().isEmpty())
                .fromAddress(config.getFromAddress())
                .fromName(config.getFromName())
                .securityType(config.getSecurityType())
                .enabled(config.isEnabled())
                .createdAt(config.getCreatedAt().toEpochMilli())
                .updatedAt(
                        config.getUpdatedAt() != null ? config.getUpdatedAt().toEpochMilli() : null)
                .build();
    }
}
