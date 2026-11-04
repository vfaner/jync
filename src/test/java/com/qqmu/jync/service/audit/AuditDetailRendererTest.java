package com.qqmu.jync.service.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.MessageSource;

import com.qqmu.jync.util.AuditOrigin;

/**
 * Turns the stored {@code {message.key}|arg} audit details into localized text, while rows
 * written before the convention keep rendering as their raw text.
 */
class AuditDetailRendererTest {

    private final Map<String, String> bundle = new HashMap<>();
    private AuditDetailRenderer renderer;

    @BeforeEach
    void setUp() {
        MessageSource source = new MessageSource() {
            @Override
            public String getMessage(String code, Object[] args, String defaultMessage,
                                     Locale locale) {
                return bundle.containsKey(code) ? bundle.get(code) : defaultMessage;
            }

            @Override
            public String getMessage(String code, Object[] args, Locale locale) {
                if (!bundle.containsKey(code)) {
                    throw new NoSuchMessageException(code);
                }
                return bundle.get(code);
            }

            @Override
            public String getMessage(org.springframework.context.MessageSourceResolvable r,
                                     Locale locale) {
                return r.getDefaultMessage();
            }
        };
        renderer = new AuditDetailRenderer(source);
    }

    @Test
    void legacyRowsRenderAsRawText() {
        assertThat(renderer.render("Signed in from 0:0:0:0:0:0:0:1", Locale.ENGLISH))
                .isEqualTo("Signed in from 0:0:0:0:0:0:0:1");
        assertThat(renderer.render(null, Locale.ENGLISH)).isEqualTo("");
    }

    @Test
    void structuredRowsSubstitutePlainArgs() {
        bundle.put("audit.detail.projectSave", "{0} project '{1}'");
        String stored = AuditDetail.of("audit.detail.projectSave", "Created", "O'Brien's team");
        // Apostrophes are ordinary here (no MessageFormat) and placeholders still resolve.
        assertThat(renderer.render(stored, Locale.ENGLISH))
                .isEqualTo("Created project 'O'Brien's team'");
    }

    @Test
    void sentinelArgsResolveToTheirOwnMessageKeys() {
        bundle.put("audit.detail.login", "Signed in from {0}");
        bundle.put("audit.origin.local", "this machine");
        bundle.put("audit.detail.dbSave", "{0} connection {1} ({2})");
        bundle.put("audit.value.created", "Created");
        bundle.put("db.type.POSTGRESQL", "PostgreSQL");

        assertThat(renderer.render(AuditDetail.of("audit.detail.login", AuditOrigin.LOCAL),
                Locale.ENGLISH)).isEqualTo("Signed in from this machine");
        assertThat(renderer.render(AuditDetail.of("audit.detail.dbSave", "@created",
                "warehouse", "@type:POSTGRESQL"), Locale.ENGLISH))
                .isEqualTo("Created connection warehouse (PostgreSQL)");
    }

    @Test
    void reasonAndNestedMessageSentinelsResolve() {
        bundle.put("audit.detail.loginFailed", "{0} from {1}");
        bundle.put("audit.reason.bad-credentials", "Wrong password");
        bundle.put("audit.origin.local", "this machine");
        bundle.put("audit.detail.ticketFailed", "Ticket failure {0}: {1}");
        bundle.put("error.ticket.expired", "ticket expired");

        assertThat(renderer.render(AuditDetail.of("audit.detail.loginFailed",
                "@reason:bad-credentials", AuditOrigin.LOCAL), Locale.ENGLISH))
                .isEqualTo("Wrong password from this machine");
        assertThat(renderer.render(AuditDetail.of("audit.detail.ticketFailed",
                AuditOrigin.LOCAL, "@msg:error.ticket.expired"), Locale.ENGLISH))
                .isEqualTo("Ticket failure this machine: ticket expired");
    }

    @Test
    void pipesAndBackslashesInArgsSurviveRoundTrip() {
        bundle.put("audit.detail.x", "note: {0}");
        String stored = AuditDetail.of("audit.detail.x", "a|b\\c");
        assertThat(renderer.render(stored, Locale.ENGLISH)).isEqualTo("note: a|b\\c");
    }

    @Test
    void anUnknownMessageKeyFallsBackToTheStoredText() {
        String stored = AuditDetail.of("audit.detail.ghost", "x");
        assertThat(renderer.render(stored, Locale.ENGLISH)).isEqualTo(stored);
    }
}
