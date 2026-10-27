package com.finora.identityaccess.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEVELOPMENT-ONLY test adapter. It appends the invitation (including the secret acceptance link) to a local file so a
 * developer can complete the flow without a mail provider. It does NOT send email and is reported as DEV_CAPTURED.
 * It is active only when {@code finora.workspaces.dev-outbox-file} is set explicitly.
 */
public class DevFileInvitationNotifier implements InvitationNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(DevFileInvitationNotifier.class);
    private final Path file;

    public DevFileInvitationNotifier(Path file) {
        this.file = file;
        LOG.warn("DEVELOPMENT invitation outbox enabled ({}): invitation links are written to a local file; no email is sent", file);
    }

    @Override
    public synchronized Outcome deliver(String email, String workspaceName, String role, String acceptLink, Instant expiresAt) {
        try {
            if (!Files.exists(file)) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            }
            String line = "{\"to\":\"" + escape(email) + "\",\"workspace\":\"" + escape(workspaceName) + "\",\"role\":\"" + role
                    + "\",\"link\":\"" + escape(acceptLink) + "\",\"expires_at\":\"" + expiresAt + "\"}\n";
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            return Outcome.DEV_CAPTURED;
        } catch (IOException e) {
            LOG.warn("Development invitation outbox write failed ({})", e.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
