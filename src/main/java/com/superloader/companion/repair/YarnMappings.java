package com.superloader.companion.repair;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 读取 Yarn 官方映射（tiny v2，intermediary / named 两列），提供 named → intermediary 的查询。
 *
 * <p>为什么需要它：一部分 Fabric mod（典型：yacl 3.8.2）发布时丢了 refmap，而 mixin 注解里写的是
 * <b>Yarn 名</b>（{@code getNeighboringEntry}、{@code NavigationDirection}、{@code EntryListWidget$Entry}）。
 * Connector 自带的 {@code mappings.tiny} 只有 official / intermediary / mojang 三列，翻不出 Yarn 名，
 * 所以必须再拿一份 Yarn 映射补上 named → intermediary 这一段，之后交给 Connector 做
 * intermediary → mojang。
 *
 * <p><b>tiny v2 布局（已实测 yarn-1.21.1+build.3-v2.jar）</b>：
 * <pre>
 *   tiny	2	0	intermediary	named          ← 表头；命名空间从第 4 列开始
 *   c	net/minecraft/class_350	net/minecraft/client/gui/widget/EntryListWidget
 *   \tm	(Lnet/minecraft/class_8028;Ljava/util/function/Predicate;)Lnet/minecraft/class_350$class_351;	method_48198	getNeighboringEntry
 * </pre>
 * 要点：成员行的 <b>描述符在第一个命名空间（intermediary）</b> 里书写，第 3 列是 intermediary 名，
 * 第 4 列是 named 名。所以查表时要把注解里的 Yarn 描述符先翻成 intermediary，再用它匹配。
 */
public final class YarnMappings {

    /** 一个成员：intermediary 描述符、intermediary 名、named 名。 */
    public record Member(String interDesc, String interName, String namedName) {
        public boolean isMethod() {
            return interDesc.startsWith("(");
        }
    }

    private final Map<String, String> namedToInter = new HashMap<>();
    private final Map<String, Map<String, List<Member>>> membersByInter = new HashMap<>();
    private final Map<String, String> descCache = new HashMap<>();

    public static YarnMappings load(Path yarnJar) throws IOException {
        YarnMappings m = new YarnMappings();
        try (ZipFile zf = new ZipFile(yarnJar.toFile())) {
            ZipEntry e = zf.getEntry("mappings/mappings.tiny");
            if (e == null) {
                throw new IOException("yarn jar 内没有 mappings/mappings.tiny: " + yarnJar);
            }
            try (InputStream in = zf.getInputStream(e);
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                m.read(r);
            }
        }
        return m;
    }

    private void read(BufferedReader r) throws IOException {
        String header = r.readLine();
        if (header == null || !header.startsWith("tiny\t2")) {
            throw new IOException("不是 tiny v2 格式：" + header);
        }
        String[] ns = header.split("\t", -1);
        if (ns.length < 5) {
            throw new IOException("tiny v2 命名空间不足（需要 intermediary 与 named）：" + header);
        }
        String curInter = null;
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            if (!line.startsWith("\t")) {
                String[] p = line.split("\t", -1);
                if (p.length >= 3 && p[0].equals("c")) {
                    curInter = p[1];
                    namedToInter.put(p[2].replace('.', '/'), p[1]);
                }
                continue;
            }
            if (curInter == null) {
                continue;
            }
            String[] p = line.split("\t", -1);
            if (p[0].isEmpty()) {
                continue; // 双 Tab 行（注释/空字段）没有标签，跳过
            }
            String tag = p[0].substring(1);
            if ((tag.equals("m") || tag.equals("f")) && p.length >= 4) {
                String desc = p[1];
                String interName = p[2];
                String namedName = p[3];
                membersByInter.computeIfAbsent(curInter, k -> new HashMap<>())
                        .computeIfAbsent(namedName, k -> new ArrayList<>())
                        .add(new Member(desc, interName, namedName));
            }
        }
    }

    /** Yarn(named) 类名 → intermediary 类名；查不到返回 null。 */
    public String namedToInterClass(String namedClass) {
        if (namedClass == null) {
            return null;
        }
        return namedToInter.get(namedClass.replace('.', '/'));
    }

    /** 把描述符里的 Yarn(named) 类名换成 intermediary 类名；非 Yarn 类原样保留。 */
    public String namedDescToInterDesc(String namedDesc) {
        if (namedDesc == null) {
            return null;
        }
        String cached = descCache.get(namedDesc);
        if (cached != null) {
            return cached;
        }
        StringBuilder sb = new StringBuilder(namedDesc.length() + 8);
        int i = 0;
        while (i < namedDesc.length()) {
            char c = namedDesc.charAt(i);
            if (c == 'L') {
                int end = namedDesc.indexOf(';', i);
                if (end < 0) {
                    sb.append(namedDesc, i, namedDesc.length());
                    break;
                }
                String cls = namedDesc.substring(i + 1, end).replace('.', '/');
                String inter = namedToInter.get(cls);
                sb.append('L').append(inter != null ? inter : cls).append(';');
                i = end + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        String out = sb.toString();
        descCache.put(namedDesc, out);
        return out;
    }

    /** 在 intermediary owner 下按 named 名（可选 intermediary 描述符精确匹配）查成员。 */
    public Member byNamedName(String interOwner, String namedName, String interDescOrNull) {
        Map<String, List<Member>> byName = membersByInter.get(interOwner);
        if (byName == null) {
            return null;
        }
        List<Member> list = byName.get(namedName);
        if (list == null) {
            return null;
        }
        if (interDescOrNull == null) {
            return list.size() == 1 ? list.get(0) : null;
        }
        Member found = null;
        for (Member m : list) {
            if (!m.interDesc().equals(interDescOrNull)) {
                continue;
            }
            if (found != null) {
                return null; // 同描述符多条，宁可不写
            }
            found = m;
        }
        return found;
    }

    public int classCount() {
        return namedToInter.size();
    }
}
