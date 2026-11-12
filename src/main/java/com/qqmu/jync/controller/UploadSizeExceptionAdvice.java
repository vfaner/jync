package com.qqmu.jync.controller;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Explains a multipart body rejected by the container for exceeding Spring's size limit.
 *
 * <p>Tomcat refuses an oversized body while parsing the request, before Spring picks a
 * handler, so a per-controller {@code @ExceptionHandler} never runs for this case. A single
 * global handler routes by path instead, so each form still names its own file field.
 *
 * <p>The TLS stage endpoint is answered with JSON rather than a redirect: its caller is
 * {@code fetch()}, which would follow the redirect into the HTML page, fail to parse it, and
 * report "HTTP 200" instead of naming the oversized file.
 */
@ControllerAdvice
public class UploadSizeExceptionAdvice {

    private static final String PAGE = "redirect:/system-config";
    private static final String TLS_STAGE_URI = "/system-config/tls/stage";

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooLarge(HttpServletRequest request, HttpServletResponse response,
                           RedirectAttributes flash) throws IOException {
        String uri = request == null ? null : request.getRequestURI();
        boolean tls = uri != null && (uri.equals("/system-config/tls")
                || uri.startsWith("/system-config/tls/"));
        String key = tls ? "error.tls.fileTooLarge" : "error.config.import.fileTooLarge";

        if (uri != null && uri.endsWith(TLS_STAGE_URI)) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json;charset=UTF-8");
            // A fixed key from the two above; no user input is written into the body.
            response.getWriter().write("{\"success\":false,\"error\":\"" + key + "\"}");
            // Null means the response is already committed; no view is rendered.
            return null;
        }

        flash.addFlashAttribute("error", key);
        return PAGE;
    }
}
