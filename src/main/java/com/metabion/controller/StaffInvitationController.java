package com.metabion.controller;

import com.metabion.dto.AcceptStaffInvitationRequest;
import com.metabion.dto.CreateStaffInvitationRequest;
import com.metabion.dto.StaffInvitationResponse;
import com.metabion.service.StaffInvitationService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StaffInvitationController {

    private final StaffInvitationService staffInvitationService;

    public StaffInvitationController(StaffInvitationService staffInvitationService) {
        this.staffInvitationService = staffInvitationService;
    }

    @PostMapping("/api/admin/staff-invitations")
    public StaffInvitationResponse create(@Valid @RequestBody CreateStaffInvitationRequest request,
                                          Authentication authentication) {
        staffInvitationService.createInvitation(authentication.getName(), request);
        return new StaffInvitationResponse("ok");
    }

    @PostMapping("/api/staff-invitations/accept")
    public StaffInvitationResponse accept(@Valid @RequestBody AcceptStaffInvitationRequest request) {
        staffInvitationService.acceptInvitation(request);
        return new StaffInvitationResponse("accepted");
    }
}
