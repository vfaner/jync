package com.qqmu.jync.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.AiProtocol;
import com.qqmu.jync.model.AiProvider;
import com.qqmu.jync.model.DatabaseType;

/**
 * Reply parsing and prompt construction for {@link AiSqlAssistant}.
 *
 * <p>Every test here goes through a stub client, so nothing reaches the network. The point is
 * not whether a real model answers well — that cannot be asserted — but whether this class
 * survives the ways a model answers badly. A reply wrapped in a markdown fence, a reply that
 * ignores the JSON contract entirely, a reply truncated mid-fence by the token budget: each of
 * those would otherwise put garbage in the reviewer's editor or throw where a message belongs.
 */
class AiSqlAssistantTest {

    /** Returns a canned reply and records what it was asked. */
    private static class StubClient extends AiChatClient {
        private String reply = "CREATE PROCEDURE x() BEGIN END";
        private boolean fail;
        private String failMessage = "HTTP 401";

        private String lastSystem;
        private String lastUser;
        private int lastMaxTokens;
        private int calls;

        @Override
        public ChatResult complete(AiProvider provider, String apiKey, String system, String user,
                                   int maxTokens) {
            this.calls++;
            this.lastSystem = system;
            this.lastUser = user;
            this.lastMaxTokens = maxTokens;
            return fail
                    ? ChatResult.failure(failMessage, "http://stub", provider.getModel(), 5)
                    : ChatResult.success(reply, "served-model", "http://stub",
                            provider.getModel(), 5);
        }
    }

    private static final String ORACLE_BODY = """
            CREATE OR REPLACE PROCEDURE GET_TOTAL(p_id IN NUMBER) IS
            BEGIN
              SELECT NVL(SUM(amount), 0) INTO v_total FROM orders WHERE id = p_id;
            END;
            """;

    private StubClient client;
    private AiProviderService providerService;
    private AiSqlAssistant assistant;
    private AiProvider provider;

    @BeforeEach
    void setUp() {
        client = new StubClient();
        providerService = mock(AiProviderService.class);
        assistant = new AiSqlAssistant(providerService, client);

        provider = new AiProvider();
        provider.setId(1L);
        provider.setName("p");
        provider.setProtocol(AiProtocol.OPENAI);
        provider.setBaseUrl("http://stub");
        provider.setModel("some-model");
        provider.setMaxTokens(2048);

        when(providerService.activeProvider()).thenReturn(Optional.of(provider));
        when(providerService.decryptKey(provider)).thenReturn("sk-plain");
    }

    private AiSqlAssistant.Candidate draft() {
        return assistant.draft("PROCEDURE", "GET_TOTAL", ORACLE_BODY, null,
                DatabaseType.ORACLE, DatabaseType.MYSQL);
    }

    @Test
    @DisplayName("both products, the object name and the source body reach the prompt")
    void thePromptNamesBothProductsAndCarriesTheSource() {
        draft();

        assertThat(client.lastUser)
                .contains("Oracle")
                .contains("MySQL")
                .contains("GET_TOTAL")
                .contains("SELECT NVL(SUM(amount), 0)");
        // Family drives the syntax, so the model is told it rather than left to infer it from
        // the product name -- 达梦 and Oracle need the same answer.
        assertThat(client.lastUser).contains("ORACLE").contains("MYSQL");
    }

    @Test
    @DisplayName("the system prompt forbids inventing schema and demands the statement alone")
    void theSystemPromptCarriesTheTwoRulesThatMatter() {
        draft();

        // "Never invent" is why the workflow is trustworthy: if a refactor drops it the feature
        // still "works" and silently becomes much more dangerous. "Nothing else" is what keeps
        // the reply usable verbatim, with no wrapper to parse or to leak into the editor.
        assertThat(client.lastSystem).contains("Never invent");
        assertThat(client.lastSystem).contains("nothing else");
    }

    @Test
    @DisplayName("the mechanical attempt is offered as a starting point when it differs")
    void theMechanicalAttemptIsIncluded() {
        assistant.draft("PROCEDURE", "GET_TOTAL", ORACLE_BODY,
                "CREATE PROCEDURE GET_TOTAL(p_id DECIMAL) BEGIN SELECT IFNULL(SUM(amount),0); END",
                DatabaseType.ORACLE, DatabaseType.MYSQL);

        assertThat(client.lastUser).contains("IFNULL").contains("starting point");
    }

    @Test
    @DisplayName("a mechanical attempt identical to the source is not pasted in twice")
    void anIdenticalMechanicalAttemptIsNotRepeated() {
        assistant.draft("PROCEDURE", "GET_TOTAL", ORACLE_BODY, ORACLE_BODY,
                DatabaseType.ORACLE, DatabaseType.DM);

        // Same-family conversion is a passthrough. Sending the same text twice wastes budget
        // and invites the model to hunt for a difference that is not there.
        assertThat(client.lastUser).doesNotContain("starting point");
    }

