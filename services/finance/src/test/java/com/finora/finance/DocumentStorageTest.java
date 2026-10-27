package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.commons.error.ApiException;
import com.finora.finance.documents.LocalDocumentStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The local storage only ever touches files it generated, inside its own directory. */
class DocumentStorageTest {

    @Test
    void storesUnderGeneratedKeysAndRefusesTamperedOnes() throws Exception {
        Path dir = Files.createTempDirectory("finora-doc-test");
        LocalDocumentStorage storage = new LocalDocumentStorage(dir.toString());
        UUID tenant = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String key = storage.store(tenant, "hello".getBytes());
        assertThat(key).matches("^[0-9a-f-]{36}/[0-9a-f-]{36}$").startsWith(tenant + "/");
        assertThat(storage.load(tenant, key)).isEqualTo("hello".getBytes());
        assertThat(Files.list(dir.resolve(tenant.toString())).toList()).hasSize(1);
        for (String bad : new String[] {"../../etc/passwd", "../" + key, tenant + "/../" + other + "/x", "/etc/passwd", tenant + "/" + "../../x", "", "a/b", key + "/..", key.replace('/', '\\')}) {
            assertThatThrownBy(() -> storage.load(tenant, bad)).as(bad).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> storage.delete(tenant, bad)).as(bad).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> storage.load(other, key)).as("another workspace's key").isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.load(tenant, null)).isInstanceOf(ApiException.class);
        storage.delete(tenant, key);
        assertThatThrownBy(() -> storage.load(tenant, key)).isInstanceOf(ApiException.class);
        storage.delete(tenant, key); // deleting twice is harmless
    }
}
