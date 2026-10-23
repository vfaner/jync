package com.qqmu.jync.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The pre-1.8 settings URL; kept so bookmarks and existing links keep working.
 *
 * <p>The page itself and its POST live at {@code /external-auth} now. Only a GET is mapped: a
 * POST to the old address would have to guess which of the renamed fields it was carrying.
 */
@Controller
public class OaAuthSettingsController {

    @GetMapping("/oa/settings")
    public String legacy() {
        return "redirect:/external-auth";
    }
}
