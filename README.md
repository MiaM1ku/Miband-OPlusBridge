# Miband-OPlusBridge

<img src="icon.png" width="96" alt="Miband-OPlusBridge">

把小米运动健康能用 **SPP** 连上的手环接到 ColorOS **设备空间** 和 **OPPO 健康**。

在设备空间里管理这只手环，查看连接与电量，并把步数、心率、睡眠写入 OPPO 健康；手机通知和来电可以转到手环，当前播放走 OPPO 健康自己的音乐控制。首页可打开「入睡后暂停音乐」：手环报入睡后暂停手机播放，醒来不自动续播。不回报实时入睡的手环，等睡眠记录同步后再暂停。手环查找手机会走 OPPO 健康自己的响铃，健康设备页也可以让这只手环响。健康首页若在导入完成前就打开，导入结束后会再读一次，不用先点「我的」。日常不必再打开小米运动健康。小米手环 11 已实机验证。手环 8 / 8 Pro / 9 / 9 Pro / 10 / 10 Pro 及 NFC、活力版只要官方重连是 SPP 或 GATT + WearAuthV2、且没有 OOB 额外认证，即可导入接管。SPP V2、SPP V1（8 Pro）和纯 BLE（fe95）在连接时按采集 profile 分支。纯 BLE 用已导入的密钥重连，系统蓝牙配对掉了也能连；SPP 仍要求系统配对还在。

接管期间连接留在前台服务里重连。健康还在、服务被系统杀掉时，健康读卡片或转发通知会把服务拉起来。控制中心可以添加「手环」，下拉或点一下会拉起连接，不会断开。后台限制拦住时用 root 启动。服务这边把健康进程绑回来，绑不上再用 root 拉健康。会话还没就绪的通知先留着，连上后续发；普通通知最多留 60 秒，来电 15 秒。健康不会把桥接界面从后台拉起来。通知只留一条折叠的「手环已连接」，同步文件时不再改它。


包名：`io.github.miam1ku.mibandoplusbridge`

点击链接加入群聊【Miband-OPlusBridge】：https://qm.qq.com/q/eEk3EsgSH0

## 安装

1. 安装 [LSPosed](https://github.com/LSPosed/LSPosed)（Zygisk）。
2. 安装本模块 APK，在 LSPosed 中启用，作用域勾选：
   - `com.heytap.mydevices`（设备空间）
   - `com.heytap.health`（OPPO 健康）
   - `com.mi.health`（导入绑定时需要）
   - `com.coloros.alarmclock`（时钟）
3. 在 KernelSU 里对本应用授权。
4. 打开「小米手环桥接」：检查 Root → 导入已配对手环 → 添加到健康。导入时会记录连接参数；若首页提示「连接参数丢失」，先恢复官方管理，再在小米运动健康中重连一次手环。
5. 强停「设备空间」和「OPPO 健康」后再打开。
旧包名 `io.github.oplusband.bridge` 与本包不能共存数据。换包后需重新导入。

## 协议测试

首页「常用」→ **协议测试**：

- 设备信息（名称、型号、固件、电量、连接；MAC 只显示末位）
- 测试通知 / 撤回
- 测试来电 / 结束来电
- 同步免打扰（同步开关 + 当前状态）
- 测试闹钟响铃 / 关闭 / 稍后响
- 测试查找手机 / 停止查找（OPPO 健康自己的响铃）
- 发送测试音乐
- 检查更新（GitHub Releases）

手环需已连接。

首页「常用」→ **发送调试日志到 QQ**：生成本机连接日志并交给 QQ。日志只有阶段、型号、队列和帧类型，不含 token、MAC 或 nonce。

## 发布

打 tag 后 GitHub Actions 会构建，并把同一个 `app-release.apk` 发到两个仓库：

```
git tag 32-1.2.9
git push origin 32-1.2.9
```

- 源仓库：https://github.com/MiaM1ku/Miband-OPlusBridge
- LSPosed 仓库：https://github.com/Xposed-Modules-Repo/io.github.miam1ku.mibandoplusbridge

tag 格式：`{versionCode}-{versionName}`。`workflow_dispatch` 只更新源仓库，不镜像。

仓库 Secrets：

- `OPLUSBAND_KEYSTORE_BASE64`
- `OPLUSBAND_STORE_PASSWORD`
- `OPLUSBAND_KEY_ALIAS`
- `OPLUSBAND_KEY_PASSWORD`
- `XPOSED_REPO_PAT`：对 `Xposed-Modules-Repo/io.github.miam1ku.mibandoplusbridge` 有 Contents 写权限的 PAT。`GITHUB_TOKEN` 不能推另一个仓库。缺这个 secret 时，tag 发布会在源 Release 之后失败。

## 鸣谢

协议对照用了这些项目：

- [AstroBox-NG](https://github.com/AstralSightStudios/AstroBox-NG)（AstralSightStudios）
- [Gadgetbridge](https://gadgetbridge.org/)

## 许可证

AGPL-3.0-or-later。见 `LICENSE`。
