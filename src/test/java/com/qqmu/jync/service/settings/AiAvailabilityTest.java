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
import org.springframework.dao.DataAccessResourceFailureException;

import com.qqmu.jync.service.ai.AiDisabledException;

/**
 * The AI feature has two switches: a deployment-level hard off that always wins, and the page
 * switch that can be flipped back. The database read sits behind a short cache because the
 * layout asks for it on every render.
 */
class AiAvailabilityTest {

    private final SystemSettingService settings = mock(SystemSettingService.class);

    @Test
    void theDeploymentSwitchAlwaysWinsWithoutTouchingTheDatabase() {
        when(settings.findBoolean(SystemSettingKey.AI_ENABLED)).thenReturn(Optional.of(true));

        AiAvailability ai = new AiAvailability(settings, false, 5_000L);

        assertThat(ai.isEnabled()).isFalse();
        assertThat(ai.isHardDisabled()).isTrue();
        verify(settings, never()).findBoolean(any());
    }

    @Test
    void theDatabaseSwitchAppliesWhenTheDeploymentDoesNotPinIt() {
        when(settings.revision()).thenReturn(1L);
        when(settings.findBoolean(SystemSettingKey.AI_ENABLED)).thenReturn(Optional.empty());
        assertThat(new AiAvailability(settings, true, 60_000L).isEnabled()).isTrue();

        when(settings.findBoolean(SystemSettingKey.AI_ENABLED)).thenReturn(Optional.of(false));
        when(settings.revision()).thenReturn(2L);
        assertThat(new AiAvailability(settings, true, 60_000L).isEnabled()).isFalse();
    }

    @Test
    void theCachedValueIsDroppedWhenTheRevisionChanges() {
        when(settings.findBoolean(SystemSettingKey.AI_ENABLED)).thenReturn(Optional.of(true));
        when(settings.revision()).thenReturn(1L);
        AiAvailability ai = new AiAvailability(settings, true, 60_000L);
        assertThat(ai.isEnabled()).isTrue();

        when(settings.findBoolean(SystemSettingKey.AI_ENABLED)).thenReturn(Optional.of(false));
        when(settings.revision()).thenReturn(2L);
        assertThat(ai.isEnabled()).isFalse();
        verify(settings, times(2)).findBoolean(SystemSettingKey.AI_ENABLED);
    }

    @Test
    void requireEnabledThrowsTheLocalizedKey() {
        when(settings.revision()).thenReturn(1L);
        when(settings.findBoolean(SystemSettingKey.AI_ENABLED)).thenReturn(Optional.of(false));

        assertThatThrownBy(new AiAvailability(settings, true, 5_000L)::requireEnabled)
                .isInstanceOf(AiDisabledException.class)
                .hasMessage("error.ai.disabled");
    }
}
