package com.qqmu.jync.service.auth;

import javax.servlet.http.HttpServletRequest;

/** Request-path helpers shared by the auth filters. */
final class RequestPaths {

    private RequestPaths() {
    }

    /**
     * The path below the context path, taken from the request URI rather than
     * {@code getServletPath()}: the mock request used by the view tests leaves the servlet path
     * empty, so a check keyed on it would silently never run there.
     */
    static String withinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }
}
