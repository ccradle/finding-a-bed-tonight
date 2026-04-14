package org.fabt.referral.api;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response DTO for {@code GET /api/v1/dv-referrals/pending/count}.
 *
 * <p>Carries the total PENDING referral count across the caller's assigned DV
 * shelters, plus a routing hint identifying the oldest such pending referral
 * so the {@code CoordinatorReferralBanner} can deep-link directly to the
 * pending referral's shelter without a second round-trip. Before Phase 4 the
 * banner's no-query-param click path fell back to
 * {@code shelters.find(s => s.dvShelter)} which picked the alphabetically-
 * first DV shelter regardless of where the pending referral actually lived
 * — the original user story that motivated the entire
 * notification-deep-linking change (see {@code openspec/changes/
 * notification-deep-linking/design.md} decision D-BP).</p>
 *
 * <p><b>Null semantics:</b> when {@link #count} is {@code 0},
 * {@link #firstPending} is JSON {@code null} (not omitted). We do NOT opt into
 * {@code @JsonInclude(NON_NULL)} for this field — the contract distinguishes
 * "no pending referrals" (explicit null) from "field missing" (client parse
 * error). When {@code count >= 1}, {@link #firstPending} identifies the
 * referral with the earliest {@code created_at} across the caller's assigned
 * shelters.</p>
 *
 * <p><b>Zero client PII:</b> only the referral UUID and shelter UUID are
 * surfaced — same shape the {@code /pending} list endpoint already exposes.
 * No household details, callback numbers, or other payload content.</p>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PendingReferralCountResponse(
        int count,
        FirstPending firstPending
) {
    /**
     * Routing hint — oldest PENDING referral the caller is authorized to see,
     * or {@code null} when {@code count == 0}.
     */
    public record FirstPending(
            UUID referralId,
            UUID shelterId
    ) {
    }
}
