package com.wannaverse.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A private Docker registry Docker Manager runs on one of its hosts. */
@Entity
@Table(name = "hosted_registries_t")
@Getter
@Setter
@NoArgsConstructor
public class HostedRegistry {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String dockerHostId;

    @Column(nullable = false)
    private String containerName;

    @Column(nullable = false)
    private String volumeName;

    /** LOCAL: only this server, on 127.0.0.1:port. PUBLIC: HTTPS on a hostname through ingress. */
    @Column(nullable = false, length = 10)
    private String mode;

    private String hostname;

    private Integer localPort;

    private String ingressRouteId;

    /** The registry's users, one bcrypt htpasswd line each (the registry reads this file). */
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String htpasswd;

    /** Password of the account Docker Manager itself uses (pulls, listing). Encrypted. */
    @JsonIgnore private String robotPassword;

    /** The entry under Registries that lets this server pull from it. */
    private String registryEntryId;

    private long createdAt;

    @Column(length = 1000)
    private String lastError;
}
