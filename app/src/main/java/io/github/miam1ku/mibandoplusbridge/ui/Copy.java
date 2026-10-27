// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.ui;

/**
 * Every user-facing sentence that depends on a status code.
 * Style: say what the state is, then what to tap next. {@code CopyStyleTest} checks the wording.
 */
public final class Copy {
    private Copy() {}

    // ---- setup ----

    public static String setupLabel(SetupProgress.Step step) {
        return switch (step) {
            case ROOT -> "检查 Root";
            case IMPORT -> "导入绑定";
            case PROFILE -> "采集连接参数";
            case ADD -> "添加到健康";
            case DONE -> "立即同步";
        };
    }

    public static String setupHint(SetupProgress.Step step) {
        return switch (step) {
            case ROOT -> "在 KernelSU 中为「小米手环桥接」打开超级用户，回到这里点「检查 Root」。";
            case IMPORT -> "从小米运动健康的本地数据库读取手环密钥，全程在本机完成。";
            case PROFILE -> "绑定已保存，点「添加到健康」即可连接。";
            case ADD -> "小米运动健康会暂停，由桥接接管手环。手环离线时也可以先添加。";
            case DONE -> "";
        };
    }

    public static final String SETUP_PREREQ = "1. 在系统蓝牙里配对手环。\n"
            + "2. 在 LSPosed 启用本模块，勾选小米运动健康、OPPO 健康、设备空间，然后强停这三个应用一次。\n"
            + "3. 在 KernelSU 中允许本应用使用超级用户。";

    public static final String GUIDE = "1. 检查 Root：在 KernelSU 中允许本应用，回来点「检查 Root」。\n"
            + "2. 导入绑定：从小米运动健康的本地数据库读取手环密钥。\n"
            + "3. 添加到健康：桥接接管手环，设备空间和 OPPO 健康里随即出现这只手环。\n\n"
            + "日常使用：在控制中心添加「手环」磁贴，下拉或点按即可连接。\n\n"
            + "开始前准备\n" + SETUP_PREREQ;

    // ---- connection ----

    /** Short reason shown next to "等待连接". */
    public static String offlineReason(String liveStatus) {
        return switch (liveStatus == null ? "" : liveStatus) {
            case "OBSERVED_PROFILE_REQUIRED" -> "到高级设置采集连接参数后即可连接";
            case "NATIVE_OWNERSHIP_REQUIRED" -> "点「立即同步」重新接管";
            case "UNPROVISIONED", "BINDING_INCOMPLETE", "BINDING_ADDRESS", "BINDING_REGION",
                    "TOKEN_ENCODING_UNSUPPORTED", "CREDENTIAL_REFRESH_REQUIRED" -> "重新导入绑定即可连接";
            case "BLUETOOTH_DISABLED" -> "打开蓝牙即可连接";
            default -> "手环靠近手机后会自动连接";
        };
    }

