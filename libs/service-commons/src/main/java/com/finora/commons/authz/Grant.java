package com.finora.commons.authz;

import java.util.Set;

/** The outcome of a permission lookup. {@code scope} is meaningful only when {@code allowed}. */
public record Grant(boolean allowed, Scope scope, Set<Action> actions) {

    public static final Grant DENIED = new Grant(false, Scope.ALL, Set.of());
}
