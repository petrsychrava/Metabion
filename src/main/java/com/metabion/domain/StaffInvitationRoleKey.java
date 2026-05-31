package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class StaffInvitationRoleKey implements Serializable {

    @Column(name = "staff_invitation_id")
    private Long staffInvitationId;

    @Column(name = "role", length = 50)
    private String role;

    public StaffInvitationRoleKey() {
    }

    public StaffInvitationRoleKey(Long staffInvitationId, String role) {
        this.staffInvitationId = staffInvitationId;
        this.role = role;
    }

    public Long getStaffInvitationId() {
        return staffInvitationId;
    }

    public void setStaffInvitationId(Long staffInvitationId) {
        this.staffInvitationId = staffInvitationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StaffInvitationRoleKey that)) {
            return false;
        }
        return Objects.equals(staffInvitationId, that.staffInvitationId) && Objects.equals(role, that.role);
    }

    @Override
    public int hashCode() {
        return Objects.hash(staffInvitationId, role);
    }
}
