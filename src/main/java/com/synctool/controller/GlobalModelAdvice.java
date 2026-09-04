package com.synctool.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.synctool.config.SyncProperties;

/**
 * Model attributes the shared layout needs on every page.
 *
 * <p>The REST endpoints also match this advice, but a model attribute is inert for a
 * {@code @ResponseBody} handler, so there is nothing to exclude.
 */
@ControllerAdvice
public class GlobalModelAdvice {

    private final String githubUrl;
    private final SyncProperties properties;

    public GlobalModelAdvice(@Value("${app.github-url}") String githubUrl,
                             SyncProperties properties) {
        this.githubUrl = githubUrl;
        this.properties = properties;
    }

    /** Repository link used by the nav icon and the donate dialog. */
    @ModelAttribute("githubUrl")
    public String githubUrl() {
        return githubUrl;
    }

    /**
     * Whether the AI menu is shown at all.
     *
     * <p>Read from configuration on every request rather than cached, so an air-gapped
     * deployment can hide the feature without a code change.
     */
    @ModelAttribute("aiEnabled")
    public boolean aiEnabled() {
        return properties.getAi().isEnabled();
    }
}
