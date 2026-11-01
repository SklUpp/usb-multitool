CC ?= clang
CFLAGS ?= -Wall -Wextra -O2 -I../../app/src/main/cpp -I../../third_party/ventoy

SRCS = test_ventoy_engine.c \
       image_block.c \
       ../../app/src/main/cpp/usb_block.c \
       ../../app/src/main/cpp/ventoy_engine.c \
       ../../third_party/ventoy/ventoy_crc32.c \
       ../../third_party/ventoy/ventoy_util.c

TARGET = test_ventoy_engine

all: $(TARGET)

$(TARGET): $(SRCS)
	$(CC) $(CFLAGS) $(SRCS) -o $(TARGET)

test: $(TARGET)
	./$(TARGET)

clean:
	rm -f $(TARGET) *.o

.PHONY: all test clean
