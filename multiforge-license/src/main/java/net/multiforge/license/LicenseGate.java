/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import net.multiforge.license.internal.PublicKeys;

/**
 * The very first hook in the MultiForge server bootstrap. Resolves,
 * verifies, and gates on a signed license token before any Vanilla or
 * NeoForge initialization runs.
 *
 * <p>Exit code {@code 78} ({@code EX_CONFIG}) is used on failure so
 * container orchestrators can distinguish license failure from other
 * boot failures.
 */
public final class LicenseGate {

    public static final String ENV_VAR = "MULTIFORGE_LICENSE";
    public static final String SYSTEM_PROPERTY = "multiforge.license";
    public static final String FILE_NAME = "license.key";
    public static final int EXIT_CODE = 78;

    private final PublicKeys publicKeys;
    private final Path workingDirectory;
    private final Environment environment;
    private final Exiter exiter;

    public LicenseGate() {
        this(PublicKeys.loadBakedIn(), Path.of("").toAbsolutePath(), new SystemEnvironment(), System::exit);
    }

    public LicenseGate(PublicKeys publicKeys, Path workingDirectory, Environment environment, Exiter exiter) {
        this.publicKeys = publicKeys;
        this.workingDirectory = workingDirectory;
        this.environment = environment;
        this.exiter = exiter;
    }

    /**
     * Verifies the license or terminates the JVM.
     *
     * @return the parsed license on success; never returns on failure.
     */
    public LicenseToken bootstrap() {
        try {
            String token = resolveToken();
            LicenseTokenParser parser = new LicenseTokenParser(publicKeys);
            LicenseToken parsed = parser.parse(token);
            parser.checkTime(parsed, Instant.now());
            parser.checkFeature(parsed, LicenseTokenParser.FEATURE_CORE);
            System.out.println("[MultiForge] License OK: sub=" + parsed.subject() + " exp="
                    + parsed.expiresAt().map(Object::toString).orElse("perpetual"));
            return parsed;
        } catch (LicenseException e) {
            System.err.println("[MultiForge] " + e.reason() + ": " + e.getMessage());
            System.err.println("[MultiForge] See https://multiforge.example/license — refusing to start.");
            exiter.exit(EXIT_CODE);
            throw new IllegalStateException("Exiter did not terminate JVM");
        }
    }

    private String resolveToken() {
        String env = environment.getEnv(ENV_VAR);
        if (env != null && !env.isBlank()) return env;
        String sys = environment.getProperty(SYSTEM_PROPERTY);
        if (sys != null && !sys.isBlank()) return sys;
        Path file = workingDirectory.resolve(FILE_NAME);
        if (Files.isRegularFile(file)) {
            try {
                return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new LicenseException(LicenseException.Reason.MISSING, "Failed to read " + file, e);
            }
        }
        throw new LicenseException(
                LicenseException.Reason.MISSING,
                "License token not found. Set " + ENV_VAR + ", -D" + SYSTEM_PROPERTY + ", or " + FILE_NAME + " in "
                        + workingDirectory);
    }

    public interface Environment {
        String getEnv(String name);

        String getProperty(String name);
    }

    @FunctionalInterface
    public interface Exiter {
        void exit(int status);
    }

    static final class SystemEnvironment implements Environment {
        @Override
        public String getEnv(String name) {
            return System.getenv(name);
        }

        @Override
        public String getProperty(String name) {
            return System.getProperty(name);
        }
    }
}
