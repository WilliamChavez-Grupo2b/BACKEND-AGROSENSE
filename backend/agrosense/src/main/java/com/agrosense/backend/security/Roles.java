package com.agrosense.backend.security;

public final class Roles {

    public static final String ANY = "hasAnyRole('ADMIN', 'FARMER', 'TECHNICIAN')";

    public static final String MANAGER = "hasAnyRole('ADMIN', 'FARMER')";

    private Roles() {
    }
}
