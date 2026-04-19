package org.fabt.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Failure-branch unit tests for {@link PgVersionGate}. Covers the paths the
 * IT (in {@link PgVersionGateTest}) cannot reach: a real Postgres server is
 * always above the floor, so the {@code IllegalStateException} branches
 * never fire in an integration context. Without these a regression that
 * inverts the comparison operator (e.g. {@code <} → {@code >}) would pass
 * CI silently.
 */
@ExtendWith(MockitoExtension.class)
class PgVersionGateUnitTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    @Test
    void belowFloor_throwsIllegalStateExceptionReferencingBothVersions() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenReturn(160004);

        PgVersionGate gate = new PgVersionGate(jdbcTemplate);

        assertThatThrownBy(gate::assertMinimumVersion)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("160004")
                .hasMessageContaining(String.valueOf(PgVersionGate.MIN_SERVER_VERSION_NUM));
    }

    @Test
    void nullFromQuery_throwsIllegalStateException() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenReturn(null);

        PgVersionGate gate = new PgVersionGate(jdbcTemplate);

        assertThatThrownBy(gate::assertMinimumVersion)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null");
    }

    @Test
    void atFloor_passes() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenReturn(PgVersionGate.MIN_SERVER_VERSION_NUM);

        PgVersionGate gate = new PgVersionGate(jdbcTemplate);

        gate.assertMinimumVersion(); // no throw
        assertThat(gate).isNotNull();
    }

    @Test
    void aboveFloor_passes() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenReturn(PgVersionGate.MIN_SERVER_VERSION_NUM + 100);

        PgVersionGate gate = new PgVersionGate(jdbcTemplate);

        gate.assertMinimumVersion(); // no throw
        assertThat(gate).isNotNull();
    }
}
