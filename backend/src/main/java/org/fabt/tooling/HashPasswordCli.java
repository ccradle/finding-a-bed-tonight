package org.fabt.tooling;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * CLI tool for generating bcrypt hashes for the platform_user bootstrap
 * activation flow (referenced from `docs/oracle-update-notes-v0.53.0.md`
 * §5.10 + V87 migration header). The bootstrap row at id
 * `00000000-0000-0000-0000-000000000fab` ships LOCKED with no password;
 * the operator activates it on first deploy by:
 *
 * <ol>
 *   <li>Running this tool to compute a bcrypt hash for the chosen
 *       password (no password ever leaves the operator's terminal).</li>
 *   <li>Pasting the hash into a one-shot {@code UPDATE platform_user
 *       SET password_hash = '...', email = '...', account_locked = false
 *       WHERE id = '...0fab'} run as the {@code fabt} DB owner (V87
 *       REVOKEs prevent {@code fabt_app}).</li>
 *   <li>Logging in via {@code POST /api/v1/auth/platform/login} and
 *       completing forced-MFA-on-first-login.</li>
 * </ol>
 *
 * <p>Invocation (works against the deployed backend JAR — no separate
 * fabt-cli module needed):
 *
 * <pre>{@code
 *   docker exec -it fabt-backend \
 *     java -cp /app/app.jar \
 *          -Dloader.main=org.fabt.tooling.HashPasswordCli \
 *          org.springframework.boot.loader.launch.PropertiesLauncher
 * }</pre>
 *
 * <p>The {@code .launch.} package segment is required — Spring Boot 4
 * moved {@code PropertiesLauncher} from {@code o.s.b.loader} to
 * {@code o.s.b.loader.launch}. The old (Spring Boot 3) path
 * {@code org.springframework.boot.loader.PropertiesLauncher} now
 * {@code ClassNotFoundException}s.
 *
 * <p>The tool prints a single line to stdout: the bcrypt hash. Reads the
 * password from stdin (echoed if not a TTY, e.g. piped from a file —
 * acceptable since the operator is the only one with shell access at
 * deploy time). For interactive use it tries {@link Console#readPassword}
 * first to suppress echo.
 *
 * <p>Cost factor: matches Spring Security default ({@code 10}). This
 * matches the existing dev seed bcrypt hash for password {@code admin123}
 * which is also cost-10.
 *
 * <p>NOT a Spring Boot application — no {@code @SpringBootApplication}
 * — to keep the CLI startup time at ~50ms instead of the full app's
 * ~5-10s. Only depends on {@code spring-security-crypto} which is
 * already on the classpath.
 */
public final class HashPasswordCli {

    private HashPasswordCli() {}

    public static void main(String[] args) {
        try {
            String password = readPassword();
            if (password == null || password.isEmpty()) {
                System.err.println("ERROR: empty password — refusing to hash. Re-run and provide a password.");
                System.exit(2);
            }
            String hash = new BCryptPasswordEncoder().encode(password);
            // Single-line stdout output — operator copies this into a SQL UPDATE.
            System.out.println(hash);
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String readPassword() throws Exception {
        Console console = System.console();
        if (console != null) {
            // Interactive TTY — read silently with no echo
            System.err.print("Password (no echo): ");
            char[] chars = console.readPassword();
            return chars == null ? null : new String(chars);
        }
        // Non-interactive (piped / redirected) — read with echo on
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            return reader.readLine();
        }
    }
}
