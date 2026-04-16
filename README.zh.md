# DroidVM

![DroidVM Logo](app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp)

[简体中文](README.zh.md) | [English](README.md)

基于 **高通 Gunyah Hypervisor** 的 Android 虚拟机管理器

在安卓设备上直接创建和管理轻量级虚拟机，享受接近原生的性能。

## 资源

- [仓库](https://github.com/Droid-VM/DroidVM)
- [Wiki](https://github.com/Droid-VM/DroidVM/wiki)
- [发布](https://github.com/Droid-VM/DroidVM/releases)

## 功能

- **多虚拟机后端支持**：[crosvm](https://crosvm.dev/) 和 [QEMU](https://www.qemu.org/)
- **UEFI 启动** Linux 或 Windows
- **GPU 加速**：VirGL、GfxStream 和 2D 软件渲染
- 内置 **VNC 客户端**，提供图形化显示访问
- **外接屏幕投屏**：通过 Android Presentation API 将虚拟机画面投射到外接屏幕（USB Type-C、Miracast 等），手机端作为触控板控制器
- **终端控制台**：完整的 ANSI/xterm 终端模拟（基于 [Termux 终端库](https://github.com/termux/termux-app)）
- 创建和编辑多种格式的磁盘镜像：**raw**、**qcow2** 等
- 磁盘操作：**调整大小**、**转换格式**、**克隆**、**优化** 和 **删除**
- **磁盘/光盘镜像下载** 和 **LXC 镜像导入** 支持
- **虚拟桥接网络**，支持 NAT、DHCP、STP 以及 IPv4/IPv6
- 通过 VirtFS (9p) 实现宿主机与虚拟机之间的 **共享目录**
- **Windows 支持**，搭配修改版 VirtIO 驱动
- **Linux Agent 操作**：修改密码等

## 环境要求

- **Android 13**（API 33）或更高版本
- **Root 权限**（Magisk、KernelSU、APatch 或类似工具）
- **高通骁龙 8 Gen 3**（SM8650）或更新的 SoC，推荐 **高通骁龙 8 Elite**（SM8750），需启用 Gunyah Hypervisor
- 已启用 **虚拟化** 支持（/dev/gunyah 或 /dev/kvm）

## 构建

### 前置条件

- [Android Studio](https://developer.android.com/studio)，SDK 36
- NDK，CMake 3.22.1+
- JDK 11+

### 构建步骤

```bash
git clone https://github.com/Droid-VM/DroidVM.git
cd DroidVM
git submodule update --init --recursive
./gradlew assembleRelease
```

构建产物位于 `app/release/`。

## 许可证

本项目基于 **GNU 通用公共许可证 v3.0** 发布。详见 [LICENSE.txt](LICENSE.txt)。

