package com.finora.commons.authz;

/** What a role may do with a resource. The first five mirror the PRD legend (C, R, U, A, X). */
public enum Action {
    CREATE, READ, UPDATE, ARCHIVE, EXPORT,
    /** Issue an invoice. */
    ISSUE,
    /** Decide an approval (only with an explicit approval assignment, which is not a role). */
    APPROVE,
    /** Unmask vendor bank details (also needs step-up). */
    UNMASK,
    /** Workspace delete / ownership transfer / data deletion. */
    EXECUTE
}
