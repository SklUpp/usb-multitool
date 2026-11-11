/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef EXT_NAME_UTF8_H
#define EXT_NAME_UTF8_H

#include <stddef.h>
#include <stdint.h>

/*
 * Strict RFC 3629 UTF-8 -> UTF-16 code units. Rejects invalid lead/continuation
 * bytes, overlong forms, surrogate code points and values above U+10FFFF.
 *
 * Deliberately does not substitute U+FFFD or truncate: a malformed on-disk name
 * would otherwise be displayed as another existing name and a later delete/rename
 * could hit the wrong entry. Returns 1 with *out_len set on success; 0 on the first
 * malformed byte (*out_len stays 0).
 */
static inline int ext_utf8_to_utf16_strict(const char *in, uint16_t *out,
					   size_t out_cap, size_t *out_len)
{
	const unsigned char *p = (const unsigned char *)in;
	size_t used = 0;

	if (out_len)
		*out_len = 0;
	if (!in)
		return 0;

	while (*p) {
		unsigned cp;
		size_t extra;
		unsigned lo = 0x80, hi = 0xBF;

		if (p[0] < 0x80) {
			cp = p[0];
			extra = 0;
		} else if (p[0] >= 0xC2 && p[0] <= 0xDF) {
			cp = p[0] & 0x1Fu;
			extra = 1;
		} else if (p[0] >= 0xE0 && p[0] <= 0xEF) {
			cp = p[0] & 0x0Fu;
			extra = 2;
			if (p[0] == 0xE0)
				lo = 0xA0; /* reject overlong 3-byte */
			else if (p[0] == 0xED)
				hi = 0x9F; /* reject UTF-16 surrogates */
		} else if (p[0] >= 0xF0 && p[0] <= 0xF4) {
			cp = p[0] & 0x07u;
			extra = 3;
			if (p[0] == 0xF0)
				lo = 0x90; /* reject overlong 4-byte */
			else if (p[0] == 0xF4)
				hi = 0x8F; /* reject > U+10FFFF */
		} else {
			return 0; /* stray continuation, C0/C1, F5..FF */
		}

		p++;
		for (size_t i = 0; i < extra; i++, p++) {
			unsigned b = *p;

			if (b < (i == 0 ? lo : 0x80u) || b > (i == 0 ? hi : 0xBFu))
				return 0;
			cp = (cp << 6) | (b & 0x3Fu);
		}

		if (cp >= 0x10000u) {
			if (used + 2 > out_cap)
				return 0;
			cp -= 0x10000u;
			out[used++] = (uint16_t)(0xD800u + (cp >> 10));
			out[used++] = (uint16_t)(0xDC00u + (cp & 0x3FFu));
		} else {
			if (used + 1 > out_cap)
				return 0;
			out[used++] = (uint16_t)cp;
		}
	}

	if (out_len)
		*out_len = used;
	return 1;
}

#endif /* EXT_NAME_UTF8_H */
