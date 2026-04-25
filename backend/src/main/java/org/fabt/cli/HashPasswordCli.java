package org.fabt.cli;

import java.io.Console;
import java.util.Arrays;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Bootstrap-activation CLI for platform_user accounts. Prompts for a
 * password (interactive, no echo via {@link Console#readPassword()}),
 * validates strength, and prints a bcrypt-12 hash that the operator
 * pastes into a {@code psql UPDATE} on the V87 bootstrap row.
 *
 * <h2>Invocation</h2>
 *
 * <p>Per design Decision 8, the CLI was originally specified to ship as
 * a separate Maven module producing {@code fabt-cli.jar}. The single-module
 * project layout (one {@code pom.xml} under {@code backend/}) makes that
 * a multi-module restructure that's out of scope for the v0.53 slice.
 * Deferred to design follow-up F4. Interim ops invocation:
 *
 * <pre>
 *   # Inside the running container (simplest path):
 *   docker exec -it fabt-backend java \
 *       -cp /app/app.jar \
 *       -Dloader.main=org.fabt.cli.HashPasswordCli \
 *       org.springframework.boot.loader.launch.PropertiesLauncher
 *
 *   # Or via htpasswd as a Spring-Security-compatible alternative:
 *   htpasswd -bnBC 12 "" "&lt;your-password&gt;" \
 *       | tr -d ':\n' \
 *       | sed 's/^\$2y/\$2a/'
 * </pre>
 *
 * <h2>Strength policy</h2>
 *
 * <p>Operator passwords MUST be at least 16 characters. The platform-
 * operator account class is the highest-value identity in FABT (suspend
 * tenants, hard-delete via crypto-shred). 16 chars is the floor; the
 * runbook recommends a passphrase from a passphrase generator, not a
 * hand-crafted password.
 *
 * <h2>Output</h2>
 *
 * <p>Prints the bcrypt-12 hash to STDOUT, single line, no trailing
 * whitespace. Exits 0 on success, 1 on validation failure (length,
 * confirmation mismatch). On non-interactive STDIN ({@code Console}
 * unavailable) exits 2 with a message — refuses to read passwords
 * from an unsafe source.
 */
public final class HashPasswordCli {

    private static final int MIN_LENGTH = 16;
    private static final int BCRYPT_COST = 12;

    private HashPasswordCli() {
    }

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h")) {
            System.out.println("hash-password — generates a bcrypt-12 hash for a platform_user");
            System.out.println("Usage: java -cp app.jar [-Dloader.main=...] org.fabt.cli.HashPasswordCli");
            System.out.println("Reads password interactively from System.console().");
            System.out.println("Validates min " + MIN_LENGTH + " chars + confirmation match.");
            System.exit(0);
        }
        if (Arrays.asList(args).contains("--version")) {
            System.out.println("fabt hash-password CLI v0.53.0");
            System.exit(0);
        }

        Console console = System.console();
        if (console == null) {
            System.err.println(
                    "ERROR: System.console() unavailable — refuses to read password from non-tty source.");
            System.err.println("Run interactively from a terminal (no piping, no nohup).");
            System.exit(2);
            return;
        }

        char[] first = console.readPassword("Enter platform_user password: ");
        if (first == null || first.length < MIN_LENGTH) {
            System.err.println("ERROR: password must be at least " + MIN_LENGTH + " characters.");
            zero(first);
            System.exit(1);
            return;
        }

        char[] second = console.readPassword("Confirm password:            ");
        if (second == null || !Arrays.equals(first, second)) {
            System.err.println("ERROR: confirmation did not match.");
            zero(first);
            zero(second);
            System.exit(1);
            return;
        }

        String hash = new BCryptPasswordEncoder(BCRYPT_COST).encode(new String(first));
        zero(first);
        zero(second);

        System.out.println(hash);
    }

    private static void zero(char[] chars) {
        if (chars != null) {
            Arrays.fill(chars, '\0');
        }
    }
}
