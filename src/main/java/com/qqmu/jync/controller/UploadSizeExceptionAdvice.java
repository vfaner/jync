package com.qqmu.jync.controller;

import javax.servlet.http.HttpServletRequest;

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
 */
@ControllerAdvice
public class UploadSizeExceptionAdvice {

    private static final String PAGE = "redirect:/system-config";

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooLarge(HttpServletRequest request, RedirectAttributes flash) {
        String uri = request == null ? null : request.getRequestURI();
        boolean tls = uri != null && (uri.equals("/system-config/tls")
                || uri.startsWith("/system-config/tls/"));
        flash.addFlashAttribute("error", tls
                ? "error.tls.fileTooLarge" : "error.config.import.fileTooLarge");
        return PAGE;
    }
}