    @Test
    @DisplayName("a plain SQL reply is used verbatim")
    void aPlainSqlReplyIsUsedVerbatim() {
        client.reply = "CREATE PROCEDURE p() BEGIN SELECT 1; END";
        AiSqlAssistant.Candidate candidate = draft();

        assertThat(candidate.isSuccess()).isTrue();
        assertThat(candidate.getSql()).isEqualTo("CREATE PROCEDURE p() BEGIN SELECT 1; END");
        assertThat(candidate.getModel()).isEqualTo("served-model");
    }

    @Test
    @DisplayName("a reply wrapped in a markdown fence is unwrapped")
    void aFencedReplyIsUnwrapped() {
        // The prompt forbids the fence; models emit one anyway often enough that stripping it
        // is part of the contract rather than a workaround.
        client.reply = """
                ```sql
                CREATE PROCEDURE p() BEGIN SELECT 1; END
                ```
                """;
        AiSqlAssistant.Candidate candidate = draft();

        assertThat(candidate.isSuccess()).isTrue();
        assertThat(candidate.getSql()).isEqualTo("CREATE PROCEDURE p() BEGIN SELECT 1; END");
    }

    @Test
    @DisplayName("an unterminated fence still yields the SQL inside it")
    void anUnterminatedFenceStillYieldsSql() {
        // The token budget ran out before the closing fence. Discarding the reply would waste
        // a paid call whose useful part arrived.
        client.reply = "```sql\nCREATE PROCEDURE p() BEGIN SELECT 1;";
        AiSqlAssistant.Candidate candidate = draft();

        assertThat(candidate.isSuccess()).isTrue();
        assertThat(candidate.getSql()).isEqualTo("CREATE PROCEDURE p() BEGIN SELECT 1;");
    }

    @Test
    @DisplayName("a reply with nothing in it means the model declined")
    void anEmptyReplyAfterStrippingMeansDeclined() {
        // Rule 4: an impossible conversion is answered with nothing. That must read as a
        // considered "no", not as a transport failure the user should retry.
        client.reply = "```sql\n```";
        AiSqlAssistant.Candidate candidate = draft();

        assertThat(candidate.isSuccess()).isFalse();
        assertThat(candidate.getMessage()).isEqualTo("error.ai.declined");
        assertThat(candidate.getSql()).isEmpty();
    }

    @Test
    @DisplayName("an empty reply is a failure, not an empty candidate")
    void anEmptyReplyIsAFailure() {
        client.reply = "";
        AiSqlAssistant.Candidate candidate = draft();

        assertThat(candidate.isSuccess()).isFalse();
        assertThat(candidate.getMessage()).isEqualTo("error.ai.emptyReply");
        // An empty candidate would silently blank the reviewer's editor.
        assertThat(candidate.getSql()).isEmpty();
    }

    @Test
    @DisplayName("a transport failure carries the endpoint's own message through")
    void aTransportFailureIsReported() {
        client.fail = true;
        client.failMessage = "HTTP 429 — rate limit exceeded";
        AiSqlAssistant.Candidate candidate = draft();

        assertThat(candidate.isSuccess()).isFalse();
        assertThat(candidate.getMessage()).contains("429").contains("rate limit");
    }

    @Test
    @DisplayName("no enabled provider throws rather than looking like a conversion failure")
    void noEnabledProviderThrows() {
        when(providerService.activeProvider()).thenReturn(Optional.empty());

        // Reporting this as a Candidate failure would tell the user their procedure could not be
        // converted, when the truth is they never configured a model.
        assertThatThrownBy(this::draft)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.ai.notAvailable");
        assertThat(client.calls).isZero();
    }

    @Test
    @DisplayName("a blank source body is rejected without spending a call")
    void aBlankSourceBodyIsRejectedLocally() {
        AiSqlAssistant.Candidate candidate = assistant.draft("PROCEDURE", "P", "   ", null,
                DatabaseType.ORACLE, DatabaseType.MYSQL);

        assertThat(candidate.isSuccess()).isFalse();
        assertThat(candidate.getMessage()).isEqualTo("error.ai.noSourceBody");
        assertThat(client.calls).isZero();
    }

    @Test
    @DisplayName("the provider's Max Tokens is the reply budget")
    void theProvidersBudgetIsUsed() {
        provider.setMaxTokens(8192);
        draft();

        assertThat(client.lastMaxTokens).isEqualTo(8192);
    }

    @Test
    @DisplayName("an unset Max Tokens falls back to a usable default, not zero")
    void anUnsetBudgetFallsBack() {
        provider.setMaxTokens(null);
        draft();

        // A zero budget would make every request return an empty reply.
        assertThat(client.lastMaxTokens).isGreaterThan(0);
    }

    @Test
    @DisplayName("the decrypted key is what gets sent, never the stored ciphertext")
    void theDecryptedKeyIsUsed() {
        provider.setApiKey("enc:ciphertext");
        draft();

        // Asserted through the service seam: decryptKey is the only sanctioned path, and a
        // refactor that read getApiKey() directly would send the ciphertext as a bearer token.
        org.mockito.Mockito.verify(providerService).decryptKey(provider);
    }
}
