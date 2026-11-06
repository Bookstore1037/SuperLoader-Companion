package com.superloader.companion;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

import net.neoforged.bus.EventBus;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;

/**
 * events —— 用公开 API 做事件审计，不碰游戏内部状态。
 *
 * 为什么不用 mixin 计数：实测 Mixin 服务只处理 TransformingClassLoader 上的类，
 * `net.neoforged.bus.*` 属于 cpw.mods.cl.ModuleClassLoader 的模块层，注入完全不生效
 * （EventBus / ListenerList 两个探针都没被 Mixing，自身类却可以）。
 * 所以这里退回公开 API：
 *
 *   v1（schema 1）：
 *     1. 静态注册视图：扫 ModList 的 @SubscribeEvent 声明，反射拿方法参数类型 = 事件类
 *     2. 触发计数：对每个事件类在 GAME bus 上注册一个只计数的 spy listener
 *     3. 尽力而为：反射读 EventBus.listeners，能读到就给出精确的监听器注册数
 *
 *   v2（schema 2，0.4.1）：
 *     4. 注册表全量挂 spy：load_complete 后（以及每次 periodic）遍历 GAME bus 的
 *        listenerLists keys，给"有注册但扫不到 @SubscribeEvent"的程序化 addListener
 *        事件类也挂上 spy，覆盖率不再受静态扫描限制
 *     5. 事件耗时：每个事件类挂两个 spy —— HIGHEST 记开始、LOWEST 记结束，
 *        两个探针之间的间隔 ≈ 其他监听器的处理耗时（同优先级的监听器与我们的探针
 *        相对顺序不退让，所以是近似值，不是 EventBus.post 的总墙钟时间）
 *
 * 已知边界（v2 明确不覆盖）：
 *   - 只监听 GAME bus；MOD bus 的事件（注册在 MOD bus 上）在 GAME bus 反射表里根本
 *     不存在，强行挂 spy 只会得到假的 never_triggered，因此保持 unspied
 *   - 同一个事件类在同一线程上嵌套 post 时，只有最外层采样耗时（内层只计数）
 */
public final class EventProbe {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final String SUBSCRIBE_EVENT = "net.neoforged.bus.api.SubscribeEvent";
    private static final String MOD_BUS_EVENT_PREFIX = "net.neoforged.fml.event.";

    /** 事件类 -> 被触发次数（LOWEST spy 每被调用一次 +1）。 */
    private static final ConcurrentHashMap<Class<?>, AtomicLong> POSTS = new ConcurrentHashMap<>();
    /** 事件类 -> 声明来源（mod:class#method）。 */
    private static final ConcurrentHashMap<Class<?>, Set<String>> DECLARED = new ConcurrentHashMap<>();
    /** 已经挂过 spy 的事件类。 */
    private static final Set<Class<?>> SPIED = ConcurrentHashMap.newKeySet();
    /** 事件类 -> 我们挂上去的 listener 个数（1 或 2），算注册数时要扣掉。 */
    private static final ConcurrentHashMap<Class<?>, Integer> SPY_LISTENERS = new ConcurrentHashMap<>();

    // ---- 耗时采样：每类 3 个计数器 + 每线程一个 [startNanos, depth] ----
    private static final ConcurrentHashMap<Class<?>, AtomicLong> TIMING_COUNT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, AtomicLong> TIMING_TOTAL_NS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, AtomicLong> TIMING_MAX_NS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, ThreadLocal<long[]>> TIMING_START = new ConcurrentHashMap<>();

    private static final AtomicBoolean SPIES = new AtomicBoolean();
    private static final AtomicBoolean HOOKS = new AtomicBoolean();
    /** shutdown 时注册表可能已被清空，保留最后一次成功读取的结果。 */
    private static volatile Map<Class<?>, Integer> LAST_REGISTRATIONS;
    private static final OffsetDateTime OBSERVED_SINCE = OffsetDateTime.now();

    private EventProbe() {
    }

    // ------------------------------------------------------------ 采集