    /** Result line after add, reconnect, remove, or restore stops early. */
    public static String ownershipFailure(String code) {
        return switch (code == null ? "" : code) {
            case "USER_LOCKED" -> "解锁手机后再点一次。";
            case "UNPROVISIONED", "BINDING_INCOMPLETE" -> "到高级设置重新导入绑定，然后再添加。";
            case "BINDING_ADDRESS" -> "重新选择这只手环并导入绑定，蓝牙地址会按大写 MAC 保存。";
            case "BINDING_REGION" -> "在小米运动健康里打开这只手环，等它连上后再导入一次。";
            case "TOKEN_ENCODING_UNSUPPORTED" -> "让手环在小米运动健康里连上一次，再导入绑定。";
            case "OBSERVED_PROFILE_REQUIRED" -> "到高级设置点「采集连接参数」，在小米运动健康里重连一次手环。";
            case "FIRMWARE_OR_MODEL_UNSUPPORTED" -> "让手环在小米运动健康里连上一次，读到型号和固件后再添加。";
            case "OOB_BRANCH_UNSUPPORTED" -> "这只设备使用带外配对认证。桥接适配 SPP 和 BLE 的 WearAuthV2 认证。";
            case "PROTOCOL_VERSION_UNSUPPORTED", "SESSION_CONFIGURATION_UNSUPPORTED" ->
                    "桥接适配官方以 SPP 或 BLE 重连的手环。在小米运动健康里重连一次后再试。";
            case "DEVICE_IDENTITY_CHANGED" -> "这次导入的是另一只手环。请导入原来那只，或先移除再添加新手环。";
            case "BAND_STATE_STORAGE_FAILED", "OWNERSHIP_STORAGE_FAILED" -> "清理一些存储空间后再点一次。";
            case "ROOT_REQUIRED", "ROOT_ACTION_FAILED", "ROOT_ACTION_TIMEOUT" ->
                    "在 KernelSU 中确认已允许本应用，再点一次。";
            case "HOST_VERSION_UNSUPPORTED" -> "请把小米运动健康换到实测版本 3.59.1 后再试。";
            case "NEARBY_PERMISSION_REQUIRED" -> "授权附近设备后再点一次。";
            case "DEVICE_NOT_REGISTERED" -> "先点「添加到健康」。";
            case "DIAGNOSTIC_SOCKET_STILL_ACTIVE" -> "连接正在关闭，稍等几秒再恢复官方管理。";
            case "OWNERSHIP_RECOVERY_REQUIRED", "OFFICIAL_ALREADY_DISABLED" ->
                    "先恢复官方管理，确认小米运动健康已启用，再接管。";
            case "OWNERSHIP_ROLLBACK_FAILED" -> "小米运动健康可能仍在暂停，再点一次「恢复小米运动健康」。";
            default -> "这次操作中断了。再点一次；仍然中断时先恢复官方管理。";
        };
    }

    // ---- binding import ----

    public static String importResult(String code, String model, String productId, String firmware) {
        String detail = "\n型号：" + dash(model) + "\n产品：" + dash(productId)
                + "\n固件：" + (firmware == null || firmware.isBlank() ? "连接后读取" : firmware);
        return switch (code == null ? "" : code) {
            case "IMPORT_WINDOW_OPEN" -> "导入窗口已开启。在小米运动健康中打开所选设备，官方应用会重新读取绑定。";
            case "IMPORT_WINDOW_CLOSED" -> "导入和协议采集窗口都已关闭。";
            case "DIAGNOSTIC_WINDOW_OPEN" -> "连接参数采集已开启。只记录连接类型和协议版本。在官方应用中重连手环。";
            case "PROTOCOL_CAPTURE_OPEN" -> "协议消息采集已开启，120 秒后自动关闭。现在执行要测试的操作。";
            case "UNPROVISIONED" -> "等待导入绑定。";
            case "BINDING_SAVED" -> "绑定已保存。" + detail;
            case "BINDING_INCOMPLETE" -> "绑定已保存，点「添加到健康」即可连接。" + detail;
            case "NO_BAND_KEY" -> "先在小米运动健康里完成手环配对，再导入。";
            case "MI_DATABASE_MISSING" -> "安装并登录小米运动健康、配对手环后再导入。";
            case "MI_DATABASE_UNREADABLE" -> "设备数据库正被占用，稍等片刻再导入。";
            case "ROOT_ACTION_FAILED", "ROOT_ACTION_TIMEOUT" -> "在 KernelSU 中确认已允许本应用，再导入一次。";
            case "MULTIPLE_BANDS" -> "数据库里有多只手环，选一只导入。";
            case "USER_LOCKED" -> "解锁手机后再导入。";
            case "CREDENTIAL_STORAGE_FAILED" -> "保存中断了，再点一次「导入绑定」即可恢复。";
            default -> "这次操作中断了，再点一次。";
        };
    }

    // ---- health sync ----

    public static String healthHost(String status) {
        return "UNAVAILABLE".equals(status) ? "打开一次 OPPO 健康，健康记录会接着写入。" : "";
    }

