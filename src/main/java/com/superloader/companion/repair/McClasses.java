package com.superloader.companion.repair;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 运行期 mojang 类的成员索引：给"合成出来的 refmap value 到底真不真"提供最终裁判。
 *
 * <p>只用一两个 jar（如 1.21.1 的 client mojang jar）按需解压，不建全量索引——被校验的类只有几十个。
 */
public final class McClasses {

    private final List<ZipFile> zips = new ArrayList<>();
    private final Map<String, ClassMembers> cache = new HashMap<>();
    private final Map<String, byte[]> raw = new HashMap<>();

    public McClasses(List<Path> jars) throws IOException {
        for (Path p : jars) {
            zips.add(new ZipFile(p.toFile()));
        }
    }

    public void close() {
        for (ZipFile z : zips) {
            try {
                z.close();
            } catch (IOException ignored) {
                // 关闭失败不影响结果
            }
        }
    }

    /** 读类字节；找不到返回 null。 */
    private byte[] bytes(String slashedClass) {
        byte[] cached = raw.get(slashedClass);
        if (cached != null) {
            return cached;
        }
        String entry = slashedClass + ".class";
        for (ZipFile z : zips) {
            ZipEntry e = z.getEntry(entry);
            if (e == null) {
                continue;
            }
            try (InputStream in = z.getInputStream(e)) {
                byte[] b = in.readAllBytes();
                raw.put(slashedClass, b);
                return b;
            } catch (IOException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 解析类结构；jar 里没有这个类返回 null。 */
    public ClassMembers shape(String slashedClass) {
        if (cache.containsKey(slashedClass)) {
            return cache.get(slashedClass);
        }
        byte[] b = bytes(slashedClass);
        ClassMembers cm;
        try {
            cm = b == null ? null : ClassMembers.parse(b);
        } catch (IOException e) {
            cm = null;
        }
        cache.put(slashedClass, cm);
        return cm;
    }

    /**
     * 判断成员是否存在（含父类链）。类本身不在这些 jar 里时返回 {@code null}（"我无法判断"，
     * 调用方应回退到映射表校验）。
     */
    public Boolean hasMember(String slashedClass, String name, String desc, boolean method) {
        ClassMembers first = shape(slashedClass);
        if (first == null) {
            return null;
        }
        java.util.Deque<String> todo = new java.util.ArrayDeque<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        todo.add(slashedClass);
        boolean incomplete = false;
        // 父类与接口都要走：Level.hasNeighborSignal 声明在 SignalGetter 接口，
        // ServerLevel.noCollision 声明在 EntityGetter 接口，只看父类会误判成"不存在"。
        while (!todo.isEmpty() && seen.size() < 400) {
            String current = todo.poll();
            if (!seen.add(current)) {
                continue;
            }
            ClassMembers cm = shape(current);
            if (cm == null) {
                if (current.equals(slashedClass)) {
                    return null;
                }
                // 父类/接口在别的 jar 里（netty、DataFixerUpper、JDK）——查不到不等于不存在。
                incomplete = true;
                continue;
            }
            if (cm.has(name, desc, method)) {
                return true;
            }
            if (cm.superName != null) {
                todo.add(cm.superName);
            }
            todo.addAll(cm.interfaces);
        }
        return incomplete ? null : false;
    }
}
