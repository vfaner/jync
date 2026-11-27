package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MetadataStoreFailureAdviceTest {

    private final MetadataStoreFailureAdvice advice = new MetadataStoreFailureAdvice();

    @Test
    void anH2ClosedFailureBecomesAStandaloneMaintenancePage() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/projects/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException(
                "metadata store failed", new RuntimeException("The database has been closed"));

        Object result = advice.metadataStoreUnavailable(failure, request, response);

        assertThat(result).isInstanceOf(org.springframework.web.servlet.ModelAndView.class);
        var view = (org.springframework.web.servlet.ModelAndView) result;
        assertThat(view.getViewName()).isEqualTo("error-metadata");
        assertThat(view.getStatus().value()).isEqualTo(503);
        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void anApiH2FailureReturnsJson503() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException(
                "metadata store failed", new RuntimeException("MVStoreException: writing failed"));

        assertThat(advice.metadataStoreUnavailable(failure, request, response)).isNull();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("error.metadata.unavailable");
    }

    @Test
    void aNonH2DatabaseFailureIsNotSwallowed() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/projects/1");
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException(
                "source database failed", new RuntimeException("Access denied for user"));

        assertThatThrownBy(() -> advice.metadataStoreUnavailable(
                failure, request, new MockHttpServletResponse()))
                .isSameAs(failure);
    }
}
