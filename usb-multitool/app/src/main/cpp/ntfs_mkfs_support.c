/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Minimal stand-ins for ntfsprogs/utils.c when upstream mkntfs is linked as
 * a library. The formatter itself is the unmodified upstream mkntfs code
 * (see scripts/patches/ntfs-3g-mkntfs-library.patch); only the CLI locale
 * setup, banner strings and the libntfs compatibility wrapper live in
 * utils.c, which also needs libintl (absent from the Android NDK).
 * ntfs_home already comes from libntfs-3g/volume.c.
 *
 * ntfs_mbstoucs_libntfscompat() is copied from ntfsprogs/utils.c
 * (GPL-2.0-or-later) to keep the same semantics for preallocated buffers.
 * Portions: Copyright (c) 2002-2005 Richard Russon,
 * Copyright (c) 2003-2006 Anton Altaparmakov, Copyright (c) 2003 Lode Leroy,
 * Copyright (c) 2005-2007 Yura Pakhuchiy, Copyright (c) 2014 Jean-Pierre Andre.
 */
#include <errno.h>
#include <stdlib.h>
#include <string.h>

#include "utils.h"
#include "unistr.h"

const char *ntfs_bugs = "";
const char *ntfs_gpl = "";

int utils_set_locale(void)
{
	return 0;
}

int ntfs_mbstoucs_libntfscompat(const char *ins, ntfschar **outs, int outs_len)
{
	if (!outs) {
		errno = EINVAL;
		return -1;
	} else if (*outs != NULL) {
		/* libntfs' mbstoucs allows a preallocated buffer; libntfs-3g's
		 * always allocates, so copy into the caller's buffer. */
		ntfschar *tmpstr = NULL;
		int tmpstr_len;

		tmpstr_len = ntfs_mbstoucs(ins, &tmpstr);
		if (tmpstr_len >= 0) {
			if ((tmpstr_len + 1) > outs_len) {
				ntfschar *re_outs = realloc(*outs,
					sizeof(ntfschar) * (tmpstr_len + 1));
				if (!re_outs)
					tmpstr_len = -1;
				else
					*outs = re_outs;
			}
			if (tmpstr_len >= 0)
				memcpy(*outs, tmpstr,
				       sizeof(ntfschar) * (tmpstr_len + 1));
			free(tmpstr);
		}
		return tmpstr_len;
	}
	return ntfs_mbstoucs(ins, outs);
}
