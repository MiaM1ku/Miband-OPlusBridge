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
        if (!hasProfile) return Step.PROFILE;
        if (!registered || !nativeOwned) return Step.ADD;
        return Step.DONE;
    }

    public boolean showChecklist() {
        return current() != Step.DONE;
    }

    public String primaryLabel() {
        return switch (current()) {
            case ROOT -> "检查 Root";
            case IMPORT -> "导入绑定";
            case PROFILE -> "采集连接参数";
            case ADD -> "添加到健康";
            case DONE -> "立即同步";
        };
    }

    public String primaryHint() {
        return switch (current()) {
            case ROOT -> "打开 KernelSU，为本应用打开超级用户权限，返回后点「检查 Root」。";
            case IMPORT -> "选择已配对手环，打开小米运动健康点开该设备。120 秒内同时导入绑定并记录连接参数。";
            case PROFILE -> "打开小米运动健康并点开这只手环，让它重新连上。120 秒内记录 SPP 连接参数。";
            case ADD -> "将暂停小米运动健康并由本应用接管。手环不必在线。";
            case DONE -> "";
        };
    }
}
