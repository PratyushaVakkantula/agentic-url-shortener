package com.agentic.platform.security;

/**
 * Application roles. ADMIN inherits every other role through the role hierarchy
 * configured in {@link SecurityConfig}.
 */
public enum Role {
    /** May start workflow runs and submit requirements. */
    REQUESTER,
    /** May approve or reject human checkpoints (never on runs they started). */
    APPROVER,
    /** Operational control: deactivate links, safe-stop runs, view metrics. */
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
