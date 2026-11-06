package com.superloader.companion.repair;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 最小 class 文件解析：只取两样东西——{@code @Mixin} 的 value/targets，以及整个常量池里的 UTF-8 字符串。
 *
 * <p>不依赖 ASM，因为修复工具要能在启动前用 {@code java -cp companion.jar} 单独运行。
 */
public final class ClassFileInfo {

    /** @Mixin 声明的目标类（已归一化，如 {@code net/minecraft/class_310}）。 */
    public final List<String> targets = new ArrayList<>();
    /** 常量池全部字符串（注解里的方法名/描述符都在这里）。 */
    public final Set<String> utf8 = new LinkedHashSet<>();

    private ClassFileInfo() {
    }

    public static ClassFileInfo parse(byte[] bytes) throws IOException {
        ClassFileInfo info = new ClassFileInfo();
        new Parser(info, bytes).run();
        return info;
    }

    private static final class Parser {
        private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";

        private final ClassFileInfo info;
        private final DataInputStream in;
        private String[] utf8;

        Parser(ClassFileInfo info, byte[] bytes) {
            this.info = info;
            this.in = new DataInputStream(new ByteArrayInputStream(bytes));
        }

        void run() throws IOException {
            if (in.readInt() != 0xCAFEBABE) {
                throw new IOException("不是 class 文件（magic 不符）");
            }
            in.readUnsignedShort();
            in.readUnsignedShort();

            int cpCount = in.readUnsignedShort();
            utf8 = new String[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> {
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                    }
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new IOException("未知常量池 tag " + tag + " (index " + i + ")");
                }
            }
            for (int i = 1; i < cpCount; i++) {
                if (utf8[i] != null) {
                    info.utf8.add(utf8[i]);
                }
            }

            in.readUnsignedShort(); // access
            in.readUnsignedShort(); // this
            in.readUnsignedShort(); // super
            int interfaces = in.readUnsignedShort();
            for (int i = 0; i < interfaces; i++) {
                in.readUnsignedShort();
            }
            skipMembers();
            skipMembers();

            int attrs = in.readUnsignedShort();
            for (int i = 0; i < attrs; i++) {
                int nameIdx = in.readUnsignedShort();
                int len = in.readInt();
                String name = utf8[nameIdx];
                if ("RuntimeInvisibleAnnotations".equals(name) || "RuntimeVisibleAnnotations".equals(name)) {
                    byte[] buf = new byte[len];
                    in.readFully(buf);
                    readAnnotations(new DataInputStream(new ByteArrayInputStream(buf)));
                } else {
                    skipFully(in, len);
                }
            }
        }

        private void skipMembers() throws IOException {
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                in.readUnsignedShort();
                in.readUnsignedShort();
                in.readUnsignedShort();
                int attrs = in.readUnsignedShort();
                for (int a = 0; a < attrs; a++) {
                    in.readUnsignedShort();
                    skipFully(in, in.readInt());
                }
            }
        }

        private void readAnnotations(DataInputStream a) throws IOException {
            int count = a.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                String type = utf8At(a);
                int pairs = a.readUnsignedShort();
                Object value = null;
                Object targets = null;
                for (int p = 0; p < pairs; p++) {
                    String elem = utf8At(a);
                    Object v = elementValue(a);
                    if ("value".equals(elem)) {
                        value = v;
                    } else if ("targets".equals(elem)) {
                        targets = v;
                    }
                }
                if (MIXIN_DESC.equals(type)) {
                    collect(value, true);
                    collect(targets, false);
                }
            }
        }

        private void collect(Object v, boolean isClassConstant) {
            if (v instanceof String s) {
                addTarget(s, isClassConstant);
            } else if (v instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof String s) {
                        addTarget(s, isClassConstant);
                    }
                }
            }
        }

        private void addTarget(String raw, boolean isClassConstant) {
            String s = raw.trim();
            if (s.isEmpty()) {
                return;
            }
            if (s.startsWith("L") && s.endsWith(";")) {
                s = s.substring(1, s.length() - 1);
            }
            s = s.replace('.', '/');
            if (!info.targets.contains(s)) {
                info.targets.add(s);
            }
        }

        private String utf8At(DataInputStream a) throws IOException {
            int idx = a.readUnsignedShort();
            if (idx == 0 || idx >= utf8.length) {
                throw new IOException("常量池索引越界 " + idx);
            }
            return utf8[idx];
        }

        private Object elementValue(DataInputStream a) throws IOException {
            int tag = a.readUnsignedByte();
            switch (tag) {
                case 's', 'c' -> {
                    return utf8At(a);
                }
                case 'e' -> {
                    utf8At(a);
                    utf8At(a);
                    return null;
                }
                case '@' -> {
                    utf8At(a);
                    int pairs = a.readUnsignedShort();
                    for (int p = 0; p < pairs; p++) {
                        utf8At(a);
                        elementValue(a);
                    }
                    return null;
                }
                case '[' -> {
                    int n = a.readUnsignedShort();
                    List<Object> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        list.add(elementValue(a));
                    }
                    return list;
                }
                case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> {
                    a.readUnsignedShort();
                    return null;
                }
                default -> throw new IOException("未知注解元素 tag " + tag);
            }
        }

        private static void skipFully(DataInputStream s, int len) throws IOException {
            int remaining = len;
            while (remaining > 0) {
                int skipped = s.skipBytes(remaining);
                if (skipped <= 0) {
                    if (s.read() < 0) {
                        throw new EOFException();
                    }
                    remaining--;
                } else {
                    remaining -= skipped;
                }
            }
        }
    }
}
