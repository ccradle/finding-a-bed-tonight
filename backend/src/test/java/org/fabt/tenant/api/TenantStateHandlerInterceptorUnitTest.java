package org.fabt.tenant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.domain.TenantState;
import org.fabt.tenant.domain.TenantStateGuardException;
import org.fabt.tenant.service.TenantStateGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TenantStateHandlerInterceptor}. Pins the skip-vs-enforce contract
 * so future regressions to the interceptor don't silently turn it into a no-op (which
 * would eliminate the whole F-3.3 defense-in-depth layer without breaking any test
 * elsewhere).
 */
@ExtendWith(MockitoExtension.class)
class TenantStateHandlerInterceptorUnitTest {

    @Mock
    TenantStateGuard tenantStateGuard;

    @Mock
    HttpServletRequest request;

    @Mock
    HttpServletResponse response;

    @Test
    void preHandle_noTenantContext_skipsGuard_returnsTrue() {
        TenantStateHandlerInterceptor interceptor = new TenantStateHandlerInterceptor(tenantStateGuard);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        verify(tenantStateGuard, never()).requireActive(any());
    }

    @Test
    void preHandle_systemTenantId_skipsGuard_returnsTrue() {
        TenantStateHandlerInterceptor interceptor = new TenantStateHandlerInterceptor(tenantStateGuard);

        Boolean result = TenantContext.callWithContext(
            TenantContext.SYSTEM_TENANT_ID, false,
            () -> interceptor.preHandle(request, response, new Object()));

        assertThat(result).isTrue();
        verify(tenantStateGuard, never()).requireActive(any());
    }

    @Test
    void preHandle_activeTenant_callsRequireActive_returnsTrue() {
        TenantStateHandlerInterceptor interceptor = new TenantStateHandlerInterceptor(tenantStateGuard);
        UUID tenantId = UUID.randomUUID();

        Boolean result = TenantContext.callWithContext(tenantId, false,
            () -> interceptor.preHandle(request, response, new Object()));

        assertThat(result).isTrue();
        verify(tenantStateGuard, times(1)).requireActive(tenantId);
    }

    @Test
    void preHandle_suspendedTenant_guardThrows_exceptionPropagates() {
        // TenantLifecycleExceptionAdvice maps the exception to 404/503; the
        // interceptor does NOT swallow. Verify the throw propagates past
        // preHandle so the advice can fire.
        TenantStateHandlerInterceptor interceptor = new TenantStateHandlerInterceptor(tenantStateGuard);
        UUID tenantId = UUID.randomUUID();
        TenantStateGuardException suspended = TenantStateGuardException.nonActive(
            tenantId, TenantState.SUSPENDED);
        doThrow(suspended).when(tenantStateGuard).requireActive(tenantId);

        assertThatThrownBy(() -> TenantContext.callWithContext(tenantId, false,
            () -> interceptor.preHandle(request, response, new Object())))
            .isInstanceOf(TenantStateGuardException.class)
            .satisfies(ex -> {
                TenantStateGuardException typed = (TenantStateGuardException) ex;
                assertThat(typed.kind()).isEqualTo(TenantStateGuardException.Kind.NON_ACTIVE);
                assertThat(typed.observedState()).isEqualTo(TenantState.SUSPENDED);
            });
    }
}
