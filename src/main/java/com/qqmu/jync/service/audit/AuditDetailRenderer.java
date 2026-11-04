package com.qqmu.jync.service.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import com.qqmu.jync.util.AuditOrigin;

/**
 * Localizes a stored audit detail for the viewer's language.
 *
 * <p>Rows written in the structured form ({@code {message.key}|arg|...}) are resolved against
 * the i18n bundles; argument sentinels such as {@code @local} or {@code @type:POSTGRESQL} are
 * themselves replaced by their own localized labels. Rows written before the convention — and
 * any row whose key is missing from the bundles — render verbatim, so the append-only history
 * never degrades to blanks.
 *
 * <p>Placeholder substitution is a literal {@code {n}} replace, not MessageFormat: audit
 * arguments routinely contain apostrophes (a connection named "Bob's DB") which
 * MessageFormat treats as quoting syntax.
 */
@Component
public class AuditDetailRenderer {

    private static final Pattern STORED =
            Pattern.compile("^\\{([a-zA-Z][a-zA-Z0-9.]*)}(?:\\|(.*))?$", Pattern.DOTALL);

    private final MessageSource messages;

    public AuditDetailRenderer(MessageSource messages) {
        this.messages = messages;
    }

    public String render(String stored, Locale locale) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        Matcher matcher = STORED.matcher(stored);
        if (!matcher.matches()) {
            return stored;
        }
        String key = matcher.group(1);
        String template = messages.getMessage(key, null, null, locale);
        if (template == null) {
            return stored;
        }
        List<String> args = parseArgs(matcher.group(2));
        String result = template;
        for (int i = 0; i < args.size(); i++) {
            result = result.replace("{" + i + "}", resolveArg(args.get(i), locale));
        }
        return result;
    }

    private List<String> parseArgs(String encoded) {
        List<String> args = new ArrayList<>();
        if (encoded == null) {
            return args;
        }
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '|') {
                args.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (escaped) {
            // Trailing backslash could only come from our own encoder, which never emits one;
            // keep the text intact rather than dropping it.
            current.append('\\');
        }
        args.add(current.toString());
        return args;
    }

    private String resolveArg(String arg, Locale locale) {
        if (AuditOrigin.LOCAL.equals(arg)) {
            return label("audit.origin.local", locale, arg);
        }
        if (arg.startsWith("@reason:")) {
            return label("audit.reason." + arg.substring("@reason:".length()), locale, arg);
        }
        if (arg.startsWith("@msg:")) {
            return label(arg.substring("@msg:".length()), locale, arg);
        }
        if (arg.startsWith("@type:")) {
            return label("db.type." + arg.substring("@type:".length()), locale, arg);
        }
        if (arg.startsWith("@kind:")) {
            return label("migration.kind." + arg.substring("@kind:".length()), locale, arg);
        }
        if (arg.startsWith("@strategy:")) {
            return label("audit.strategy." + arg.substring("@strategy:".length()), locale, arg);
        }
        if (arg.startsWith("@method:")) {
            return label("audit.method." + arg.substring("@method:".length()).toLowerCase(Locale.ROOT),
                    locale, arg);
        }
        if (arg.startsWith("@scope:project:")) {
            String template = label("audit.changelog.scopeProject", locale, arg);
            return template.replace("{0}", arg.substring("@scope:project:".length()));
        }
        if ("@scope:all".equals(arg)) {
            return label("audit.changelog.scopeAll", locale, arg);
        }
        if (arg.startsWith("@rebaseline:")) {
            return label("audit.rebaseline." + arg.substring("@rebaseline:".length()), locale, arg);
        }
        if ("@failedOnly:true".equals(arg)) {
            return label("audit.changelog.failed", locale, arg);
        }
        if ("@failedOnly:false".equals(arg) || "@empty".equals(arg)) {
            return "";
        }
        if ("@created".equals(arg)) {
            return label("audit.value.created", locale, arg);
        }
        if ("@updated".equals(arg)) {
            return label("audit.value.updated", locale, arg);
        }
        return arg;
    }

    private String label(String key, Locale locale, String fallback) {
        return messages.getMessage(key, null, fallback, locale);
    }
}
