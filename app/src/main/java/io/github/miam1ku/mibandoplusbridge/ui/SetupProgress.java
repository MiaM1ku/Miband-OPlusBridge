// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.ui;

/** First-run step from root, binding, SPP profile, registration, and account stores. */
public final class SetupProgress {
    public enum Step { ROOT, IMPORT, PROFILE, ADD, DONE }

    public final boolean hasRoot;
    public final boolean hasBinding;
    public final boolean hasProfile;
    public final boolean registered;
    public final boolean nativeOwned;
    public final boolean accountConfirmed;

    public SetupProgress(boolean hasRoot, boolean hasBinding, boolean hasProfile,
                         boolean registered, boolean nativeOwned, boolean accountConfirmed) {
        this.hasRoot = hasRoot;
        this.hasBinding = hasBinding;
        this.hasProfile = hasProfile;
        this.registered = registered;
        this.nativeOwned = nativeOwned;
        this.accountConfirmed = accountConfirmed;
    }

    public Step current() {
        if (!hasRoot) return Step.ROOT;
        if (!hasBinding) return Step.IMPORT;
        if (!registered || !nativeOwned) return Step.ADD;
        return Step.DONE;
    }

    public boolean showChecklist() {
        return current() != Step.DONE;
    }

    public String primaryLabel() {
        return Copy.setupLabel(current());
    }

    public String primaryHint() {
        return Copy.setupHint(current());
    }

    /** 1-based position among the three visible steps; DONE reports 3. */
    public int visibleIndex() {
        return switch (current()) {
            case ROOT -> 1;
            case IMPORT -> 2;
            default -> 3;
        };
    }
}
