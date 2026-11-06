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
 * 读取 Connector 内置的 {@code mappings/mappings.tiny}（v1，official/intermediary/mojang 三命名空间），
 * 提供"mixin 注解里的 dev 名 → mojang 成员描述符"的查询。
 *
 * <p><b>列布局（勿再读错）</b>：tiny v1 的表头是 {@code v1 official intermediary mojang}，
 * 命名空间按 0 基编号：official=0、intermediary=1、<b>mojang=2</b>（1 基则是第 3 个）。
 * 成员行的字段（0 基）：
 * <pre>
 *   0 METHOD/FIELD   1 owner(official)   2 desc(official)   3 official 名   4 intermediary 名   5 mojang 名
 * </pre>
 * 即 {@code p[5]} 才是 mojang 名，{@code p[4]} 是 intermediary 名；类型描述符同样按 official 书写，
 * 需要按类表换成 mojang。
 *
 * <p>refmap 的 value 必须输出 <b>mojang</b> 名（类、成员名、描述符全部 mojang），
 * 因为 NeoForge 运行时就是 mojang 命名空间；Connector 的 refmap 重映射对已是 mojang 的值是原样放行。
 */
public final class TinyMappings {

    /** 一条成员映射：official 描述符、official 名、intermediary 名、mojang 名。 */
    public record Member(String officialDesc, String officialName, String interName, String mojangName) {
        public boolean isMethod() {
            return officialDesc.startsWith("(");
        }
    }

    private final Map<String, String> offToInter = new HashMap<>();
    private final Map<String, String> interToOff = new HashMap<>();
    private final Map<String, String> mojToInter = new HashMap<>();
    private final Map<String, String> offToMoj = new HashMap<>();
    private final Map<String, String> interToMoj = new HashMap<>();
    private final Map<String, List<Member>> membersByOfficial = new HashMap<>();
    private final Map<String, String> interDescCache = new HashMap<>();
    private final Map<String, String> mojDescCache = new HashMap<>();
    private Map<String, List<Member>> anywhere;

