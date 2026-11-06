package com.superloader.companion.repair;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 最小 class 文件解析（成员表版）：给出父类名与声明的字段/方法集合，用于对运行期 mojang 类做静态校验。
 *
 * <p>为什么需要它：映射表（Connector 的 tiny、Yarn）只收录"被混淆过"的成员；像 {@code Minecraft.close()}
 * 这种名字和 Yarn/Mojang 一致、又不在 tiny 里的方法，只能去运行期类里核实。校验必须落在真实类上，
 * 否则"合成成功但游戏里找不到"就会重新变成静默失败。
 */
public final class ClassMembers {

    /** 成员集合：方法记作 {@code name(desc)}，字段记作 {@code name:desc}。 */
    public final String superName;
    /** 直接实现的接口（含父接口链要自己递归）；Level.hasNeighborSignal 这类方法就声明在接口上。 */
    public final java.util.List<String> interfaces;
    public final Set<String> methods = new LinkedHashSet<>();
    public final Set<String> fields = new LinkedHashSet<>();

    private ClassMembers(String superName, java.util.List<String> interfaces) {
        this.superName = superName;
        this.interfaces = interfaces;
    }

    /** 用来查表用的键：方法 {@code name(desc)}、字段 {@code name:desc}。 */
    public static String key(String name, String desc, boolean method) {
        return method ? name + desc : name + ":" + desc;
    }

    public boolean has(String name, String desc, boolean method) {
        return (method ? methods : fields).contains(key(name, desc, method));
    }

    public static ClassMembers parse(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("不是 class 文件（magic 不符）");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();

        int cpCount = in.readUnsignedShort();
        String[] utf8 = new String[cpCount];
        int[] classNameIndex = new int[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 3, 4 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                case 7 -> classNameIndex[i] = in.readUnsignedShort();
                case 8, 16, 19, 20 -> in.readUnsignedShort();
                case 9, 10, 11, 12, 17, 18 -> {
                    in.readUnsignedShort();
                    in.readUnsignedShort();
                }
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                default -> throw new IOException("未知常量池 tag " + tag);
            }
        }

        in.readUnsignedShort(); // access
        in.readUnsignedShort(); // this
        int superIdx = in.readUnsignedShort();
        String superName = superIdx == 0 ? null : utf8[classNameIndex[superIdx]];
        int interfaces = in.readUnsignedShort();
        java.util.List<String> ifaces = new java.util.ArrayList<>();
        for (int i = 0; i < interfaces; i++) {
            int idx = in.readUnsignedShort();
            ifaces.add(utf8[classNameIndex[idx]]);
        }

        ClassMembers cm = new ClassMembers(superName, ifaces);
        readMembers(in, utf8, cm.fields, false);
        readMembers(in, utf8, cm.methods, true);
        return cm;
    }

    private static void readMembers(DataInputStream in, String[] utf8, Set<String> into, boolean method)
            throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort();
            int nameIdx = in.readUnsignedShort();
            int descIdx = in.readUnsignedShort();
            into.add(key(utf8[nameIdx], utf8[descIdx], method));
            int attrs = in.readUnsignedShort();
            for (int a = 0; a < attrs; a++) {
                in.readUnsignedShort();
                skipFully(in, in.readInt());
            }
        }
    }

    private static void skipFully(DataInputStream s, int len) throws IOException {
        int remaining = len;
        while (remaining > 0) {
            int skipped = s.skipBytes(remaining);
            if (skipped <= 0) {
                if (s.read() < 0) {
                    throw new IOException("class 文件意外结束");
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }
}
