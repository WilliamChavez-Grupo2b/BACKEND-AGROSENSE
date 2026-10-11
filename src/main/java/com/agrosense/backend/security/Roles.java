package com.agrosense.backend.security;

/**
 * Authorization rules for {@code @PreAuthorize}. Role names are the values stored in {@code users.role},
 * in upper case. A role only decides which operations a user may perform; every operation is still
 * limited to the user's own estates.
 */
public final class Roles {

    /** Any known role: may read data, record readings and register sensors. */
    public static final String ANY = "hasAnyRole('ADMIN', 'FARMER', 'TECHNICIAN')";

    /** Roles that run the estate: may also create estates and crops, irrigate and close alerts. */
    public static final String MANAGER = "hasAnyRole('ADMIN', 'FARMER')";

    private Roles() {
    }
}
