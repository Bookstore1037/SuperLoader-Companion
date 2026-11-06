package com.superloader.companion.repair;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 启动前修补缺失的 refmap。
 *
 * <p>背景：Fabric 的 mixin 用 dev 名写注解，靠 jar 内 refmap 把它们映射到运行时命名空间；有些 mod
 * （典型：yacl 3.8.2）在 mixin config 里声明了 {@code refmap: xxx.json}，jar 里却没有这个文件。Connector
 * 只会"找不到就打 WARN"，于是这些 mixin 在运行期找不到目标（{@code No refMap loaded}）。
 *
 * <p>本工具在游戏启动前（Mixin 初始化之前）扫描 mods/，对"声明了 refmap 但文件缺失"的 mod 合成 refmap。
 * 合成的 value 一律是 <b>mojang</b>：Connector 对已是 mojang 的值原样放行，NeoForge 运行时也正是 mojang。
 *
 * <p>注解里可能出现的三种写法，都要能翻：
 * <ol>
 *   <li>裸 intermediary 名：{@code method_1490}、{@code field_22739} —— 查 Connector 的 tiny 即可；</li>
 *   <li>带 owner 的成员引用：{@code Lnet/minecraft/class_310;close()V}、{@code Lowner;name:desc}；</li>
 *   <li>Yarn(named) 名：{@code getNeighboringEntry(...)}，描述符里还带着 {@code NavigationDirection} 这类
 *       named 类型。这一段只有 Yarn 官方映射能给，故用 {@link YarnMappings}（版本+sha1 由 lockfile 固定）。</li>
 * </ol>
 *
 * <p><b>静态校验</b>：每条合成出来的映射都会用 mojmap 映射表反查（类+名+描述符），通过才记
 * {@code verified: true}，否则进 unresolved 且不写进 jar。这样合成错了不会等到游戏崩，repair 当次就能看出来。
 *
 * <p>用法：
 * <pre>
 * java -cp superloader_companion.jar com.superloader.companion.repair.RefmapRepair \
 *      --mods ...\mods --connector ...\connector.jar --report ...\refmap_repair.json \
 *      [--yarn-version 1.21.1+build.3 --yarn-sha1 53c8... --cache ...\.cache\compat\mappings] \
 *      [--corpus ...\compat\corpus.json] [--dry-run] [--restore] [--verbose]
 * </pre>
 */
public final class RefmapRepair {

