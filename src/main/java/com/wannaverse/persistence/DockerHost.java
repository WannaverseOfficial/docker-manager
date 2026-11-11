package com.wannaverse.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "docker_hosts_t")
public class DockerHost {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String dockerHostUrl;

    /** Display name, e.g. "Home server". Optional; the UI falls back to the URL. */
    private String name;

    // TLS for tcp:// hosts (Docker's --tlsverify): CA, client certificate and key. Encrypted.
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String tlsCaPem;

    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String tlsCertPem;

    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String tlsKeyPem;

    /** Private key for ssh:// hosts. Encrypted. */
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String sshPrivateKey;

    @JsonProperty("tls")
    public boolean hasTls() {
        return tlsCaPem != null && tlsCertPem != null && tlsKeyPem != null;
    }

    @JsonProperty("hasSshKey")
    public boolean hasSshKey() {
        return sshPrivateKey != null;
    }
}
