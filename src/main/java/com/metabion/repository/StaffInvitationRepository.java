package com.metabion.repository;

import com.metabion.domain.StaffInvitation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface StaffInvitationRepository extends JpaRepository<StaffInvitation, Long> {

    @EntityGraph(attributePaths = "roles")
    Optional<StaffInvitation> findByTokenHash(String tokenHash);

    @EntityGraph(attributePaths = "roles")
    Optional<StaffInvitation> findFirstByEmailAndAcceptedAtIsNullAndRevokedAtIsNull(String email);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update StaffInvitation invitation
            set invitation.revokedAt = :revokedAt
            where invitation.email = :email
              and invitation.acceptedAt is null
              and invitation.revokedAt is null
            """)
    int revokeActiveForEmail(@Param("email") String email, @Param("revokedAt") Instant revokedAt);
}