    public static String healthCollection(String status) {
        return switch (status == null ? "" : status) {
            case "ACCOUNT_CONFIRMATION_REQUIRED" -> "健康记录写入当前 OHealth 账号，由 OHealth 同步到云端。";
            case "HEALTH_OUTBOX_FULL" -> "健康记录排队中，OHealth 处理完后会继续采集，记录都已保存。";
            case "HISTORY_FORMAT_UNSUPPORTED" -> "部分健康文件以原始格式保存，等待后续版本解析。";
            case "HISTORY_FILE_REJECTED" -> "一份健康文件需要重新获取，下次同步时自动完成。";
            case "HISTORY_STORAGE_FAILED", "HEALTH_STORAGE_UNAVAILABLE" -> "清理一些存储空间后，健康同步会继续。";
            case "HEALTH_REPLAY_PAUSED", "HISTORY_CONFIRMATION_PENDING" -> "健康同步稍后继续，已保存的记录都在。";
            default -> "";
        };
    }

    // ---- weather ----

    public static String weatherError(String code) {
        return switch (code == null ? "" : code) {
            case "WEATHER_CITY_CONFIRMATION_REQUIRED", "WEATHER_PREVIEW_CHANGED" -> "城市有变化，点「核对手环城市」重新关联。";
            case "WEATHER_CITY_SETUP_REQUIRED" -> "先在小米运动健康里给手环设置一个城市，再切回桥接。";
            case "WEATHER_CITY_LIST_INVALID" -> "在小米运动健康里检查手环的城市列表，再发送。";
            case "WEATHER_SOURCE_STALE", "WEATHER_FORECAST_STALE" -> "天气已过期，点「获取当前位置天气」刷新后再发送。";
            case "WEATHER_BAND_REJECTED" -> "手环这次保留了原有天气，健康连接照常运行，稍后再发送一次。";
            case "WEATHER_BUSY" -> "上一次天气任务还在进行，稍后再点。";
            case "WEATHER_DISCONNECTED", "LIVE_SESSION_UNAVAILABLE", "WEATHER_CLOSED", "NATIVE_OWNERSHIP_REQUIRED" ->
                    "回首页点「立即同步」，连上后再发送。";
            case "WEATHER_DATA_INVALID" -> "点「获取当前位置天气」换一份天气再发送。";
            default -> "天气发送中断，检查连接和天气源后再点一次。";
        };
    }

    public static String weatherSource(String status, boolean pending) {
        if (pending) return "正在等待 OHealth 更新天气";
        return switch (status == null ? "" : status) {
            case "" -> "";
            case "OHEALTH_LOCATION_PERMISSION_REQUIRED" -> "在系统设置里允许 OHealth 使用位置";
            case "OHEALTH_LOCATION_DISABLED" -> "打开系统定位后再获取";
            case "OHEALTH_LOCATION_UNAVAILABLE" -> "正在等待更准确的位置，稍后再获取";
            case "OHEALTH_WEATHER_CLOUD_UNAVAILABLE" -> "检查网络后再获取";
            case "OHEALTH_WEATHER_TIMEOUT" -> "天气处理较慢，打开一次 OHealth 再获取";
            default -> "打开一次 OHealth 再获取天气";
        };
    }

    // ---- shared helpers ----

    public static String dash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    public static String stageName(Integer stage) {
        if (stage == null) return "其他阶段";
        return switch (stage) {
            case 2 -> "深睡";
            case 3 -> "浅睡";
            case 4 -> "快速眼动（REM）";
            case 5 -> "清醒";
            case 6 -> "整段";
            default -> "其他阶段";
        };
    }

    /** Quick settings subtitle. */
    public static String tileSubtitle(boolean registered, boolean serviceUp, boolean connected, int battery) {
        if (!registered) return "在应用中添加";
        if (serviceUp && connected) return battery >= 0 && battery <= 100 ? "已连接 · " + battery + "%" : "已连接";
        if (serviceUp) return "正在连接";
        return "点按连接";
    }
}
