package com.synctool.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Model attributes the shared layout needs on every page.
 *
 * <p>The REST endpoints also match this advice, but a model attribute is inert for a
 * {@code @ResponseBody} handler, so there is nothing to exclude.
 */
@ControllerAdvice
public class GlobalModelAdvice {

    private final String githubUrl;

    public GlobalModelAdvice(@Value("${app.github-url}") String githubUrl) {
        this.githubUrl = githubUrl;
    }

    /** Repository link used by the nav icon and the donate dialog. */
    @ModelAttribute("githubUrl")
    public String githubUrl() {
        return githubUrl;
    }
}
