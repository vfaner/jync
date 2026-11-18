package com.qqmu.jync.service.auth;

/**
 * Reference checks for the connection-like ids a form submits.
 *
 * <p>Same rule as editing, different error: a form reference is a user input mistake the page
 * should explain, so it is an {@link IllegalArgumentException} carrying
 * {@code error.ownership.reference:<name>} (the layout resolves the key prefix and appends the
 * name). Missing ids are not this helper's business — the existing not-found checks stay first.
 */
public final class Ownership {

    private Ownership() {
    }

    public static void requireReferenceable(String ownerUsername, String displayName,
                                            AccessGuard actor) {
        if (!actor.canEdit(ownerUsername)) {
            throw new IllegalArgumentException(
                    "error.ownership.reference:" + (displayName == null ? "" : displayName));
        }
    }
}
