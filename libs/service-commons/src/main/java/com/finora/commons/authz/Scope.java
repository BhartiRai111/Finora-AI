package com.finora.commons.authz;

/** Object-level restriction on a granted action. The service that owns the record must apply it. */
public enum Scope {
    /** Every record in the workspace. */
    ALL,
    /** Only records the caller created. */
    OWN,
    /** Only records in projects/teams the caller is assigned to. */
    ASSIGNED,
    /** Only when the Owner/Admin has granted the Viewer access to that area. */
    IF_GRANTED,
    /** A restricted view (masked, summary, metadata, status, member list). */
    LIMITED
}
