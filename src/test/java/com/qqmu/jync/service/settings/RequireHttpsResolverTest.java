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

import com.qqmu.jync.service.settings.RequireHttpsResolver.Effective;
import com.qqmu.jync.service.settings.RequireHttpsResolver.Source;

/**
 * Priority: an explicit configuration value (environment variable or yml) beats the page,
 * the page beats the default. Parsing an explicit value is strict — a typo in an environment
 * variable must fail fast rather than silently leave enforcement off.
 */
class RequireHttpsResolverTest {

    private static final SystemSettingKey KEY = SystemSettingKey.SECURITY_REQUIRE_HTTPS;

    private final SystemSettingService settings = mock(SystemSettingService.class);

    @Test
    void anExplicitTrueWinsOverTheDatabase() {
        when(settings.revision()).thenReturn(1L);
        when(settings.findBoolean(KEY)).thenReturn(Optional.of(false));

        Effective effective = new RequireHttpsResolver(settings, "true", 60_000).effective();

        assertThat(effective).isEqualTo(new Effective(true, Source.CONFIG));
        verify(settings, never()).findBoolean(any());
    }

    @Test
    void anExplicitFalseWinsOverTheDatabaseToo() {
        when(settings.revision()).thenReturn(1L);
        when(settings.findBoolean(KEY)).thenReturn(Optional.of(true));

        Effective effective = new RequireHttpsResolver(settings, "off", 60_000).effective();

        assertThat(effective).isEqualTo(new Effective(false, Source.CONFIG));
    }

    @Test
    void explicitValuesAcceptTheUsualSpellings() {
        assertThat(new RequireHttpsResolver(settings, "yes", 60_000).isRequired()).isTrue();
        assertThat(new RequireHttpsResolver(settings, "1", 60_000).isRequired()).isTrue();
        assertThat(new RequireHttpsResolver(settings, "ON", 60_000).isRequired()).isTrue();
        assertThat(new RequireHttpsResolver(settings, "no", 60_000).isRequired()).isFalse();
        assertThat(new RequireHttpsResolver(settings, "0", 60_000).isRequired()).isFalse();
    }

    @Test
    void anUnparseableExplicitValueFailsFast() {
        assertThatThrownBy(() -> new RequireHttpsResolver(settings, "ture", 60_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.security.require-https");
    }

    @Test
    void aBlankConfigurationDefersToTheDatabase() {
        when(settings.revision()).thenReturn(1L);
        when(settings.findBoolean(KEY)).thenReturn(Optional.of(true));

        Effective effective = new RequireHttpsResolver(settings, "", 60_000).effective();

        assertThat(effective).isEqualTo(new Effective(true, Source.DATABASE));
    }

    @Test
    void noConfigurationAndNoRowMeansTheFeatureIsOff() {
        when(settings.revision()).thenReturn(1L);
        when(settings.findBoolean(KEY)).thenReturn(Optional.empty());

        assertThat(new RequireHttpsResolver(settings, "  ", 60_000).effective())
                .isEqualTo(new Effective(false, Source.DEFAULT));
    }

    @Test
    void theDatabaseValueIsCachedForTheTtl() throws Exception {
        when(settings.revision()).thenReturn(1L);
        when(settings.findBoolean(KEY)).thenReturn(Optional.of(false), Optional.of(true));

        RequireHttpsResolver resolver = new RequireHttpsResolver(settings, "", 40);
        assertThat(resolver.isRequired()).isFalse();
        assertThat(resolver.isRequired()).isFalse();
        verify(settings, times(1)).findBoolean(KEY);

        Thread.sleep(120);

        assertThat(resolver.isRequired()).isTrue();
    }

    @Test
    void aWriteOnThisNodeInvalidatesTheCacheImmediately() {
        when(settings.revision()).thenReturn(1L);
        when(settings.findBoolean(KEY)).thenReturn(Optional.of(false));
        RequireHttpsResolver resolver = new RequireHttpsResolver(settings, "", 60_000);

        assertThat(resolver.isRequired()).isFalse();

        when(settings.revision()).thenReturn(2L);
        when(settings.findBoolean(KEY)).thenReturn(Optional.of(true));

        assertThat(resolver.isRequired()).isTrue();
    }

    @Test
    void isConfigControlledTellsThePageWhenTheSwitchIsReadOnly() {
        assertThat(new RequireHttpsResolver(settings, "true", 60_000).isConfigControlled()).isTrue();
        assertThat(new RequireHttpsResolver(settings, "", 60_000).isConfigControlled()).isFalse();
    }
}
