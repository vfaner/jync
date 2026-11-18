package com.qqmu.jync.service.ai;

/**
 * The AI feature is switched off — by the page switch or the deployment's hard off.
 *
 * <p>The message is the bundle key {@code error.ai.disabled}; the API layer answers 403 with
 * it so the page can localize the toast. Deliberately not an
 * {@link IllegalStateException}: the existing API handlers would otherwise turn it into a 409
 * or a 200 with a failure body, and "the feature is off" is neither a state conflict nor a
 * normal result.
 */
public class AiDisabledException extends RuntimeException {

    public AiDisabledException() {
        super("error.ai.disabled");
    }
}