    private static final String BACKUP_DIR = ".superloader-refmap";
    private static final Pattern BARE_MEMBER = Pattern.compile("(method|field)_\\d+");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern BARE_METHOD =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*\\([^()]*\\)(\\[*(L[^;]+;|[ZBCSIJFDV]))");

    public static void main(String[] args) {
        try {
            int code = run(args);
            if (code != 0) {
                System.exit(code);
            }
        } catch (Exception e) {
            System.err.println("[refmap-repair] 失败: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static int run(String[] args) throws IOException {
        Path mods = null;
        Path connector = null;
        Path report = null;
        Path cache = null;
        Path corpus = null;
        List<Path> mcJars = new ArrayList<>();
        String yarnVersion = null;
        String yarnSha1 = null;
        boolean dryRun = false;
        boolean restore = false;
        boolean verbose = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mods" -> mods = Path.of(args[++i]);
                case "--connector" -> connector = Path.of(args[++i]);
                case "--report" -> report = Path.of(args[++i]);
                case "--cache" -> cache = Path.of(args[++i]);
                case "--corpus" -> corpus = Path.of(args[++i]);
                case "--yarn-version" -> yarnVersion = args[++i];
                case "--yarn-sha1" -> yarnSha1 = args[++i];
                case "--mc-jar" -> mcJars.add(Path.of(args[++i]));
                case "--dry-run" -> dryRun = true;
                case "--restore" -> restore = true;
                case "--verbose" -> verbose = true;
                default -> throw new IllegalArgumentException("未知参数 " + args[i]);
            }
        }
        if (mods == null || !Files.isDirectory(mods)) {
            throw new IllegalArgumentException("--mods 必须是目录: " + mods);
        }
        if (restore) {
            int n = restoreAll(mods);
            System.out.println("[refmap-repair] 已还原 " + n + " 个 jar");
            return 0;
        }
        if (connector == null || !Files.isRegularFile(connector)) {
            throw new IllegalArgumentException("--connector 必须指向 Connector jar: " + connector);
        }

        TinyMappings tiny = TinyMappings.load(connector);
        McClasses mc = mcJars.isEmpty() ? null : new McClasses(mcJars);
        Set<String> sampleIds = corpus == null ? Set.of() : readCorpusIds(corpus);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated", OffsetDateTime.now().toString());
        out.put("mods_dir", mods.toString());
        out.put("connector", connector.getFileName().toString());
        out.put("dry_run", dryRun);
        out.put("mc_jars", mcJars.stream().map(p -> p.getFileName().toString()).toList());

        YarnMappings yarn = null;
        Map<String, Object> yarnOut = new LinkedHashMap<>();
        if (yarnVersion != null || yarnSha1 != null) {
            if (yarnVersion == null || yarnSha1 == null) {
                throw new IllegalArgumentException("--yarn-version 与 --yarn-sha1 必须同时提供（sha1 校验是硬约束）");
            }
            try {
                Path jar = resolveYarnJar(cache, yarnVersion, yarnSha1);
                yarn = YarnMappings.load(jar);
                yarnOut.put("status", "loaded");
                yarnOut.put("version", yarnVersion);
                yarnOut.put("sha1", yarnSha1);
                yarnOut.put("classes", yarn.classCount());
            } catch (Exception e) {
                yarnOut.put("status", "error");
                yarnOut.put("version", yarnVersion);
                yarnOut.put("error", e.toString());
                System.err.println("[refmap-repair] Yarn 映射不可用，named 名将无法解析: " + e.getMessage());
            }
        } else {
            yarnOut.put("status", "disabled");
        }
        out.put("yarn", yarnOut);

        List<Object> results = new ArrayList<>();
        int patched = 0;
        int missingRefmaps = 0;
        int unresolvedTotal = 0;
        int unverifiableTotal = 0;

        List<Path> jars = new ArrayList<>();
        try (var stream = Files.list(mods)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().forEach(jars::add);
        }
        for (Path jar : jars) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("file", jar.getFileName().toString());
            List<Object> configs = new ArrayList<>();
            int entries = 0;
            int unresolved = 0;
            int unverifiable = 0;
            boolean didPatch = false;
            List<Map<String, Object>> toWrite = new ArrayList<>();
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                ZipEntry fmjEntry = zf.getEntry("fabric.mod.json");
                if (fmjEntry == null) {
                    continue;
                }
                Map<String, Object> fmj = Json.parseObject(read(zf, fmjEntry));
                Object idObj = fmj.get("id");
                String id = idObj == null ? "" : String.valueOf(idObj);
                r.put("id", id);
                if (id.equals("connector") || id.equals("forgified-fabric-api") || id.equals("superloader_companion")) {
                    continue;
                }
                r.put("origin", classify(id, sampleIds));
                List<String> configNames = new ArrayList<>();
                Object mixins = fmj.get("mixins");
                if (mixins instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof String s) {
                            configNames.add(s);
                        } else if (o instanceof Map<?, ?> m && m.get("config") != null) {
                            configNames.add(String.valueOf(m.get("config")));
                        }
                    }
                }
                if (configNames.isEmpty()) {
                    continue;
                }
                for (String cfgName : configNames) {
                    Map<String, Object> cfgOut = new LinkedHashMap<>();
                    cfgOut.put("config", cfgName);
                    ZipEntry cfgEntry = zf.getEntry(cfgName);
                    if (cfgEntry == null) {
                        cfgOut.put("status", "config_missing");
                        configs.add(cfgOut);
                        continue;
                    }
                    Map<String, Object> cfg;
                    try {
                        cfg = Json.parseObject(read(zf, cfgEntry));
                    } catch (RuntimeException e) {
                        cfgOut.put("status", "config_unparsable");
                        configs.add(cfgOut);
                        continue;
                    }
                    Object refmapObj = cfg.get("refmap");
                    if (refmapObj == null) {
                        cfgOut.put("status", "no_refmap_declared");
                        configs.add(cfgOut);
                        continue;
                    }
                    String refmap = String.valueOf(refmapObj);
                    cfgOut.put("refmap", refmap);
                    if (zf.getEntry(refmap) != null) {
                        cfgOut.put("status", "present");
                        // 已经修补过（或 mod 自带）：仍然逐条校验一遍，保证每次 repair 都产出
                        // "哪些映射真的能在运行时类里找到"的证据，而不是只说一句 present。
                        Existing existing = verifyExisting(read(zf, zf.getEntry(refmap)), tiny, mc);
                        cfgOut.put("entries", existing.count);
                        if (!existing.details.isEmpty()) {
                            cfgOut.put("mappings", existing.details);
                        }
                        if (!existing.bad.isEmpty()) {
                            cfgOut.put("unverified", existing.bad);
                        }
                        if (!existing.unknown.isEmpty()) {
                            cfgOut.put("unverifiable", existing.unknown);
                        }
                        entries += existing.count;
                        unresolved += existing.bad.size();
                        unverifiable += existing.unknown.size();
                        configs.add(cfgOut);
                        continue;
                    }
                    missingRefmaps++;
                    cfgOut.put("status", "missing");
                    String pkg = cfg.get("package") == null ? "" : String.valueOf(cfg.get("package"));
                    List<String> classes = new ArrayList<>();
                    for (String key : List.of("mixins", "client", "server")) {
                        Object v = cfg.get(key);
                        if (v instanceof List<?> list) {
                            for (Object o : list) {
                                if (o instanceof String s) {
                                    classes.add(s);
                                }
                            }
                        }
                    }
                    Map<String, Object> mappings = new LinkedHashMap<>();
                    List<Object> details = new ArrayList<>();
                    List<String> unresolvedKeys = new ArrayList<>();
                    for (String rel : classes) {
                        String path = (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/")
                                + rel.replace('.', '/') + ".class";
                        ZipEntry ce = zf.getEntry(path);
                        if (ce == null) {
                            unresolvedKeys.add(path + " (class missing)");
                            continue;
                        }
                        ClassFileInfo ci;
                        try {
                            ci = ClassFileInfo.parse(readBytes(zf, ce));
                        } catch (IOException e) {
                            unresolvedKeys.add(path + " (class unparsable: " + e.getMessage() + ")");
                            continue;
                        }
                        Resolved resolved = resolve(ci, tiny, yarn, mc);
                        if (!resolved.map.isEmpty()) {
                            // Mixin 用 mixin 类的全限定名（斜杠形式，不含 .class）作为 refmap 的 key
                            String key = (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/")
                                    + rel.replace('.', '/');
                            mappings.put(key, resolved.map);
                            entries += resolved.map.size();
                            for (Ref ref : resolved.refs) {
                                Map<String, Object> d = new LinkedHashMap<>();
                                d.put("class", key);
                                d.put("key", ref.key());
                                d.put("value", ref.value());
                                d.put("via", ref.via());
                                d.put("verified", ref.verified());
                                d.put("verdict", ref.verified() ? "verified" : "unverified");
                                details.add(d);
                            }
                        }
                        unresolvedKeys.addAll(resolved.unresolved);
                    }
                    unresolved += unresolvedKeys.size();
                    cfgOut.put("entries", entriesOf(mappings));
                    cfgOut.put("mappings", details);
                    if (!unresolvedKeys.isEmpty()) {
                        cfgOut.put("unresolved", unresolvedKeys);
                    }
                    cfgOut.put("mixin_classes", classes.size());
                    mergeAdd(toWrite, refmap, mappings);
                    configs.add(cfgOut);
                }
            } catch (Exception e) {
                r.put("error", e.toString());
                results.add(r);
                if (verbose) {
                    System.err.println("[refmap-repair] " + jar.getFileName() + " 失败: " + e);
                }
                continue;
            }
            if (!configs.isEmpty()) {
                r.put("configs", configs);
                r.put("entries", entries);
                r.put("unresolved", unresolved);
            }
            if (!dryRun && !toWrite.isEmpty()) {
                try {
                    backup(jar, mods);
                    rewriteJar(jar, toWrite);
                    didPatch = true;
                    patched++;
                } catch (IOException e) {
                    r.put("error", "rewrite failed: " + e);
                    results.add(r);
                    continue;
                }
            }
            if (configs.isEmpty()) {
                continue;
            }
            r.put("patched", didPatch);
            unresolvedTotal += unresolved;
            unverifiableTotal += unverifiable;
            results.add(r);
            if (verbose) {
                System.out.println("[refmap-repair] " + jar.getFileName() + " entries=" + entries
                        + " unresolved=" + unresolved + (didPatch ? " PATCHED" : ""));
            }
        }

