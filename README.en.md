# USB Multitool

[Русский](README.md) | **English**

[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](https://www.gnu.org/licenses/gpl-3.0.html)
![Android 9+](https://img.shields.io/badge/Android-9%2B-3DDC84)
![ABI: arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-lightgrey)
![Status: alpha](https://img.shields.io/badge/status-alpha-orange)

USB Multitool is an Android application for working with USB drives directly from a smartphone or tablet. It allows users to manage files and partitions, format drives, write disk images, install Ventoy, and check the condition of a drive. The application requires neither root access nor Android's system mounting: it communicates with the drive directly through the USB Host API.

> **Development status: alpha.** The application is at an early stage and has not yet been verified on a wide range of real USB drives. Write operations are irreversible, so during testing it is recommended to use an empty drive that holds no important data.

## Features

### Files

Browse the contents of each partition on a drive (FAT, exFAT, NTFS, EXT4), create folders, rename and delete items, and transfer files between the drive and the phone.

### Partitions

View the disk layout with an MBR or GPT partition table, create and delete partitions, format them as FAT, exFAT, NTFS, or EXT4, change names and labels, switch a partition's type (EFI or data), and set the boot flag. If the primary GPT is damaged, the application falls back to the backup copy and can restore the table. Changes are written in an order that ensures an interrupted operation never leaves the disk without an intact copy of the table.

### Image writing

Write disk images in `.iso` and `.img` formats, as well as compressed `.gz` and `.xz`, to a drive. Before writing, the image size and the drive boundaries are checked; decompression happens during the write, and the operation can be cancelled. By default, a verification read is performed after the write.

### Ventoy

Install Ventoy on a drive with a GPT partition table and update an existing installation while keeping the images stored on it. The files used come from Ventoy 1.1.18 bundled with the application, from the `/sdcard/ventoy` directory, or from the latest release on GitHub; checksums are verified in every case. Also available are Windows 11 installation settings (hardware check bypass, local account) and preparation of an `autounattend.xml` file; previous configurations are saved as backups before being replaced.

### Diagnostics

- application self-test and a check of the connected drive;
- read scan that pinpoints the location of errors;
- creation of a disk image with a map of unreadable sectors, and verified restoration from it;
- backup of the critical sectors of the GPT;
- zero-fill wipe of the drive;
- reading SMART data (read-only, where supported by the drive).

Architecture and implementation details are available in the [docs/](docs/README.md) directory.

## Requirements

- Android 9 (API 28) or later.
- A processor with the arm64-v8a architecture.
- USB Host (OTG) support and a suitable adapter.
- A USB mass-storage drive using the Bulk-Only Transport protocol. Drives that use UAS are not supported.

The sector size is detected automatically from the drive.

## Installation

The application is not distributed through Google Play or F-Droid. Signed builds are published in [Releases](../../releases) together with a SHA-256 checksum file.

If no suitable release is available yet, a debug build can be obtained from GitHub Actions: open the [`android-debug` workflow](https://github.com/SklUpp/usb-multitool/actions/workflows/android-debug.yml), run it manually (**Run workflow**), and download the `app-debug-apk` artifact. Such a build is signed with a debug key and is intended for testing.

Installing the APK requires allowing installation from unknown sources.

## Usage

1. Connect the drive through an OTG adapter and grant the application access to the USB device.
2. Choose the required section of the interface: Files, Ventoy, Partitions, or Diagnostics.
3. Before any operation that changes data, the application asks for confirmation showing the drive's model, serial number, and capacity. Check these details carefully: a wrongly selected drive will be overwritten irreversibly.

The interface language (Russian or English) is selected in the application settings.

## Data safety and privacy

The application asks for confirmation of every destructive operation and checks write boundaries before it begins. If an operation is interrupted after writing has started, the application reports a partial result rather than success or cancellation.

No telemetry is collected or transmitted. Network access is used only to check for and download Ventoy releases from GitHub; notifications and background operation are needed only while long-running operations are in progress.

## Known limitations

- Moving and resizing partitions that contain data is not supported.
- Ventoy installation is available only for GPT; drives with a 4096-byte sector size (4K native) are not supported by Ventoy.
- A formatting operation cannot be interrupted once it has started.
- An interrupted operation does not resume automatically after the drive is reconnected.
- UAS, root mode, FUSE, and loop devices are not supported, nor are F3 capacity testing, write-speed benchmarks, and images over HTTP(S).
- Automated checks do not cover work with real drives, booting from the created media, or installing operating systems.

## Compatibility and feedback

Compatibility with specific drive models, adapters, and devices has not yet been established. If you have tried the application, please report the result in [Issues](../../issues): include the model of your phone, drive, and adapter, and describe which operations succeeded.

## Building

The verified build environment is GitHub Actions. Environment requirements and the build, test, and release procedures are described in [docs/build-test-release.md](docs/build-test-release.md).

## License

The application is distributed under **GPL-3.0-or-later**; the license text is available at <https://www.gnu.org/licenses/gpl-3.0.html>. When distributing the APK, GPL obligations to provide the source code apply.

Third-party components are used under their own licenses; versions and details are listed in [third_party/README.md](third_party/README.md).

| Component | License |
| --- | --- |
| [FatFs](https://elm-chan.org/fsw/ff/) | BSD-style |
| [ntfs-3g](https://github.com/tuxera/ntfs-3g) | GPL-2.0-or-later |
| [lwext4](https://github.com/gkostka/lwext4) | GPL-2.0-or-later |
| [exfatprogs](https://github.com/exfatprogs/exfatprogs) | GPL-2.0-or-later |
| [Ventoy](https://github.com/ventoy/Ventoy) | GPL-3.0-or-later |
| [SectorForge](https://github.com/terraseraph/sectorforge) | MIT |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/), [XZ for Java](https://tukaani.org/xz/java.html) | Apache-2.0 / public domain |

The project is not affiliated with Ventoy, Tuxera, EtchDroid, or the other projects mentioned.

## Related projects

- [EtchDroid](https://github.com/EtchDroid/EtchDroid): writing images to USB drives without root.
- [Ventoid](https://github.com/GPLaider/Ventoid): a Ventoy installer for Android.
- [Android USB Host API](https://developer.android.com/develop/connectivity/usb/host): official documentation.
