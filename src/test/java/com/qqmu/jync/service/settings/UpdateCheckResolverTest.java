package com.qqmu.jync.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.settings.UpdateCheckResolver.Effective;
import com.qqmu.jync.service.settings.UpdateCheckResolver.Source;

/**
 * Priority: an explicit configuration value (environment variable or yml) beats the page,
 * the page beats the default. The default is on — a fresh installation asks GitHub/Gitee
 * unless someone says otherwise. Parsing an explicit value is strict, so a typo in an
 * environment variable fails the boot rather than silently re-enabling outbound requests.
 */
class UpdateCheckResolverTest {

    private static final SystemSettingKey KEY = SystemSettingKey.UPDATE_CHECK_ENABLED;

    private final SystemSettingService settings = mock(SystemSettingService.class);

    @Test
    void anExplicitFalseWinsOverTheDatabase() {
        when(settings.revision()).thenReturn(1L);
        when(settings.findBoolean(KEY)).thenReturn(Optional.of(true));

        Effective effective = new UpdateCheckResolver(settings, "false", 60_000).effective();

        assertThat(effective).isEqualTo(new Effective(false, Source.CONFIG));
        verify(settings, never()).findBoolean(any());
    }

    @Test
    void anExplicitTrueWinsOverTheDatabaseToo() {
        when(settings.revision()).thenReturn(1L);
        when(settings.findBoolean(KEY)).thenReturn(Optional.of(false));

        assertThat(new UpdateCheckResolver(settings, "on", 60_000).effective())
                .isEqualTo(new Effective(true, Source.CONFIG));
    }

    @Test
    void explicitValuesAcceptTheUsualSpellings() {
        assertThat(new UpdateCheckResolver(settings, "yes", 60_000).isEnabled()).isTrue();
        assertThat(new UpdateCheckResolver(settings, "1", 60_000).isEnabled()).isTrue();
        assertThat(new UpdateCheckResolver(settings, "ON", 60_000).isEnabled()).isTrue();
        assertThat(new UpdateCheckResolver(settings, "no", 60_000).isEnabled()).isFalse();
        assertThat(new UpdateCheckResolver(settings, "0", 60_000).isEnabled()).isFalse();
    }

    @Test
    void anUnparseableExplicitValueFailsFast() {
        assertThatThrownBy(() -> new UpdateCheckResolver(settings, "ture", 60_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.update-check.enabled");
    }

    @Test
    void aBlankConfigurationDefersToTheDatabase() {
        when(settings.revision()).thenReturn(1L);
        when(settings.findBoolean(KEY)).thenReturn(Optional.of(false));

        assertThat(new UpdateCheckResolver(settings, "", 60_000).effective())
                .isEqualTo(new Effective(false, Source.DATABASE));
    }

    @Test
    void noConfigurationAndNoRowMeansTheCheckIsOn() {
        when(settings.revision()).thenReturn(1L);
        when(settings.findBoolean(KEY)).thenReturn(Optional.empty());

        assertThat(new UpdateCheckResolver(settings, "  ", 60_000).effective())
                .isEqualTo(new Effective(true, Source.DEFAULT));
    }

    @Test
    void theDatabaseValueIsCachedForTheTtl() throws Exception {
        when(settings.revision()).thenReturn(1L);
        when(settings.findBoolean(KEY)).thenReturn(Optional.of(false), Optional.of(true));

        UpdateCheckResolver resolver = new UpdateCheckResolver(settings, "", 40);
        assertThat(resolver.isEnabled()).isFalse();
        assertThat(resolver.isEnabled()).isFalse();
        verify(settings, times(1)).findBoolean(KEY);

        Thread.sleep(120);

        assertThat(resolver.isEnabled()).isTrue();
    }

    @Test
    void aWriteOnThisNodeInvalidatesTheCacheImmediately() {
        when(settings.revision()).thenReturn(1L);
        when(settings.findBoolean(KEY)).thenReturn(Optional.of(true));
        UpdateCheckResolver resolver = new UpdateCheckResolver(settings, "", 60_000);

        assertThat(resolver.isEnabled()).isTrue();

        when(settings.revision()).thenReturn(2L);
        when(settings.findBoolean(KEY)).thenReturn(Optional.of(false));

        assertThat(resolver.isEnabled()).isFalse();
    }

    @Test
    void isConfigControlledTellsThePageWhenTheSwitchIsReadOnly() {
        assertThat(new UpdateCheckResolver(settings, "false", 60_000).isConfigControlled()).isTrue();
        assertThat(new UpdateCheckResolver(settings, "", 60_000).isConfigControlled()).isFalse();
    }
}
