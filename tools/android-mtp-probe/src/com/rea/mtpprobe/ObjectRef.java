package com.rea.mtpprobe;

/**
 * 一个 MTP 对象的引用：句柄 / 相机侧字节数 / 文件名。
 *
 * 原先用 {@code Object[]} 打包 {@code {handle, size, name}}，字段顺序只靠注释维系；
 * 换成具名 final 字段后，顺序拼错在编译期就过不去。
 */
final class ObjectRef {

    final int handle;
    final long size;
    final String name;

    ObjectRef(int handle, long size, String name) {
        this.handle = handle;
        this.size = size;
        this.name = name;
    }
}
