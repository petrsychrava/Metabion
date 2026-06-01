package com.metabion.exception;

public class StaffInvitationException extends RuntimeException {

    public StaffInvitationException(String message) {
        super(message);
    }

    public static StaffInvitationException invalidOrExpired() {
        return new StaffInvitationException("This invitation link is invalid or expired.");
    }

    public static StaffInvitationException completionConflict() {
        return new StaffInvitationException("This invitation cannot be completed. Contact an administrator.");
    }
}
