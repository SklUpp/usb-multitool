# Host FatFs image tests — no Android SDK/NDK required on CI (ubuntu gcc).
ROOT := $(abspath ../..)
FATFS := $(ROOT)/third_party/fatfs/source
CPP := $(ROOT)/app/src/main/cpp

CC ?= gcc
CFLAGS ?= -std=c11 -O2 -Wall -Wextra -Wno-unused-parameter
CFLAGS += -I$(FATFS) -I$(CPP)

# Termux clang may lack installed compiler-rt; NDK copy works as a host link aid.
TERMUX_BUILTINS ?= $(wildcard $(PREFIX)/opt/ndk-multilib/cross-compiler-rt/libclang_rt.builtins-aarch64-android.a)

SRCS := \
	test_fat_images.c \
	image_block.c \
	$(CPP)/usb_block.c \
	$(CPP)/fat_engine.c \
	$(FATFS)/ff.c \
	$(FATFS)/ffunicode.c \
	$(FATFS)/ffsystem.c \
	$(FATFS)/diskio.c

.PHONY: all test clean
all: test_fat_images

test_fat_images: $(SRCS)
	$(CC) $(CFLAGS) -o $@ $(SRCS) $(TERMUX_BUILTINS)

test: test_fat_images
	./test_fat_images

clean:
	rm -f test_fat_images fat16.img fat32.img exfat.img
