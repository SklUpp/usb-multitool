/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef EXFAT_FSCK_LIB_H
#define EXFAT_FSCK_LIB_H

#include "libexfat.h"

#ifdef __cplusplus
extern "C" {
#endif

#define EXFAT_FSCK_EXIT_NO_ERRORS        0x00
#define EXFAT_FSCK_EXIT_CORRECTED        0x01
#define EXFAT_FSCK_EXIT_ERRORS_LEFT      0x04
#define EXFAT_FSCK_EXIT_OPERATION_ERROR  0x08

int exfat_fsck_on_blk_dev(struct exfat_blk_dev *bd, int repair_yes);

#ifdef __cplusplus
}
#endif

#endif
