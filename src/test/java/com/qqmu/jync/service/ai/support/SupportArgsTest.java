package com.qqmu.jync.service.ai.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** The shared control-action parse: one failure mode the model can understand. */
class SupportArgsTest {

    private static final Set<SupportActionStore.Action> ALLOWED =
            Set.of(SupportActionStore.Action.START, SupportActionStore.Action.STOP);

    @Test
    void anAllowedActionParses() {
        assertThat(SupportArgs.action("START", ALLOWED))
                .isEqualTo(SupportActionStore.Action.START);
    }

    @Test
    void anActionOutsideTheAllowedSetIsBadArgs() {
        assertThatThrownBy(() -> SupportArgs.action("RUN_NOW", ALLOWED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(SupportArgs.BAD_ARGS);
    }

    @Test
    void anUnknownActionNameIsBadArgs() {
        assertThatThrownBy(() -> SupportArgs.action("NOT_AN_ACTION", ALLOWED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(SupportArgs.BAD_ARGS);
    }
}
