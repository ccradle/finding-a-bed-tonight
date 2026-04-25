package org.fabt.auth.platform;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

/**
 * Phase G-4.4 task §5.4a — request-scoped helper letting controllers
 * annotated {@link PlatformAdminOnly} populate {@code before_state} /
 * {@code after_state} JSONB columns on the {@code platform_admin_access_log}
 * row.
 *
 * <h2>Why request-scoped + why a separate bean</h2>
 *
 * <p>The {@link PlatformAdminLogger} aspect runs {@code @Around} the
 * controller method. Audit rows commit BEFORE {@code proceed()} (Decision
 * 11), so the aspect cannot capture state at the natural service-call
 * boundary inside the method body. Instead, the controller method itself
 * calls {@link #captureBefore(Map)} just before the operation and
 * {@link #captureAfter(Map)} just after. The aspect then drains the
 * captured state from this request-scoped bean into the audit row.
 *
 * <p>Request scope is the right boundary because: (a) the bean is
 * scoped to the lifecycle of a single platform-admin call; (b) Spring's
 * {@code TARGET_CLASS} proxy lets the singleton aspect inject this bean
 * transparently, with the proxy resolving to the current request's
 * instance at every call.
 *
 * <h2>Allowlist enforcement (warroom P2)</h2>
 *
 * <p>The bean's {@link #captureBefore(Map)} / {@link #captureAfter(Map)}
 * methods filter input through a denylist regex matching common secret-
 * related keys ({@code password}, {@code secret}, {@code key},
 * {@code token}, {@code hash}, {@code dek}, {@code cipher}, {@code salt},
 * {@code signature}, {@code nonce}). Matching entries are dropped with a
 * WARN log. Caller-side discipline (controller picks only safe fields)
 * remains the primary defense; the denylist is defense-in-depth.
 *
 * <h2>What controllers should capture</h2>
 *
 * <p>Per-action allowlist (recommended fields, NOT enforced by the bean —
 * the controller chooses):
 * <ul>
 *   <li><b>Tenant lifecycle</b> ({@code suspend / unsuspend / offboard / hardDelete}) —
 *       {@code state}, {@code slug}, {@code name}, {@code archived_at},
 *       {@code created_at}</li>
 *   <li><b>Tenant key rotation</b> — {@code tenantId}, {@code generation_before},
 *       {@code generation_after}</li>
 *   <li><b>HMIS export</b> — {@code tenantId}, {@code row_count_estimate},
 *       {@code export_format}</li>
 *   <li><b>OAuth2 test connection</b> — {@code tenantId}, {@code provider_name},
 *       {@code probe_outcome}</li>
 *   <li><b>Batch job trigger</b> — {@code job_name}, {@code parameters_keys}
 *       (key names only, not values)</li>
 * </ul>
 */
@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
public class PlatformActionStateCapture {

    private static final Logger log = LoggerFactory.getLogger(PlatformActionStateCapture.class);

    /**
     * Defense-in-depth denylist. Any map key matching this pattern (case-
     * insensitive) is dropped from captured state. Errs on the side of
     * over-rejection: callers should pick controlled allowlist field
     * names that don't trigger the regex (e.g. {@code state} instead of
     * {@code apiKey}). Pattern is intentionally broad.
     */
    private static final Pattern SECRET_KEY_DENYLIST = Pattern.compile(
            "(?i).*(password|secret|key|token|hash|dek|kek|cipher|salt|signature|nonce|credential).*");

    private Map<String, Object> beforeState;
    private Map<String, Object> afterState;

    /**
     * Captures the entity state BEFORE the platform action runs. Called
     * by the controller method just before the destructive / mutating
     * operation. The caller selects which fields to capture; the bean
     * filters out denylisted key patterns as defense-in-depth.
     *
     * @param state field name → value. Values must be JSON-serializable
     *              (String, Number, Boolean, UUID, nested Map). Caller
     *              is responsible for keeping payload below the V89
     *              {@code pg_column_size <= 65536} CHECK constraint.
     */
    public void captureBefore(Map<String, Object> state) {
        this.beforeState = filtered(state);
    }

    /**
     * Captures the entity state AFTER the platform action runs. Called
     * by the controller method just after the operation, but before the
     * controller returns (the aspect has already committed PAL row with
     * NULL after_state and AE row by this point — see Decision 11).
     *
     * <p>NOTE: at the time this method is called, the aspect's audit
     * rows have ALREADY been committed (Decision 11 ordering). The
     * aspect's commit happens BEFORE proceed(); after_state captured
     * here is therefore visible to subsequent reads of the PAL row only
     * if the aspect runs an UPDATE post-proceed — but PAL has the
     * append-only trigger from V89/D4 which raises on UPDATE. <b>Conclusion:
     * after_state populated via this helper is captured for in-memory
     * forensics during the request scope but does NOT reach the PAL
     * row.</b> See F13 follow-up in design.md.
     *
     * <p>For v0.53, controllers SHOULD still call this method — it
     * surfaces the post-action state in application logs (the aspect
     * logs MDC platform_action=true with the captured state on commit
     * failure) and prepares for F13 when we restructure the aspect to
     * commit PAL after proceed().
     */
    public void captureAfter(Map<String, Object> state) {
        this.afterState = filtered(state);
    }

    /** Aspect entry point — reads the captured before-state, or null. */
    Map<String, Object> getBeforeState() {
        return beforeState;
    }

    /** Aspect entry point — reads the captured after-state, or null. */
    Map<String, Object> getAfterState() {
        return afterState;
    }

    private Map<String, Object> filtered(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>(input.size());
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (SECRET_KEY_DENYLIST.matcher(entry.getKey()).matches()) {
                log.warn("PlatformActionStateCapture dropped denylisted key '{}' — "
                        + "platform-admin audit state-capture must not include credentials, "
                        + "secrets, or key material. Caller should select only safe field "
                        + "names (e.g. state, slug, generation).", entry.getKey());
                continue;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result.isEmpty() ? null : result;
    }
}