    public static void installSpies() {
        if (!SPIES.compareAndSet(false, true)) {
            return;
        }
        try {
            ModList modList = ModList.get();
            if (modList == null) {
                LOGGER.warn("[sl-events] ModList unavailable, skip spy installation");
                return;
            }
            ClassLoader loader = EventProbe.class.getClassLoader();
            int declarations = 0;
            int spies = 0;

            for (ModFileScanData data : modList.getAllScanData()) {
                String owner = ownerOf(data);
                for (ModFileScanData.AnnotationData annotation : data.getAnnotations()) {
                    if (annotation.targetType() != java.lang.annotation.ElementType.METHOD) {
                        continue;
                    }
                    if (!SUBSCRIBE_EVENT.equals(annotation.annotationType().getClassName())) {
                        continue;
                    }
                    String className = annotation.clazz().getClassName();
                    Class<?> declaring;
                    try {
                        declaring = Class.forName(className, false, loader);
                    } catch (Throwable t) {
                        continue;
                    }
                    for (Method method : declaring.getDeclaredMethods()) {
                        if (method.getParameterCount() != 1 || !method.isAnnotationPresent(SubscribeEvent.class)) {
                            continue;
                        }
                        Class<?> eventClass = method.getParameterTypes()[0];
                        if (!Event.class.isAssignableFrom(eventClass)) {
                            continue;
                        }
                        declarations++;
                        DECLARED.computeIfAbsent(eventClass, k -> ConcurrentHashMap.newKeySet())
                                .add(owner + ":" + className + "#" + method.getName());
                        if (registerSpy(eventClass)) {
                            spies++;
                        }
                    }
                }
            }
            LOGGER.info("[sl-events] spy installation (declarations) done: declarations={}, bus listeners={}, event classes={}",
                    declarations, spies, SPIED.size());
        } catch (Throwable t) {
            LOGGER.error("[sl-events] failed to install spies", t);
        }
    }

