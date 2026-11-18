package com.qqmu.jync.service.ai.support;

import java.util.List;

/** The five things an answer may emit; the controller maps them to SSE events. */
public interface SupportSink {

    void delta(String text);

    void tool(String name, String labelKey);

    void draft(DraftView draft);

    void error(String messageKey);

    void done();

    record DraftView(String token, String title, String summary, List<Field> fields) {
    }

    record Field(String labelKey, String value) {
    }
}
