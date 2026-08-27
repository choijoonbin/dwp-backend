package com.dwp.core.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalKeyProviderFactoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsOwnerOnlyLocalKeyFilesAndProbes() throws IOException {
        Path keyFile = keyFile("v1");
        Files.write(keyFile, key(7));
        ownerOnly(keyFile);

        LocalKeyProvider provider = LocalKeyProviderFactory.files(
                temporaryDirectory,
                "dwp-agent",
                "payload",
                "local://dwp-agent/payload",
                "v1",
                List.of());

        provider.probe(new KeyContext(
                "local", "dwp-agent", "payload", 0, "startup-probe", "agent", "readiness"));
    }

    @Test
    void rejectsTraversalSymlinksAndGroupReadableFiles() throws IOException {
        assertThatThrownBy(() -> LocalKeyProviderFactory.files(
                temporaryDirectory,
                "../agent",
                "payload",
                "local://dwp-agent/payload",
                "v1",
                List.of()))
                .isInstanceOf(KeyProviderException.class);

        Path symlinkTarget = temporaryDirectory.resolve("outside.key");
        Files.write(symlinkTarget, key(8));
        ownerOnly(symlinkTarget);
        Path symlink = keyFile("linked-v1");
        try {
            Files.createSymbolicLink(symlink, symlinkTarget);
            assertThatThrownBy(() -> LocalKeyProviderFactory.files(
                    temporaryDirectory,
                    "dwp-agent",
                    "payload",
                    "local://dwp-agent/payload",
                    "linked-v1",
                    List.of()))
                    .isInstanceOf(KeyProviderException.class)
                    .hasMessageContaining("Symbolic links");
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems rely on the remaining path checks.
        }

        Path groupReadable = keyFile("group-v1");
        Files.write(groupReadable, key(8));
        try {
            Files.setPosixFilePermissions(groupReadable, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ));
            assertThatThrownBy(() -> LocalKeyProviderFactory.files(
                    temporaryDirectory,
                    "dwp-agent",
                    "payload",
                    "local://dwp-agent/payload",
                    "group-v1",
                    List.of()))
                    .isInstanceOf(KeyProviderException.class)
                    .hasMessageContaining("owner-only");
        } catch (UnsupportedOperationException ignored) {
            // Covered on POSIX CI and developer hosts.
        }
    }

    @Test
    void rejectsInvalidInlineKeysAndDuplicateVersions() {
        assertThatThrownBy(() -> LocalKeyProviderFactory.inline(
                "local://dwp-agent/payload",
                "v1",
                Base64.getEncoder().encodeToString(new byte[16]),
                Map.of()))
                .isInstanceOf(KeyProviderException.class);

        String encoded = Base64.getEncoder().encodeToString(key(9));
        assertThatThrownBy(() -> LocalKeyProviderFactory.inline(
                "local://dwp-agent/payload", "v1", encoded, Map.of("v1", encoded)))
                .isInstanceOf(KeyProviderException.class)
                .hasMessageContaining("unique");
    }

    private Path keyFile(String version) throws IOException {
        Path directory = temporaryDirectory.resolve("dwp-agent").resolve("payload");
        Files.createDirectories(directory);
        return directory.resolve(version + ".key");
    }

    private void ownerOnly(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems rely on the remaining file checks.
        }
    }

    private byte[] key(int fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) fill);
        return key;
    }
}