    /**
     * 注册表全量补挂：GAME bus 上所有注册过的事件类都挂 spy。
     *
     * 这是 v2 的核心 —— v1 只认 @SubscribeEvent 扫描到的事件类，
     * 程序化 `bus.addListener(...)` 的 31 个类完全看不到。
     * 返回本次新挂上的事件类数量。
     */
    static int attachRegistrySpies() {
        Map<Class<?>, Integer> registrations = readRegistrations();
        if (registrations == null) {
            return 0;
        }
        int added = 0;
        for (Class<?> eventClass : registrations.keySet()) {
            if (!Event.class.isAssignableFrom(eventClass)) {
                continue;
            }
            if (registerSpy(eventClass)) {
                added++;
            }
        }
        if (added > 0) {
            LOGGER.info("[sl-events] registry sweep attached spies for {} event classes", added);
        }
        return added;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean registerSpy(Class<?> eventClass) {
        if (eventClass.getName().startsWith(MOD_BUS_EVENT_PREFIX)) {
            // MOD bus 的事件注册到 GAME bus 上永远不会触发，直接跳过避免假 neverTriggered
            return false;
        }
        if (!SPIED.add(eventClass)) {
            return false;
        }

        AtomicLong posts = new AtomicLong();
        AtomicLong count = new AtomicLong();
        AtomicLong total = new AtomicLong();
        AtomicLong max = new AtomicLong();
        ThreadLocal<long[]> start = ThreadLocal.withInitial(() -> new long[2]);

        POSTS.putIfAbsent(eventClass, posts);
        TIMING_COUNT.put(eventClass, count);
        TIMING_TOTAL_NS.put(eventClass, total);
        TIMING_MAX_NS.put(eventClass, max);
        TIMING_START.put(eventClass, start);

        Consumer<Event> onStart = event -> {
            long[] holder = start.get();
            if (holder[1] == 0L) {
                holder[0] = System.nanoTime();
            }
            holder[1]++;
        };
        Consumer<Event> onEnd = event -> {
            long now = System.nanoTime();
            long[] holder = start.get();
            if (holder[1] > 0L) {
                holder[1]--;
                if (holder[1] == 0L && holder[0] != 0L) {
                    long delta = now - holder[0];
                    holder[0] = 0L;
                    if (delta >= 0L) {
                        count.incrementAndGet();
                        total.addAndGet(delta);
                        long previous;
                        do {
                            previous = max.get();
                        } while (delta > previous && !max.compareAndSet(previous, delta));
                    }
                }
            }
            posts.incrementAndGet();
        };

        IEventBus bus = NeoForge.EVENT_BUS;
        int listeners = 0;
        try {
            bus.addListener(EventPriority.HIGHEST, (Class) eventClass, (Consumer) onStart);
            listeners = 1;
        } catch (Throwable t) {
            LOGGER.debug("[sl-events] HIGHEST spy unavailable for {}: {}",
                    eventClass.getName(), t.toString());
        }
        try {
            bus.addListener(EventPriority.LOWEST, (Class) eventClass, (Consumer) onEnd);
            listeners++;
        } catch (Throwable t) {
            LOGGER.debug("[sl-events] LOWEST spy unavailable for {}: {}",
                    eventClass.getName(), t.toString());
            if (listeners == 1) {
                // 计时探针挂不上就退化成纯计数，至少保证 posts 有意义
                try {
                    bus.addListener((Class) eventClass, (Consumer) onEnd);
                    listeners++;
                } catch (Throwable fallback) {
                    LOGGER.debug("[sl-events] counter spy unavailable for {}: {}",
                            eventClass.getName(), fallback.toString());
                }
            }
        }

        if (listeners == 0) {
            SPIED.remove(eventClass);
            POSTS.remove(eventClass);
            TIMING_COUNT.remove(eventClass);
            TIMING_TOTAL_NS.remove(eventClass);
            TIMING_MAX_NS.remove(eventClass);
            TIMING_START.remove(eventClass);
            return false;
        }
        SPY_LISTENERS.put(eventClass, listeners);
        return true;
    }

    private static String ownerOf(ModFileScanData data) {
        try {
            for (var fileInfo : data.getIModInfoData()) {
                List<IModInfo> mods = fileInfo.getMods();
                if (mods != null && !mods.isEmpty()) {
                    return mods.get(0).getModId();
                }
            }
        } catch (Throwable ignored) {
            // 兜底
        }
        return "?";
    }

    // ------------------------------------------------------------ 快照

    public static void installHooks() {
        if (!HOOKS.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(() -> snapshot("shutdown"), "sl-events-shutdown"));
        } catch (Throwable t) {
            LOGGER.warn("[sl-events] failed to install shutdown hook: {}", t.toString());
        }
        Thread periodic = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException e) {
                    return;
                }
                snapshot("periodic");
            }
        }, "sl-events-periodic");
        periodic.setDaemon(true);
        periodic.start();
    }

    public static synchronized void snapshot(String stage) {
        try {
            // v2：先补齐 GAME bus 上"程序化注册"的事件类（shutdown 时不再改注册表）
            if (!"shutdown".equals(stage)) {
                attachRegistrySpies();
            }

            JsonObject root = new JsonObject();
            root.addProperty("schema", 2);
            root.addProperty("stage", stage);
            root.addProperty("generated_at", OffsetDateTime.now().toString());
            root.addProperty("method", "public-api spies: @SubscribeEvent scan + GAME bus registry sweep; "
                    + "HIGHEST/LOWEST probes for timing");
            root.addProperty("observed_since", OBSERVED_SINCE.toString());

            Map<Class<?>, Integer> registrations = readRegistrations();
            if (registrations == null) {
                registrations = new LinkedHashMap<>();
            }
            if (LAST_REGISTRATIONS != null) {
                Map<Class<?>, Integer> merged = new LinkedHashMap<>(LAST_REGISTRATIONS);
                registrations.forEach((k, v) -> merged.merge(k, v, Math::max));
                registrations = merged;
            }
            if (!registrations.isEmpty()) {
                LAST_REGISTRATIONS = registrations;
            }
            Map<Class<?>, Integer> effective = registrations.isEmpty() ? null : registrations;
            root.addProperty("registration_view", effective == null ? "unavailable" : "reflection");

            Set<Class<?>> all = new LinkedHashSet<>(DECLARED.keySet());
            all.addAll(POSTS.keySet());
            all.addAll(registrations.keySet());

            List<JsonObject> events = new ArrayList<>();
            int never = 0;
            int spied = 0;
            int timed = 0;
            for (Class<?> eventClass : all) {
                boolean isSpied = SPIED.contains(eventClass);
                if (isSpied) {
                    spied++;
                }
                long posts = isSpied ? POSTS.getOrDefault(eventClass, new AtomicLong()).get() : 0L;
                Set<String> declared = DECLARED.get(eventClass);

                Integer registered = registrations.get(eventClass);
                if (registered != null && isSpied) {
                    // 注册数里包含我们自己挂的 1~2 个 spy
                    registered = Math.max(0, registered - SPY_LISTENERS.getOrDefault(eventClass, 1));
                }

                JsonObject o = new JsonObject();
                o.addProperty("event", eventClass.getName());
                o.addProperty("spied", isSpied);
                if (registered == null) {
                    o.add("registered", JsonNull.INSTANCE);
                } else {
                    o.addProperty("registered", registered);
                }
                if (isSpied) {
                    o.addProperty("posts", posts);
                } else {
                    o.add("posts", JsonNull.INSTANCE);
                }

                JsonObject timing = isSpied ? timingOf(eventClass) : null;
                if (timing == null) {
                    o.add("timing", JsonNull.INSTANCE);
                } else {
                    o.add("timing", timing);
                    timed++;
                }

                boolean neverTriggered = isSpied && posts == 0L && (registered == null || registered > 0);
                o.addProperty("never_triggered", neverTriggered);
                if (neverTriggered) {
                    never++;
                }
                JsonArray from = new JsonArray();
                if (declared != null) {
                    declared.stream().sorted().forEach(from::add);
                }
                o.add("declared_by", from);
                events.add(o);
            }
            events.sort(Comparator
                    .comparingLong((JsonObject o) -> {
                        var p = o.get("posts");
                        return p == null || p.isJsonNull() ? -1L : p.getAsLong();
                    }).reversed()
                    .thenComparing(o -> o.get("event").getAsString()));

            JsonArray arr = new JsonArray();
            events.forEach(arr::add);
            root.add("events", arr);

            JsonObject totals = new JsonObject();
            totals.addProperty("event_classes", events.size());
            totals.addProperty("spied", spied);
            totals.addProperty("never_triggered", never);
            totals.addProperty("timed", timed);
            root.add("totals", totals);

            Path dir = FMLPaths.GAMEDIR.get().resolve("logs").resolve("superloader-report");
            Files.createDirectories(dir);
            Path target = dir.resolve("events.json");
            Path tmp = dir.resolve("events.json.tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("[sl-events] events.json written (stage={}, classes={}, spied={}, timed={}, never={})",
                    stage, events.size(), spied, timed, never);
        } catch (Throwable t) {
            LOGGER.error("[sl-events] failed to write events.json", t);
        }
    }

    /** 事件类的耗时聚合；样本数为 0 返回 null。 */
    private static JsonObject timingOf(Class<?> eventClass) {
        AtomicLong count = TIMING_COUNT.get(eventClass);
        if (count == null || count.get() <= 0L) {
            return null;
        }
        JsonObject timing = new JsonObject();
        timing.addProperty("count", count.get());
        timing.addProperty("total_ns", TIMING_TOTAL_NS.getOrDefault(eventClass, new AtomicLong()).get());
        timing.addProperty("max_ns", TIMING_MAX_NS.getOrDefault(eventClass, new AtomicLong()).get());
        return timing;
    }

    /** GAME bus 上"事件类 -> 监听器数"的精确视图；读不到返回 null。
     *
     * EventBus.listeners 是按"监听器对象"索引的（用于 unregister），
     * 真正的"事件类 -> ListenerList"在 listenerLists（LockHelper）里。
     */
    private static Map<Class<?>, Integer> readRegistrations() {
        try {
            Field field = EventBus.class.getDeclaredField("listenerLists");
            field.setAccessible(true);
            Object bus = NeoForge.EVENT_BUS;
            if (!(bus instanceof EventBus eventBus)) {
                return null;
            }
            Object helper = field.get(eventBus);
            if (helper == null) {
                return null;
            }
            Method getReadMap = helper.getClass().getDeclaredMethod("getReadMap");
            getReadMap.setAccessible(true);
            Object raw = getReadMap.invoke(helper);
            if (!(raw instanceof Map<?, ?> map)) {
                return null;
            }
            Map<Class<?>, Integer> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof Class<?> cls
                        && entry.getValue() instanceof net.neoforged.bus.ListenerList list) {
                    var listeners = list.getListeners();
                    int count = listeners == null ? 0 : listeners.length;
                    if (count > 0) {
                        out.put(cls, count);
                    }
                }
            }
            return out;
        } catch (Throwable t) {
            LOGGER.info("[sl-events] registration reflection unavailable: {}", t.toString());
            return null;
        }
    }
}
