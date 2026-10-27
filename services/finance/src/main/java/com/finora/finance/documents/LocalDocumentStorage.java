package com.finora.finance.documents;

import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Local-disk storage for development and single-node use. Files live under one configured directory that must NOT be served by any web
 * server; each is named by random UUIDs (workspace folder / file id), never by anything a user typed. Every path is re-validated against the
 * pattern and the base directory, so a tampered key cannot reach outside it. Files are written with owner-only permissions.
 */
@Component
public class LocalDocumentStorage implements DocumentStorage {

    private static final Pattern KEY = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final Path base;

    public LocalDocumentStorage(@Value("${finora.finance.documents.storage-dir:${java.io.tmpdir}/finora-finance-documents}") String dir) {
        this.base = Path.of(dir).toAbsolutePath().normalize();
    }

    private Path resolve(UUID tenant, String key) {
        if (key == null || !KEY.matcher(key).matches() || !key.startsWith(tenant + "/")) {
            throw new ApiException(ErrorCode.NOT_FOUND, null);
        }
        Path p = base.resolve(key).normalize();
        if (!p.startsWith(base)) {
            throw new ApiException(ErrorCode.NOT_FOUND, null);
        }
        return p;
    }

    @Override
    public String store(UUID tenant, byte[] content) {
        String key = tenant + "/" + UUID.randomUUID();
        Path target = resolve(tenant, key);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.write(tmp, content);
                try {
                    Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // not a POSIX file system
                }
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL, null);
        }
        return key;
    }

    @Override
    public byte[] load(UUID tenant, String key) {
        try {
            return Files.readAllBytes(resolve(tenant, key));
        } catch (IOException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, null);
        }
    }

    @Override
    public void delete(UUID tenant, String key) {
        try {
            Files.deleteIfExists(resolve(tenant, key));
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL, null);
        }
    }
}