    public static TinyMappings load(Path connectorJar) throws IOException {
        TinyMappings m = new TinyMappings();
        try (ZipFile zf = new ZipFile(connectorJar.toFile())) {
            ZipEntry e = zf.getEntry("mappings/mappings.tiny");
            if (e == null) {
                throw new IOException("connector jar 内没有 mappings/mappings.tiny");
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
        if (header == null || !header.startsWith("v1")) {
            throw new IOException("不是 tiny v1 格式：" + header);
        }
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            String[] p = line.split("\t", -1);
            switch (p[0]) {
                case "CLASS" -> {
                    if (p.length >= 4) {
                        offToInter.put(p[1], p[2]);
                        interToOff.put(p[2], p[1]);
                        mojToInter.put(p[3], p[2]);
                        offToMoj.put(p[1], p[3]);
                        interToMoj.put(p[2], p[3]);
                    }
                }
                case "METHOD", "FIELD" -> {
                    if (p.length >= 6) {
                        membersByOfficial.computeIfAbsent(p[1], k -> new ArrayList<>())
                                .add(new Member(p[2], p[3], p[4], p[5]));
                    }
                }
                default -> {
                    // 忽略注释行与未知行
                }
            }
        }
    }

    public String official(String intermediaryClass) {
        return interToOff.get(intermediaryClass);
    }

    public String toIntermediaryClass(String name) {
        if (name == null) {
            return null;
        }
        String n = name.replace('.', '/');
        if (interToOff.containsKey(n)) {
            return n;
        }
        String viaMoj = mojToInter.get(n);
        if (viaMoj != null) {
            return viaMoj;
        }
        return offToInter.get(n);
    }

    /** 把描述符里的 official 类名替换成 intermediary 类名。 */
    public String toIntermediaryDesc(String officialDesc) {
        if (officialDesc == null) {
            return null;
        }
        String cached = interDescCache.get(officialDesc);
        if (cached != null) {
            return cached;
        }
        StringBuilder sb = new StringBuilder(officialDesc.length() + 8);
        int i = 0;
        while (i < officialDesc.length()) {
            char c = officialDesc.charAt(i);
            if (c == 'L') {
                int end = officialDesc.indexOf(';', i);
                if (end < 0) {
                    sb.append(officialDesc, i, officialDesc.length());
                    break;
                }
                String cls = officialDesc.substring(i + 1, end);
                String inter = offToInter.getOrDefault(cls, cls);
                sb.append('L').append(inter).append(';');
                i = end + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        String out = sb.toString();
        interDescCache.put(officialDesc, out);
        return out;
    }

    /** 类名 → mojang 类名（接受 official / intermediary / mojang 三种写法；查不到原样返回）。 */
    public String toMojangClass(String name) {
        if (name == null) {
            return null;
        }
        String n = name.replace('.', '/');
        String moj = interToMoj.get(n);
        if (moj != null) {
            return moj;
        }
        moj = offToMoj.get(n);
        if (moj != null) {
            return moj;
        }
        return n;  // 已经是 mojang（或非 MC 类）
    }

    /** 把描述符里的类名换成 mojang 类名（接受 official / intermediary / mojang 书写）。 */
    public String toMojangDesc(String desc) {
        if (desc == null) {
            return null;
        }
        String cached = mojDescCache.get(desc);
        if (cached != null) {
            return cached;
        }
        StringBuilder sb = new StringBuilder(desc.length() + 8);
        int i = 0;
        while (i < desc.length()) {
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                if (end < 0) {
                    sb.append(desc, i, desc.length());
                    break;
                }
                String cls = desc.substring(i + 1, end);
                sb.append('L').append(toMojangClass(cls)).append(';');
                i = end + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        String out = sb.toString();
        mojDescCache.put(desc, out);
        return out;
    }

    /** 该 intermediary 类声明的所有成员（可能为空）。 */
    public List<Member> members(String intermediaryClass) {
        String off = interToOff.get(intermediaryClass);
        if (off == null) {
            return List.of();
        }
        List<Member> list = membersByOfficial.get(off);
        return list == null ? List.of() : list;
    }

    /** 以 intermediary 名 + 可选 intermediary 描述符查成员。 */
    public Member byInterName(String intermediaryClass, String interName, String interDescOrNull) {
        for (Member m : members(intermediaryClass)) {
            if (!m.interName().equals(interName)) {
                continue;
            }
            if (interDescOrNull == null || toIntermediaryDesc(m.officialDesc()).equals(interDescOrNull)) {
                return m;
            }
        }
        return null;
    }

    /**
     * 以 intermediary 名 + 描述符在**全表**里找成员。
     * mixin 允许在子类上引用父类声明的方法（carpet 的 {@code AbstractArrow.tick} 实际声明在 Entity），
     * 这时按 owner 查不到，只能按成员名反查声明处，再交给运行期类沿父类链校验。
     * 同一个 method_/field_ 编号理论上唯一；若出现多个不同 mojang 名则不猜。
     */
    public Member byInterNameAnywhere(String interName, String interDesc) {
        if (anywhere == null) {
            anywhere = new HashMap<>();
            for (List<Member> list : membersByOfficial.values()) {
                for (Member m : list) {
                    anywhere.computeIfAbsent(m.interName(), k -> new ArrayList<>()).add(m);
                }
            }
        }
        List<Member> list = anywhere.get(interName);
        if (list == null) {
            return null;
        }
        Member found = null;
        for (Member m : list) {
            if (!toIntermediaryDesc(m.officialDesc()).equals(interDesc)) {
                continue;
            }
            if (found != null && !found.mojangName().equals(m.mojangName())) {
                return null;
            }
            found = m;
        }
        return found;
    }

    /** 以 mojang 名查成员（mod 若用 mojmap 构建，注解里写的就是 mojang 名）。 */
    public Member byMojangName(String intermediaryClass, String mojangName) {
        for (Member m : members(intermediaryClass)) {
            if (m.mojangName().equals(mojangName)) {
                return m;
            }
        }
        return null;
    }

    /** 以 official 名查成员（极少数 mod 用混淆名）。 */
    public Member byOfficialName(String intermediaryClass, String officialName) {
        for (Member m : members(intermediaryClass)) {
            if (m.officialName().equals(officialName)) {
                return m;
            }
        }
        return null;
    }

    /**
     * 映射表里是否有这个 mojang 类。用来区分"类存在但成员不存在"（校验失败）与
     * "这个类根本不归 MC 管"（netty/DFU/JDK，无法判断）。
     */
    public boolean knowsMojangClass(String mojangClass) {
        String n = mojangClass.replace('.', '/');
        return mojToInter.containsKey(n) || interToMoj.containsValue(n) || offToMoj.containsValue(n);
    }

    /**
     * 静态校验用：给定 mojang 类名、mojang 成员名、mojang 描述符，判断它是否真实存在于映射表。
     * 合成出来的每条 refmap value 都要过这一关，过不了就记 unresolved。
     */
    public boolean hasMojangMember(String mojangClass, String mojangName, String mojangDesc) {
        String inter = mojToInter.get(mojangClass.replace('.', '/'));
        if (inter == null) {
            return false;
        }
        for (Member m : members(inter)) {
            if (m.mojangName().equals(mojangName) && toMojangDesc(m.officialDesc()).equals(mojangDesc)) {
                return true;
            }
        }
        return false;
    }

    /** 静态校验用（intermediary 写法）：value 仍是 intermediary 时，直接用映射表核实成员是否存在。 */
    public boolean hasInterMember(String intermediaryClass, String interName, String interDesc) {
        for (Member m : members(intermediaryClass)) {
            if (!m.interName().equals(interName)) {
                continue;
            }
            if (toIntermediaryDesc(m.officialDesc()).equals(interDesc)) {
                return true;
            }
        }
        return false;
    }

    /** 生成 refmap value：{@code L<mojangOwner>;<mojangName><mojangDesc>}（字段用 ':' 分隔名与描述符）。 */
    public String refValue(String intermediaryClass, Member m) {
        String sep = m.isMethod() ? "" : ":";
        return "L" + toMojangClass(intermediaryClass) + ";" + m.mojangName() + sep
                + toMojangDesc(m.officialDesc());
    }

    /** 成员描述符，mojang 命名空间。 */
    public String descriptor(String intermediaryClass, Member m) {
        return toMojangDesc(m.officialDesc());
    }
}
