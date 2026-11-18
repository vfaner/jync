package com.qqmu.jync.service.ai.support;

import java.util.List;

/** One question plus the browser's short conversation history. */
public record SupportChatRequest(String message, List<HistoryItem> history) {

    public record HistoryItem(String role, String text) {
    }
}
