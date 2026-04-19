package org.fabt.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the v0.43 B12 invariant (task 3.18 hardening): when
 * {@code RlsAwareDataSource.applyRlsContext} fails mid-setup, the borrowed
 * connection MUST be closed before the {@link SQLException} escapes.
 *
 * <p><b>Safety chain in prod.</b> Calling {@code close()} on a HikariCP
 * proxy does NOT destroy the physical connection — it returns the proxy
 * to the pool with whatever session state was set before the failure.
 * The actual safety property is: (a) the decorator closes the proxy,
 * releasing it back to the pool, and (b) the NEXT borrow triggers
 * another {@code applyRlsContext} which overwrites the session GUCs
 * with the new caller's context (or empty for null TenantContext). A
 * half-configured connection therefore does not carry stale tenant
 * bindings into a subsequent request — the re-apply on borrow is the
 * load-bearing invariant, not connection-destruction.
 *
 * <p>Testing the chain via a real Testcontainers Postgres is awkward
 * because manufacturing a {@code SET ROLE} failure requires DB-level
 * GRANT manipulation. Since the decorator's error path is a small
 * try/catch (see {@link RlsDataSourceConfig.RlsAwareDataSource#applyRlsContext}),
 * a targeted unit test of the wrapper with mocked JDBC types covers the
 * close-on-failure contract; the re-apply-on-borrow half is covered by
 * {@code TenantIdPoolBleedTest} + {@code PgauditApplicationNameDriftTest}.
 */
@DisplayName("RlsAwareDataSource B12 — connection-setup failure closes the connection (task #165)")
class RlsAwareDataSourceFailureTest {

    @Test
    @DisplayName("getConnection(): if applyRlsContext fails, the connection is closed before the exception escapes")
    void applyRlsContextFailure_connectionClosedBeforeExceptionEscapes() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement pstmt = mock(PreparedStatement.class);
        SQLException failure = new SQLException("simulated SET ROLE fabt_app rejection (28P01)", "28P01");

        when(delegate.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(pstmt);
        when(pstmt.execute()).thenThrow(failure);

        RlsDataSourceConfig.RlsAwareDataSource wrapper =
                new RlsDataSourceConfig.RlsAwareDataSource(delegate, null);

        assertThatThrownBy(wrapper::getConnection)
                .as("B12: the SQLException from applyRlsContext must propagate, not be swallowed")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("SET ROLE fabt_app rejection");

        verify(conn).close();
    }

    @Test
    @DisplayName("getConnection(): success path does NOT call close() (connection is handed to caller)")
    void applyRlsContextSuccess_connectionHandedToCaller() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement pstmt = mock(PreparedStatement.class);

        when(delegate.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(pstmt);
        when(pstmt.execute()).thenReturn(true);

        RlsDataSourceConfig.RlsAwareDataSource wrapper =
                new RlsDataSourceConfig.RlsAwareDataSource(delegate, null);

        Connection returned = wrapper.getConnection();

        assertThat(returned)
                .as("Success path hands the prepared connection back to the caller unchanged")
                .isSameAs(conn);
        verify(conn, never()).close();
    }

    @Test
    @DisplayName("getConnection(): if close() itself throws, the original failure is preserved as the primary cause")
    void closeFailureIsSuppressedNotMasked() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement pstmt = mock(PreparedStatement.class);
        SQLException primary = new SQLException("primary: SET ROLE failed", "28P01");
        SQLException secondary = new SQLException("secondary: close failed", "08006");

        when(delegate.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(pstmt);
        when(pstmt.execute()).thenThrow(primary);
        org.mockito.Mockito.doThrow(secondary).when(conn).close();

        RlsDataSourceConfig.RlsAwareDataSource wrapper =
                new RlsDataSourceConfig.RlsAwareDataSource(delegate, null);

        assertThatThrownBy(wrapper::getConnection)
                .as("Primary failure must not be masked by the close() failure — "
                        + "diagnostic chain would be lost otherwise")
                .isSameAs(primary);

        assertThat(primary.getSuppressed())
                .as("Secondary close() failure captured as suppressed on the primary")
                .hasSize(1);
        assertThat(primary.getSuppressed()[0]).isSameAs(secondary);
    }

    @Test
    @DisplayName("getConnection(String,String): same close-on-failure contract applies")
    void credentialOverload_appliesSameFailureContract() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement pstmt = mock(PreparedStatement.class);
        SQLException failure = new SQLException("role lookup failed", "42704");

        when(delegate.getConnection("u", "p")).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(pstmt);
        when(pstmt.execute()).thenThrow(failure);

        RlsDataSourceConfig.RlsAwareDataSource wrapper =
                new RlsDataSourceConfig.RlsAwareDataSource(delegate, null);

        assertThatThrownBy(() -> wrapper.getConnection("u", "p"))
                .isInstanceOf(SQLException.class);

        verify(conn).close();
    }
}
