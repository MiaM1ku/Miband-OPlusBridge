// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.ui;

/** First-run step from root, binding, registration, and account stores. */
public final class SetupProgress {
    public enum Step { ROOT, IMPORT, ADD, ACCOUNT, DONE }

    public final boolean hasRoot;
    public final boolean hasBinding;
    public final boolean registered;
    public final boolean accountConfirmed;

    public SetupProgress(boolean hasRoot, boolean hasBinding, boolean registered, boolean accountConfirmed) {
        this.hasRoot = hasRoot;
        this.hasBinding = hasBinding;
        this.registered = registered;
        this.accountConfirmed = accountConfirmed;
    }

    public Step current() {
        if (!hasRoot) return Step.ROOT;
        if (!hasBinding) return Step.IMPORT;
        if (!registered) return Step.ADD;
        if (!accountConfirmed) return Step.ACCOUNT;
        return Step.DONE;
    }

    public boolean showChecklist() {
        return current() != Step.DONE;
    }

    public String primaryLabel() {
        return switch (current()) {
            case ROOT -> "检查 Root";
            case IMPORT -> "导入绑定";
            case ADD -> "添加到健康";
            case ACCOUNT -> "确认健康账号";
            case DONE -> "立即同步";
        };
    }

    public String primaryHint() {
        return switch (current()) {
            case ROOT -> "请打开 KernelSU，为本应用打开超级用户权限，返回后点「检查 Root」。未授权前不会继续初始化。";
            case IMPORT -> "选择已配对手环，打开小米运动健康点开该设备。120 秒内有效。";
            case ADD -> "将暂停小米运动健康并由本应用接管。手环不必在线。";
            case ACCOUNT -> "打开 OHealth 后回到本页，确认要把健康记录导入的账号。";
            case DONE -> "";
        };
    }
}