        out.put("mods", results);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("jars_scanned", jars.size());
        totals.put("missing_refmaps", missingRefmaps);
        totals.put("patched", patched);
        totals.put("entries", entriesTotal(results));
        totals.put("verified", verifiedTotal(results));
        totals.put("unverified", unknownVerifiedTotal(results));
        totals.put("unresolved", unresolvedTotal);
        totals.put("unverifiable", unverifiableTotal);
        totals.put("origins", originCounts(results));
        totals.put("yarn", yarnOut.get("status"));
        out.put("totals", totals);
        String json = Json.write(out);
        if (report != null) {
            Files.createDirectories(report.getParent());
            Files.writeString(report, json, StandardCharsets.UTF_8);
        }
        System.out.println("[refmap-repair] 缺失 refmap " + missingRefmaps + " 个，修补 " + patched
                + " 个 jar，条目 " + totals.get("entries") + "，校验通过 " + totals.get("verified")
                + "，校验失败 " + totals.get("unverified") + "，无法校验 " + unverifiableTotal
                + "，未解析 " + unresolvedTotal + "，yarn=" + yarnOut.get("status")
                + (dryRun ? "（dry-run）" : ""));
        return 0;
    }

    // ---------------------------------------------------------------- 解析

    private record Ref(String key, String value, String via, boolean verified) {
    }

    private record Resolved(Map<String, String> map, List<Ref> refs, List<String> unresolved) {
    }

    /** 注解里的一个目标选择器：可能是 {@code Lowner;name(desc)}、{@code name(desc)} 或 {@code name:desc}。 */
    private record Sel(String raw, String owner, String name, String desc, boolean isMethod, boolean hasOwner) {
    }

    private static Resolved resolve(ClassFileInfo ci, TinyMappings tiny, YarnMappings yarn, McClasses mc) {
        Map<String, String> map = new LinkedHashMap<>();
        List<Ref> refs = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();

        List<String> targets = new ArrayList<>();
        for (String t : ci.targets) {
            String inter = tiny.toIntermediaryClass(t);
            targets.add(inter == null ? t : inter);
        }
        if (targets.isEmpty()) {
            return new Resolved(map, refs, unresolved);
        }

        for (String s : ci.utf8) {
            if (map.containsKey(s)) {
                continue;
            }
            if (BARE_MEMBER.matcher(s).matches()) {
                Set<String> values = new LinkedHashSet<>();
                for (String t : targets) {
                    TinyMappings.Member m = tiny.byInterName(t, s, null);
                    if (m != null) {
                        values.add(tiny.refValue(t, m));
                    }
                }
                if (values.size() == 1) {
                    String value = values.iterator().next();
                    put(map, refs, s, value, "intermediary", verify(tiny, mc, value));
                } else if (values.size() > 1) {
                    unresolved.add(s + "（多个目标类给出不同映射，拒绝猜）");
                } else {
                    unresolved.add(s + "（目标类里没有这个 intermediary 成员）");
                }
                continue;
            }

            Sel sel = parseSelector(s);
            if (sel == null) {
                continue;
            }
            Set<String> owners = new LinkedHashSet<>();
            if (sel.hasOwner()) {
                String inter = tiny.toIntermediaryClass(sel.owner());
                if (inter == null && yarn != null) {
                    inter = yarn.namedToInterClass(sel.owner());
                }
                if (inter != null) {
                    owners.add(inter);
                }
            } else {
                owners.addAll(targets);
            }
            if (owners.isEmpty()) {
                // owner 不是 MC 类（mod 自己的类）：refmap 不需要它
                continue;
            }

            String interDesc = toIntermediaryDesc(sel.desc(), tiny, yarn);
            Set<String> values = new LinkedHashSet<>();
            String via = null;
            for (String owner : owners) {
                String v = lookup(tiny, yarn, owner, sel.name(), interDesc, sel.isMethod());
                if (v != null) {
                    values.add(v);
                    via = via == null ? "mappings" : via;
                }
            }
            if (values.size() == 1) {
                String value = values.iterator().next();
                put(map, refs, s, value, via, verify(tiny, mc, value));
            } else if (values.size() > 1) {
                unresolved.add(s + "（多个目标类给出不同映射，拒绝猜）");
            } else if (sel.hasOwner()) {
                // 名字不在任何命名空间里（多为 Yarn 名）：owner 换 mojang、名字保持、描述符换 mojang。
                // 不再用"描述符相同就猜一个方法"的兜底——那会把 close 错映射成 destroy。
                String ownerMoj = tiny.toMojangClass(owners.iterator().next());
                String value = "L" + ownerMoj + ";" + sel.name()
                        + (sel.isMethod() ? "" : ":") + tiny.toMojangDesc(sel.desc());
                Boolean ok = verify(tiny, mc, value);
                if (Boolean.TRUE.equals(ok)) {
                    put(map, refs, s, value, "identity", true);
                } else {
                    unresolved.add(s + "（映射表里查不到，身份映射也校验不过）");
                }
            } else {
                unresolved.add(s + "（裸名无法唯一确定目标类）");
            }
        }
        return new Resolved(map, refs, unresolved);
    }

    private static void put(Map<String, String> map, List<Ref> refs, String key, String value,
                            String via, boolean verified) {
        map.put(key, value);
        refs.add(new Ref(key, value, via, verified));
    }

    /**
     * 在指定 owner 下查成员：先 Yarn(named)（仅当名字不是 intermediary 形式），再 intermediary / mojang / official。
     */
    private static String lookup(TinyMappings tiny, YarnMappings yarn, String owner, String name,
                                 String interDesc, boolean isMethod) {
        if (yarn != null && !BARE_MEMBER.matcher(name).matches()) {
            YarnMappings.Member ym = yarn.byNamedName(owner, name, isMethod ? interDesc : null);
            if (ym != null) {
                TinyMappings.Member tm = tiny.byInterName(owner, ym.interName(), ym.interDesc());
                if (tm != null) {
                    return tiny.refValue(owner, tm);
                }
            }
        }
        TinyMappings.Member m = tiny.byInterName(owner, name, isMethod ? interDesc : null);
        if (m == null) {
            m = tiny.byMojangName(owner, name);
        }
        if (m == null) {
            m = tiny.byOfficialName(owner, name);
        }
        return m == null ? null : tiny.refValue(owner, m);
    }

    /**
     * 注解里的描述符可能已经是 intermediary，也可能是 Yarn(named)。统一先翻成 intermediary，
     * 再交给 tiny 查表；Yarn 表里没有的类名原样保留，所以两种输入都能吃。
     */
    private static String toIntermediaryDesc(String desc, TinyMappings tiny, YarnMappings yarn) {
        String d = desc;
        if (yarn != null) {
            d = yarn.namedDescToInterDesc(d);
        }
        return tiny.toIntermediaryDesc(d);
    }

    /** 已存在的 refmap：逐条复核 value 是否真能在运行期类里找到；null 记入 unverifiable 而不是 bad。 */
    private record Existing(int count, List<Object> details, List<String> bad, List<String> unknown) {
    }

    private static Existing verifyExisting(String json, TinyMappings tiny, McClasses mc) {
        List<Object> details = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        int count = 0;
        Map<String, Object> root;
        try {
            root = Json.parseObject(json);
        } catch (RuntimeException e) {
            bad.add("refmap 不是合法 JSON: " + e.getMessage());
            return new Existing(0, details, bad, unknown);
        }
        if (!(root.get("mappings") instanceof Map<?, ?> classes)) {
            return new Existing(0, details, bad, unknown);
        }
        for (Map.Entry<?, ?> cls : classes.entrySet()) {
            if (!(cls.getValue() instanceof Map<?, ?> members)) {
                continue;
            }
            for (Map.Entry<?, ?> m : members.entrySet()) {
                String key = String.valueOf(m.getKey());
                String value = String.valueOf(m.getValue());
                Boolean ok = verify(tiny, mc, value);
                count++;
                if (Boolean.FALSE.equals(ok)) {
                    bad.add(cls.getKey() + " :: " + key + " -> " + value);
                } else if (ok == null) {
                    unknown.add(cls.getKey() + " :: " + key + " -> " + value);
                }
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("class", String.valueOf(cls.getKey()));
                d.put("key", key);
                d.put("value", value);
                d.put("via", "existing");
                d.put("verified", Boolean.TRUE.equals(ok));
                d.put("verdict", ok == null ? "unverifiable" : (ok ? "verified" : "unverified"));
                details.add(d);
            }
        }
        return new Existing(count, details, bad, unknown);
    }

    /** null = 无法判断（例如指向 mod 类或裸名，不在 MC 映射表里），不等于"错"。 */
    private static Boolean verify(TinyMappings tiny, McClasses mc, String value) {
        if (value == null || !value.startsWith("L")) {
            return null;
        }
        int semi = value.indexOf(';');
        if (semi <= 1) {
            return null;
        }
        String owner = value.substring(1, semi);
        String rest = value.substring(semi + 1);
        String name;
        String desc;
        int paren = rest.indexOf('(');
        if (paren >= 0) {
            name = rest.substring(0, paren);
            desc = rest.substring(paren);
        } else {
            int colon = rest.indexOf(':');
            if (colon <= 0) {
                return false;
            }
            name = rest.substring(0, colon);
            desc = rest.substring(colon + 1);
        }
        // refmap value 的三种命名空间混写很常见：
        //   intermediary 全套（carpet 自带 refmap）、mojang 全套（合成结果）、
        //   以及"intermediary owner + mojang 名"这种混合（Connector 重映射一半的产物）。
        // 所以先统一翻译成 mojang 三元组，再去运行期类里核实，最后才回退映射表。
        boolean method = paren >= 0;
        String mojOwner = tiny.toMojangClass(owner);
        String mojDesc = tiny.toMojangDesc(desc);
        String mojName = name;
        // 名字可能是 intermediary（method_/field_，或 record 组件的 comp_），
        // 也可能本来就是 mojang 名——先查表，查得到才翻译。
        TinyMappings.Member member = tiny.byInterName(owner, name, desc);
        if (member == null) {
            // mixin 允许在子类上引用父类声明的成员（carpet 的 AbstractArrow.tick 实际声明在 Entity）
            member = tiny.byInterNameAnywhere(name, desc);
        }
        if (member != null) {
            mojName = member.mojangName();
        }
        if (mc != null) {
            Boolean runtime = mc.hasMember(mojOwner, mojName, mojDesc, method);
            if (runtime != null) {
                return runtime;
            }
        }
        if (tiny.hasMojangMember(mojOwner, mojName, mojDesc)) {
            return Boolean.TRUE;
        }
        // owner 是 netty / DataFixerUpper / JDK 这类映射表里没有的类：无法判断，不能判成错。
        return tiny.knowsMojangClass(mojOwner) ? Boolean.FALSE : null;
    }

    private static Sel parseSelector(String s) {
        if (s.isEmpty() || s.length() > 400) {
            return null;
        }
        if (s.indexOf(' ') >= 0 || s.indexOf('\n') >= 0) {
            return null;
        }
        if (s.startsWith("L") && s.indexOf(';') > 1) {
            int semi = s.indexOf(';');
            String owner = s.substring(1, semi);
            String rest = s.substring(semi + 1);
            int paren = rest.indexOf('(');
            if (paren > 0 && rest.endsWith(")")) {
                String name = rest.substring(0, paren);
                return IDENT.matcher(name).matches()
                        ? new Sel(s, owner, name, rest.substring(paren), true, true) : null;
            }
            if (paren > 0) {
                // 方法描述符带返回类型，( 之前是名字，后面是完整描述符
                String name = rest.substring(0, paren);
                String desc = rest.substring(paren);
                if (IDENT.matcher(name).matches() && desc.indexOf(')') > 0) {
                    return new Sel(s, owner, name, desc, true, true);
                }
            }
            int colon = rest.indexOf(':');
            if (colon > 0) {
                String name = rest.substring(0, colon);
                return IDENT.matcher(name).matches()
                        ? new Sel(s, owner, name, rest.substring(colon + 1), false, true) : null;
            }
            return null;
        }
        if (BARE_METHOD.matcher(s).matches()) {
            int paren = s.indexOf('(');
            String name = s.substring(0, paren);
            return new Sel(s, null, name, s.substring(paren), true, false);
        }
        int colon = s.indexOf(':');
        if (colon > 0 && IDENT.matcher(s.substring(0, colon)).matches()) {
            String desc = s.substring(colon + 1);
            if (desc.startsWith("L") || desc.startsWith("[")) {
                return new Sel(s, null, s.substring(0, colon), desc, false, false);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- Yarn lockfile

    private static Path resolveYarnJar(Path cache, String version, String sha1) throws Exception {
        Path dir = cache != null ? cache : Path.of(System.getProperty("java.io.tmpdir"), "superloader-mappings");
        Files.createDirectories(dir);
        String file = "yarn-" + version + "-v2.jar";
        Path jar = dir.resolve(file);
        if (!Files.isRegularFile(jar)) {
            String url = "https://maven.fabricmc.net/net/fabricmc/yarn/" + version + "/" + file;
            write(url, jar);
        }
        String actual = sha1Of(jar);
        if (!sha1.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(jar);
            throw new IOException("yarn jar sha1 不匹配（期望 " + sha1 + "，实际 " + actual + "），已删除不加载");
        }
        return jar;
    }

    private static void write(String url, Path target) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IOException("下载失败 HTTP " + resp.statusCode() + ": " + url);
        }
        Files.write(target, resp.body());
    }

    private static String sha1Of(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 来源标注

    /** 读 compat/corpus.json 的样本 id/slug，用来区分"评测台样本"和"玩家基线"。 */
    private static Set<String> readCorpusIds(Path corpusJson) {
        Set<String> ids = new LinkedHashSet<>();
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(corpusJson, StandardCharsets.UTF_8));
            // corpus.json 的分组是 main / infra / content_phase2（早期草稿用过 samples），都收。
            for (String group : List.of("main", "infra", "content_phase2", "samples", "negative", "ported_out")) {
                Object list = root.get(group);
                if (!(list instanceof List<?> entries)) {
                    continue;
                }
                for (Object o : entries) {
                    if (!(o instanceof Map<?, ?> m)) {
                        continue;
                    }
                    for (String k : List.of("id", "slug", "mod_id")) {
                        Object v = m.get(k);
                        if (v != null) {
                            ids.add(norm(String.valueOf(v)));
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[refmap-repair] 读 corpus 失败（来源标注降级为 player_baseline）: " + e);
        }
        return ids;
    }

    private static String classify(String id, Set<String> sampleIds) {
        String n = norm(id);
        if (n.equals("connector") || n.equals("forgified_fabric_api") || n.equals("superloader_companion")) {
            return "runtime";
        }
        return sampleIds.contains(n) ? "compat_sample" : "player_baseline";
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    // ---------------------------------------------------------------- 统计

    private static int entriesOf(Map<String, Object> mappings) {
        int n = 0;
        for (Object v : mappings.values()) {
            if (v instanceof Map<?, ?> m) {
                n += m.size();
            }
        }
        return n;
    }

    private static int entriesTotal(List<Object> results) {
        int n = 0;
        for (Object o : results) {
            if (o instanceof Map<?, ?> m && m.get("entries") instanceof Number num) {
                n += num.intValue();
            }
        }
        return n;
    }

    private static int verifiedTotal(List<Object> results) {
        int n = 0;
        for (Object o : results) {
            if (!(o instanceof Map<?, ?> m) || !(m.get("configs") instanceof List<?> cfgs)) {
                continue;
            }
            for (Object c : cfgs) {
                n += countVerified(c, true);
            }
        }
        return n;
    }

    private static int unknownVerifiedTotal(List<Object> results) {
        int n = 0;
        for (Object o : results) {
            if (!(o instanceof Map<?, ?> m) || !(m.get("configs") instanceof List<?> cfgs)) {
                continue;
            }
            for (Object c : cfgs) {
                n += countVerified(c, false);
            }
        }
        return n;
    }

    private static int countVerified(Object cfg, boolean want) {
        if (!(cfg instanceof Map<?, ?> m) || !(m.get("mappings") instanceof List<?> list)) {
            return 0;
        }
        int n = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> d)) {
                continue;
            }
            String verdict = String.valueOf(d.get("verdict"));
            boolean bad = "unverified".equals(verdict)
                    || (verdict.equals("null") && !Boolean.parseBoolean(String.valueOf(d.get("verified"))));
            if (want ? Boolean.parseBoolean(String.valueOf(d.get("verified"))) : bad) {
                n++;
            }
        }
        return n;
    }

    private static int unverifiableTotalOf(List<Object> results) {
        int n = 0;
        for (Object o : results) {
            if (!(o instanceof Map<?, ?> m) || !(m.get("configs") instanceof List<?> cfgs)) {
                continue;
            }
            for (Object c : cfgs) {
                if (!(c instanceof Map<?, ?> cfg) || !(cfg.get("mappings") instanceof List<?> list)) {
                    continue;
                }
                for (Object d : list) {
                    if (d instanceof Map<?, ?> dm && "unverifiable".equals(String.valueOf(dm.get("verdict")))) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private static Map<String, Object> originCounts(List<Object> results) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("player_baseline", 0);
        counts.put("compat_sample", 0);
        counts.put("runtime", 0);
        for (Object o : results) {
            if (o instanceof Map<?, ?> m) {
                String k = String.valueOf(m.get("origin"));
                Object cur = counts.get(k);
                if (cur instanceof Number num) {
                    counts.put(k, num.intValue() + 1);
                }
            }
        }
        return counts;
    }

    // ---------------------------------------------------------------- 文件操作

    private static byte[] readBytes(ZipFile zf, ZipEntry e) throws IOException {
        try (InputStream in = zf.getInputStream(e)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            in.transferTo(bos);
            return bos.toByteArray();
        }
    }

    private static String read(ZipFile zf, ZipEntry e) throws IOException {
        try (InputStream in = zf.getInputStream(e)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            in.transferTo(bos);
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    private static void backup(Path jar, Path mods) throws IOException {
        Path dir = mods.resolve(BACKUP_DIR).resolve("backup");
        Files.createDirectories(dir);
        Path target = dir.resolve(jar.getFileName().toString());
        if (!Files.exists(target)) {
            Files.copy(jar, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
        Path manifest = mods.resolve(BACKUP_DIR).resolve("manifest.txt");
        Files.writeString(manifest, jar.getFileName() + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private static int restoreAll(Path mods) throws IOException {
        Path backupRoot = mods.resolve(BACKUP_DIR);
        Path dir = backupRoot.resolve("backup");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int n = 0;
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                Files.copy(p, mods.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                n++;
            }
        }
        // 还原后清掉备份和 manifest，让状态回到"未修补"：
        // 否则 backup/ 还在会让 backup() 跳过备份，manifest 又被 APPEND 一行，导致重复记录。
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                Files.deleteIfExists(p);
            }
        }
        Files.deleteIfExists(dir);
        Files.deleteIfExists(backupRoot.resolve("manifest.txt"));
        if (Files.isDirectory(backupRoot)) {
            try (var stream = Files.list(backupRoot)) {
                if (stream.findAny().isEmpty()) {
                    Files.deleteIfExists(backupRoot);
                }
            }
        }
        return n;
    }

    private static void rewriteJar(Path jar, List<Map<String, Object>> additions) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                try (InputStream in = zf.getInputStream(e)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    in.transferTo(bos);
                    entries.put(e.getName(), bos.toByteArray());
                }
            }
        }
        for (Map<String, Object> add : additions) {
            String name = String.valueOf(add.get("name"));
            Map<String, Object> mappings = castMap(add.get("mappings"));
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("mappings", mappings);
            entries.put(name, Json.write(root).getBytes(StandardCharsets.UTF_8));
        }
        Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
        try (OutputStream os = Files.newOutputStream(tmp);
             ZipOutputStream zos = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                zos.putNextEntry(ze);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 同一个 refmap 文件可能被多个 mixin config 共用，需要合并而不是覆盖。 */
    private static void mergeAdd(List<Map<String, Object>> toWrite, String name, Map<String, Object> mappings) {
        for (Map<String, Object> m : toWrite) {
            if (name.equals(m.get("name"))) {
                castMap(m.get("mappings")).putAll(mappings);
                return;
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("mappings", mappings);
        toWrite.add(entry);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }
}
