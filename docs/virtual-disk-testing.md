# Virtual-Disk & Loopback Testing Guide

Kufus uses two complementary test layers so storage code is thoroughly exercised before running destructive writes against real USB flash drives:

## 1. Automated Unprivileged Integration Tests (CI & Local)

No root privileges or kernel modules are needed to test the real native POSIX sysfs parser, symlink traversal, `/proc/mounts` / `/proc/swaps` unescaping, `dm-*` slave system-disk exclusion, and watcher lifecycle:

```bash
./gradlew :worker-cli:linuxX64Test :disk-core:jvmTest :shared:jvmTest
```

- [LinuxSysfsIntegrationTest.kt](../worker-cli/src/linuxTest/kotlin/io/github/drlacheheb/kufus/worker/LinuxSysfsIntegrationTest.kt) builds a real temporary `sysfs` tree using POSIX symlinks (`symlink()`, `realpath()`, `readlink()`) and invokes `listLinuxSysfsUsbDrives` directly through the compiled `linuxX64` C-interop layer.
- [MacosDiskMapperTest.kt](../disk-core/src/commonTest/kotlin/io/github/drlacheheb/kufus/disk/MacosDiskMapperTest.kt) and [WindowsDiskParserTest.kt](../disk-core/src/commonTest/kotlin/io/github/drlacheheb/kufus/disk/WindowsDiskParserTest.kt) validate external-USB filtering, APFS/virtual container exclusion, system mount protection, and Win32 argument quoting.

## 2. Kernel Virtual USB & Loopback Harness

The helper scripts under `scripts/virtual-disks/` let you attach safe scratch virtual disks on each host OS:

### Linux (`dummy_hcd` Virtual USB & `losetup` Loopback)

Use `dummy_hcd` + `g_mass_storage` to emulate a genuine kernel USB mass-storage device (`/dev/sdX`) with USB transport and serial attributes, or `loopback` to verify that plain `/dev/loopN` devices are excluded by `--list`:

```bash
# Attach a 128 MiB virtual USB flash drive and run kufus-worker --list
sudo ./scripts/virtual-disks/linux-virtual-usb.sh gadget up 128

# Detach and delete the virtual backing image
sudo ./scripts/virtual-disks/linux-virtual-usb.sh gadget down

# Attach / detach a raw loopback disk image (excluded from --list)
sudo ./scripts/virtual-disks/linux-virtual-usb.sh loopback up 128
sudo ./scripts/virtual-disks/linux-virtual-usb.sh loopback down
```

### macOS (`hdiutil`)

```bash
./scripts/virtual-disks/macos-virtual-disk.sh up 128
./scripts/virtual-disks/macos-virtual-disk.sh down
```

### Windows (`.vhdx`)

Run from an elevated PowerShell prompt (requires Hyper-V VHD cmdlets):

```powershell
.\scripts\virtual-disks\windows-virtual-disk.ps1 up 128
.\scripts\virtual-disks\windows-virtual-disk.ps1 down
```
