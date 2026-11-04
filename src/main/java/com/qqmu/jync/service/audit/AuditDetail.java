package com.qqmu.jync.service.audit;

/**
 * Encodes an audit detail as a language-neutral message key plus arguments, e.g.
 * {@code {audit.detail.login}|@local}. {@link AuditDetailRenderer} localizes it at render
 * time, so the same stored row reads Chinese or English depending on the viewer's language.
 *
 * <p>Arguments are separated by {@code |} and that character, together with {@code \}, is
 * escaped inside values — names may contain either.
 */
public final class AuditDetail {

    private AuditDetail() {
    }

    public static String of(String messageKey, String... args) {
        StringBuilder sb = new StringBuilder("{").append(messageKey).append('}');
        for (String raw : args) {
            sb.append('|');
            String arg = raw == null ? "" : raw;
            for (int i = 0; i < arg.length(); i++) {
                char c = arg.charAt(i);
                if (c == '\\' || c == '|') {
                    sb.append('\\');
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
