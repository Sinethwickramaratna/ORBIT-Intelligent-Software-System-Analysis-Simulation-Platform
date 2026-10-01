package com.orbit.backend.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "refresh_token_table")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

    @Id
    @Column(name = "token_id", nullable = false, updatable = false)
    private UUID tokenId;

    /** SHA-256 hex digest of the opaque token handed to the client (a DB leak cannot be replayed). */
    @Column(name = "token", nullable = false, unique = true)
    private String token;

    /** Many refresh tokens belong to one user ({@code user_id} foreign key). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expired_at", nullable = false)
    private Instant expiredAt;

    /** The owner's id, read from the foreign key without loading the {@link User}. */
    public UUID getUserId() {
        return user.getUserId();
    }
}
