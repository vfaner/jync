package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.auth.ExternalSessionSignIn;
import com.qqmu.jync.service.auth.oa.OaAuthConfigService;
import com.qqmu.jync.service.auth.oa.OaAuthResult;
import com.qqmu.jync.service.auth.oa.TicketValidationClient;

@WebMvcTest(SsoEntryController.class)
@AutoConfigureMockMvc(addFilters = false)
class SsoEntryControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private OaAuthConfigService configService;
    @MockBean
    private TicketValidationClient ticketClient;
    @MockBean
    private ExternalSessionSignIn signIn;
    @MockBean
    private AdminAuditService auditService;

    private OaAuthConfig config;

    @BeforeEach
    void setUp() {
        config = new OaAuthConfig();
        config.setEnabled(true);
        config.setMode(OaAuthConfig.Mode.TICKET);
        config.setValidateUrl("https://sso.example.com/validate");
        config.setUserField("data.username");
        when(configService.load()).thenReturn(config);
        when(signIn.savedTarget(any(), any())).thenReturn("/projects");
    }

    private MockHttpServletResponse enter(String ticket) throws Exception {
        return mvc.perform(get("/sso/entry").param("ticket", ticket))
                .andReturn().getResponse();
    }

    @Test
    void anotherModeTurnsTheEntryPointAway() throws Exception {
        config.setMode(OaAuthConfig.Mode.PASSWORD);

        assertThat(enter("t-1").getRedirectedUrl()).isEqualTo("/login");
        verifyNoInteractions(ticketClient);
    }

    @Test
    void aMissingTicketIsReportedOnTheLoginPage() throws Exception {
        assertThat(enter(" ").getRedirectedUrl()).isEqualTo("/login?ticketMissing");
        verifyNoInteractions(ticketClient);
    }

    @Test
    void aFailedTicketIsAuditedWithoutTheTicket() throws Exception {
        when(ticketClient.validate(any(), eq("t-1")))
                .thenReturn(OaAuthResult.failure("error.oa.rejected"));

        assertThat(enter("t-1").getRedirectedUrl()).isEqualTo("/login?ticketError");

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(AuditAction.LOGIN_FAILED), detail.capture(), eq("sso"));
        assertThat(detail.getValue()).doesNotContain("t-1");
    }

    @Test
    void aValidTicketSignsInAndReturnsToTheSavedRequest() throws Exception {
        when(ticketClient.validate(any(), eq("t-1")))
                .thenReturn(OaAuthResult.success("alice", false));

        assertThat(enter("t-1").getRedirectedUrl()).isEqualTo("/projects");

        verify(signIn).signIn(any(), any(), eq("alice"), eq(UserRole.VIEWER), eq("TICKET"));
    }

    @Test
    void theAdminListAppliesToTicketUsersToo() throws Exception {
        config.setAdminUsers("root");
        when(ticketClient.validate(any(), eq("t-1")))
                .thenReturn(OaAuthResult.success("root", false));

        enter("t-1");

        verify(signIn).signIn(any(), any(), eq("root"), eq(UserRole.ADMIN), eq("TICKET"));
    }

    @Test
    void aNextParameterIsIgnored() throws Exception {
        when(ticketClient.validate(any(), eq("t-1")))
                .thenReturn(OaAuthResult.success("alice", false));

        String target = mvc.perform(get("/sso/entry")
                        .param("ticket", "t-1")
                        .param("next", "https://evil.example/steal"))
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(target).isEqualTo("/projects");
    }
}
