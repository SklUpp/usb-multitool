# Host FatFs image tests — no Android SDK/NDK required on CI (ubuntu gcc).
ROOT := $(abspath ../..)
FATFS := $(ROOT)/third_party/fatfs/source
CPP := $(ROOT)/app/src/main/cpp

CC ?= gcc
CFLAGS ?= -std=c11 -O2 -Wall -Wextra -Wno-unused-parameter -D_FILE_OFFSET_BITS=64
CFLAGS += -I$(FATFS) -I$(CPP) -pthread

# Termux clang may lack installed compiler-rt; NDK copy works as a host link aid.
TERMUX_BUILTINS ?= $(wildcard $(PREFIX)/opt/ndk-multilib/cross-compiler-rt/libclang_rt.builtins-aarch64-android.a)

COMMON_SRCS := \
	image_block.c \
	$(CPP)/usb_block.c \
	$(CPP)/fat_engine.c \
	$(FATFS)/ff.c \
	$(FATFS)/ffunicode.c \
	$(FATFS)/ffsystem.c \
	$(FATFS)/diskio.c

SRCS := test_fat_images.c $(COMMON_SRCS)
TEARDOWN_SRCS := test_fat_teardown.c $(COMMON_SRCS)

.PHONY: all test clean
all: test_fat_images test_fat_teardown

test_fat_images: $(SRCS)
	$(CC) $(CFLAGS) -o $@ $(SRCS) $(TERMUX_BUILTINS) -pthread

test_fat_teardown: $(TEARDOWN_SRCS)
	$(CC) $(CFLAGS) -o $@ $(TEARDOWN_SRCS) $(TERMUX_BUILTINS) -pthread

test: test_fat_images test_fat_teardown
	./test_fat_images
	./test_fat_teardown

clean:
	rm -f test_fat_images test_fat_teardown fat16.img fat32.img exfat.img fat32_4k.img exfat_4k.img label_fat.img label_exfat.img list256.img short.img reformat.img pathovf.img teardown_ro.img teardown_clean.img teardown_sync.img teardown_retry.img teardown_barrier.img teardown_barrier_close.img teardown_barrier_ok.img teardown_close.img teardown_fail.img teardown_wfail.img teardown_fsinfo.img
