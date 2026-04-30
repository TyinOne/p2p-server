package com.tyin.zero.p2pcommon.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * 文件权限工具类（跨平台兼容）
 */
public final class FilePermissionUtil {

    private static final Set<java.nio.file.attribute.PosixFilePermission> OWNER_RW =
            PosixFilePermissions.fromString("rw-------");

    private FilePermissionUtil() {}

    /**
     * 设置文件为仅所有者可读写（Windows 上静默跳过）
     */
    public static void setOwnerReadWrite(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_RW);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            // Windows 不支持 POSIX 权限，或文件系统不支持
        }
    }
}
