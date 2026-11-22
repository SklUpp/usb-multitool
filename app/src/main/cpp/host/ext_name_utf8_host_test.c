/* SPDX-License-Identifier: GPL-2.0-or-later */
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "ext_name_utf8.h"

static int expect_ok(const char *name, const uint16_t *want, size_t want_len)
{
	uint16_t out[64];
	size_t len = 99;

	if (!ext_utf8_to_utf16_strict(name, out, sizeof(out) / sizeof(out[0]), &len)) {
		printf("FAIL: rejected valid name: %s\n", name);
		return 1;
	}
	if (len != want_len || memcmp(out, want, want_len * sizeof(uint16_t)) != 0) {
		printf("FAIL: wrong units for: %s\n", name);
		return 1;
	}
	return 0;
}

static int expect_bad(const char *name)
{
	uint16_t out[64];
	size_t len = 1;

	if (ext_utf8_to_utf16_strict(name, out, sizeof(out) / sizeof(out[0]), &len)) {
		printf("FAIL: accepted malformed name\n");
		return 1;
	}
	if (len != 0) {
		printf("FAIL: malformed name left a partial length\n");
		return 1;
	}
	return 0;
}

int main(void)
{
	int failures = 0;
	const uint16_t ascii[] = {'a', 'b', 'c'};
	const uint16_t e_acute[] = {0x00E9};
	const uint16_t euro[] = {0x20AC};
	const uint16_t emoji[] = {0xD83D, 0xDE00};

	failures += expect_ok("abc", ascii, 3);
	failures += expect_ok("\xC3\xA9", e_acute, 1);
	failures += expect_ok("\xE2\x82\xAC", euro, 1);
	failures += expect_ok("\xF0\x9F\x98\x80", emoji, 2);
	failures += expect_ok("", ascii, 0);

	/* The exact case from the audit: a Latin-1 byte must not truncate to "abc". */
	failures += expect_bad("abc\xE9.txt");
	failures += expect_bad("\xE9");
	failures += expect_bad("a\xC3");              /* truncated 2-byte */
	failures += expect_bad("\xC0\xAF");           /* overlong 2-byte */
	failures += expect_bad("\xE0\x80\xAF");       /* overlong 3-byte */
	failures += expect_bad("\xF0\x80\x80\xAF");   /* overlong 4-byte */
	failures += expect_bad("\xED\xA0\x80");       /* UTF-16 surrogate */
	failures += expect_bad("\xF4\x90\x80\x80");   /* above U+10FFFF */
	failures += expect_bad("\xF5\x80\x80\x80");   /* invalid lead */
	failures += expect_bad("\x80");               /* stray continuation */

	if (failures != 0) {
		printf("FAIL ext_name_utf8_host_test: %d failures\n", failures);
		return 1;
	}
	printf("PASS ext_name_utf8_host_test\n");
	return 0;
}
