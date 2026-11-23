# Keep native method signatures and declaring classes
-keepclasseswithmembers class * {
    native <methods>;
}

# Keep SectorForge diagnostics native entry and callbacks
-keep class usb.diagnostics.SectorForgeNative { *; }
-keep class usb.diagnostics.SectorForgeEngine$Reader {
    int readAt(long, java.nio.ByteBuffer);
}
-keep class usb.diagnostics.SectorForgeEngine$Writer {
    int writeAt(long, java.nio.ByteBuffer);
}
-keep class usb.diagnostics.SectorForgeEngine$Progress {
    boolean onProgress(long, long, long, long, long[]);
}

# Keep block device interface and implementations accessed via JNI
-keep interface usb.device.BlockDevice {
    void read(long, java.nio.ByteBuffer);
    void write(long, java.nio.ByteBuffer);
    void sync();
}
-keep class * implements usb.device.BlockDevice {
    void read(long, java.nio.ByteBuffer);
    void write(long, java.nio.ByteBuffer);
    void sync();
}

# Keep filesystem native entry classes
-keep class usb.fs.ExfatFsck { *; }
-keep class usb.fs.ExtFs { *; }
-keep class usb.fs.FatFsNative { *; }
-keep class usb.fs.NtfsFs { *; }

# Keep FatFs block callback interface and implementations
-keep interface usb.fs.FatBlockCallback {
    int readSectors(long, byte[], int);
    int writeSectors(long, byte[], int);
    int sync();
}
-keep class * implements usb.fs.FatBlockCallback {
    int readSectors(long, byte[], int);
    int writeSectors(long, byte[], int);
    int sync();
}

# Keep data models instantiated from native code
-keep class usb.fs.ExtDirEntry { <init>(...); *; }
-keep class usb.fs.ExtStat { <init>(...); *; }
-keep class usb.fs.FatDirEntry { <init>(...); *; }
-keep class usb.fs.NtfsDirEntry { <init>(...); *; }
-keep class usb.fs.NtfsStat { <init>(...); *; }

# Keep exceptions constructed or thrown from native code
-keep class usb.fs.ExtException { *; }
-keep class usb.fs.FatFsException { *; }
-keep class usb.fs.FileExistsException { *; }
-keep class usb.fs.FileTooLargeException { *; }
-keep class usb.fs.NtfsException { *; }
