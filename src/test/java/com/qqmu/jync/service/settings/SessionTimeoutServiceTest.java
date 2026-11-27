package com.qqmu.jync.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import jakarta.servlet.ServletContext;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.AccessGuard;

class SessionTimeoutServiceTest {

    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final ServletContext context = mock(ServletContext.class);
    private final AccessGuard admin = AccessGuard.of("admin", UserRole.ADMIN);
    private final AccessGuard ops = AccessGuard.of("ops", UserRole.OPS);

    private SessionTimeoutService service() {
        when(settings.findValue(SessionTimeoutService.KEY)).thenReturn(Optional.empty());
        SessionTimeoutService service = new SessionTimeoutService(settings, 480);
        service.setServletContext(context);
        return service;
    }

    @Test
    void usesTheConfiguredBaseAndPreviewsAChange() {
        SessionTimeoutService service = service();

        SessionTimeoutService.Change change = service.preview(720);

        assertThat(service.currentMinutes()).isEqualTo(480);
        assertThat(change.beforeMinutes()).isEqualTo(480);
        assertThat(change.afterMinutes()).isEqualTo(720);
        assertThat(change.beforeIsDefault()).isTrue();
    }

    @Test
    void refusesAChangeBelowThirtyMinutes() {
        SessionTimeoutService service = service();

        assertThatThrownBy(() -> service.preview(29))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.session.timeout.range");
        assertThatThrownBy(() -> service.apply(29, admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.session.timeout.range");
    }

    @Test
    void applyingTheCardPersistsAndUpdatesTheServletContext() {
        SessionTimeoutService service = service();

        service.apply(720, admin);

        verify(settings).putValue(SessionTimeoutService.KEY, "720");
        verify(context).setSessionTimeout(720);
        assertThat(service.currentMinutes()).isEqualTo(720);
    }

    @Test
    void anOperatorCannotApplyTheValue() {
        SessionTimeoutService service = service();

        assertThatThrownBy(() -> service.apply(720, ops))
                .isInstanceOf(AccessDeniedException.class);
        verify(settings, never()).putValue(SessionTimeoutService.KEY, "720");
    }

    @Test
    void aStoredOverrideIsLoadedOnRefresh() {
        when(settings.findValue(SessionTimeoutService.KEY)).thenReturn(Optional.of("600"));
        SessionTimeoutService service = new SessionTimeoutService(settings, 480);
        service.setServletContext(context);

        service.refresh();

        assertThat(service.currentMinutes()).isEqualTo(600);
        verify(context).setSessionTimeout(600);
    }
}
