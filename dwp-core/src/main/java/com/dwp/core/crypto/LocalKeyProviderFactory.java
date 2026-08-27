package com.dwp.core.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict loaders for ignored local inline values and 0600 runtime key files. */
public final class LocalKeyProviderFactory {

    private static final Pattern PATH_COMPONENT =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    private LocalKeyProviderFactory() {}

    public static LocalKeyProvider inline(
            String immutableKeyId,
            String activeVersion,
            String activeBase64Key,
            Map<String, String> previousBase64Keys) {
        Map<String, byte[]> keys = new LinkedHashMap<>();
        if (previousBase64Keys != null) {
            previousBase64Keys.forEach((version, key) ->
                    putUnique(keys, version, decodeBase64Key(key)));
        }
        putUnique(keys, activeVersion, decodeBase64Key(activeBase64Key));
        return new LocalKeyProvider(
                LocalKeyProvider.INLINE_PROVIDER,
                immutableKeyId,
                activeVersion,
                keys);
    }

    public static LocalKeyProvider files(
            Path localRoot,
            String service,
            String purpose,
            String immutableKeyId,
            String activeVersion,
            List<String> previousVersions) {
        Path root = canonicalRoot(localRoot);
        String safeService = pathComponent(service, "service");
        String safePurpose = pathComponent(purpose, "purpose");
        Set<String> versions = new LinkedHashSet<>(previousVersions == null
                ? List.of()
                : previousVersions);
        if (!versions.add(activeVersion)) {
            throw new KeyProviderException("Local active key version is duplicated.");
        }
        Map<String, byte[]> keys = new LinkedHashMap<>();
        for (String version : versions) {
            String safeVersion = pathComponent(version, "version");
            Path file = root.resolve(safeService)
                    .resolve(safePurpose)
                    .resolve(safeVersion + ".key")
                    .normalize();
            if (!file.startsWith(root)) {
                throw new KeyProviderException("Local key path escapes the configured root.");
            }
            rejectSymlinks(root, file);
            requirePrivateRegularFile(file);
            putUnique(keys, safeVersion, readKey(file));
        }
        return new LocalKeyProvider(
                LocalKeyProvider.FILE_PROVIDER,
                immutableKeyId,
                activeVersion,
                keys);
    }

    private static Path canonicalRoot(Path root) {
        if (root == null) throw new KeyProviderException("Local key root is required.");
        Path absolute = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(absolute)) {
            throw new KeyProviderException("Local key root must be a non-symbolic directory.");
        }
        return absolute;
    }

    private static void rejectSymlinks(Path root, Path file) {
        Path current = root;
        Path relative = root.relativize(file);
        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new KeyProviderException("Symbolic links are not allowed in local key paths.");
            }
        }
    }

    private static void requirePrivateRegularFile(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new KeyProviderException("Local key must be a regular file.");
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                    file, LinkOption.NOFOLLOW_LINKS);
            Set<PosixFilePermission> forbidden = Set.of(
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE);
            if (permissions.stream().anyMatch(forbidden::contains)) {
                throw new KeyProviderException("Local key file permissions must be owner-only.");
            }
            String expectedOwner = System.getProperty("user.name", "");
            String actualOwner = Files.getOwner(file, LinkOption.NOFOLLOW_LINKS).getName();
            if (!expectedOwner.isBlank()
                    && !actualOwner.equals(expectedOwner)
                    && !actualOwner.endsWith("/" + expectedOwner)
                    && !actualOwner.endsWith("\\" + expectedOwner)) {
                throw new KeyProviderException("Local key file owner does not match the runtime user.");
            }
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems still receive no-symlink and regular-file checks.
        } catch (IOException exception) {
            throw new KeyProviderException("Local key file metadata cannot be validated.", exception);
        }
    }

    private static byte[] readKey(Path file) {
        try {
            long size = Files.size(file);
            if (size <= 0 || size > 1024) {
                throw new KeyProviderException("Local key file size is invalid.");
            }
            byte[] value = Files.readAllBytes(file);
            if (value.length == 32) return value;
            return decodeBase64Key(new String(value, StandardCharsets.US_ASCII).trim());
        } catch (IOException exception) {
            throw new KeyProviderException("Local key file cannot be read.", exception);
        }
    }

    private static byte[] decodeBase64Key(String encoded) {
        try {
            byte[] key = Base64.getDecoder().decode(encoded == null ? "" : encoded.trim());
            if (key.length != 32) {
                throw new KeyProviderException("Local wrapping key must contain 32 bytes.");
            }
            return key;
        } catch (IllegalArgumentException exception) {
            throw new KeyProviderException("Local wrapping key is not valid Base64.", exception);
        }
    }

    private static void putUnique(Map<String, byte[]> keys, String version, byte[] key) {
        String safeVersion = pathComponent(version, "version");
        if (keys.put(safeVersion, key) != null) {
            throw new KeyProviderException("Local keyring versions must be unique.");
        }
    }

    private static String pathComponent(String value, String field) {
        if (value == null || !PATH_COMPONENT.matcher(value).matches()) {
            throw new KeyProviderException("Local key " + field + " is invalid.");
        }
        return value;
    }
}
