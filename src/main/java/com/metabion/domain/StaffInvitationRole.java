package com.metabion.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

@Entity
@Table(name = "staff_invitation_roles")
public class StaffInvitationRole {

    @EmbeddedId
    private StaffInvitationRoleKey id;

    @MapsId("staffInvitationId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_invitation_id", nullable = false)
    private StaffInvitation staffInvitation;

    public StaffInvitationRole() {
    }

    public StaffInvitationRole(StaffInvitation staffInvitation, RoleName role) {
        if (role == null || !role.isClinicalStaff()) {
            throw new IllegalArgumentException("Staff invitation role must be a clinical staff role");
        }
        this.staffInvitation = staffInvitation;
        this.id = new StaffInvitationRoleKey(staffInvitation.getId(), role.name());
    }

    public StaffInvitationRoleKey getId() {
        return id;
    }

    public void setId(StaffInvitationRoleKey id) {
        this.id = id;
    }

    public StaffInvitation getStaffInvitation() {
        return staffInvitation;
    }

    public void setStaffInvitation(StaffInvitation staffInvitation) {
        this.staffInvitation = staffInvitation;
    }

    public RoleName getRole() {
        return RoleName.from(id.getRole());
    }
}
