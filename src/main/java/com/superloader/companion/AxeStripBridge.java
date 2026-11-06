package com.superloader.companion;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * bclib 去皮桥（betterend / betternether 等 betterx 系模组的通用兼容层）。
 *
 * 背景：bclib 的 `AxeItemMixin` 把逻辑挂在 `AxeItem.getStripped(BlockState)` 上，靠
 * `AxeCanStrip` 接口给自定义方块提供去皮结果。NeoForge 21.1 起斧头不再调用 `getStripped`，
 * 改走 `BlockState.getToolModifiedState(...)` → 数据映射 `NeoForgeDataMaps.STRIPPABLES`，
 * 于是 bclib 的注入变成死代码：不报错、也不生效。
 *
 * 修复方式：监听 NeoForge 的 `BlockToolModificationEvent`（`EventHooks.onToolUse` 会为
 * 斧头能力发这个事件），当方块实现了 bclib 的 `AxeCanStrip` 且本次还没有最终状态时，
 * 用它给的 `strippedState(...)` 作为结果。
 *
 * 为什么这是"一类调用"而不是单 mod 特判：任何依赖 bclib `AxeCanStrip` 的模组
 * （betterend、betternether 以及后续 betterx 系）都会自动被覆盖；不认 mod id、不认 jar 名。
 *
 * 为什么用"接口名字符串"而不是 Class.forName 做判断：Connector 用独立加载器加载 Fabric 模组，
 * NeoForge 模组不一定看得到 bclib 的类；按名字比较接口再反射调方法，跨加载器也能工作。
 */
public final class AxeStripBridge {
    private static final String AXE_CAN_STRIP = "org.betterx.bclib.interfaces.tools.AxeCanStrip";

    private static final Map<Class<?>, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private static boolean disabled;
    private static boolean warned;
    private static boolean sawFirstEvent;
    private static int hits;
    private static int axeEventsLogged;

    private AxeStripBridge() {
    }

    public static void install() {
        if (Boolean.getBoolean("superloader.axeBridge.disabled")
                || "1".equals(System.getenv("SL_NO_AXE_BRIDGE"))) {
            disabled = true;
            SuperLoaderCompanion.LOGGER.info("[sl-bridge] axe strip bridge disabled by config");
            return;
        }
        NeoForge.EVENT_BUS.addListener(AxeStripBridge::onToolUse);
        SuperLoaderCompanion.LOGGER.info("[sl-bridge] axe strip bridge installed (bclib AxeCanStrip -> BlockToolModificationEvent)");
        // 诊断：companion 的加载器能不能直接看到 bclib 的接口（预期 Connector 下看不到）
        for (ClassLoader cl : new ClassLoader[]{Thread.currentThread().getContextClassLoader(),
                AxeStripBridge.class.getClassLoader()}) {
            try {
                Class.forName(AXE_CAN_STRIP, false, cl);
                SuperLoaderCompanion.LOGGER.info("[sl-bridge] bclib AxeCanStrip visible via {}", cl);
            } catch (Throwable t) {
                SuperLoaderCompanion.LOGGER.info("[sl-bridge] bclib AxeCanStrip NOT visible via {} ({})",
                        cl, t.getClass().getSimpleName());
            }
        }
    }

    private static void onToolUse(BlockEvent.BlockToolModificationEvent event) {
        if (disabled) {
            return;
        }
        boolean axe = event.getItemAbility() == ItemAbilities.AXE_STRIP;
        if (!sawFirstEvent) {
            sawFirstEvent = true;
            SuperLoaderCompanion.LOGGER.info("[sl-bridge] first tool event: ability={} state={} simulated={}",
                    event.getItemAbility().name(), event.getState(), event.isSimulated());
        }
        if (!axe) {
            return;
        }
        BlockState state = event.getState();
        if (state == null || event.isSimulated()) {
            return;
        }
        BlockState current = event.getFinalState();
        if (axeEventsLogged < 5) {
            axeEventsLogged++;
            SuperLoaderCompanion.LOGGER.info("[sl-bridge] AXE_STRIP event on {} (finalState={})",
                    state.getBlock().getClass().getName(), current);
        }
        // 注意：NeoForge 的事件默认把 finalState 初始化成"原方块状态"，不是 null。
        // 只有别人真的换成了另一个方块时才算"已有结果"，这时候不抢。
        if (current != null && current.getBlock() != state.getBlock()) {
            return;
        }
        BlockState stripped = strippedViaBclib(state);
        if (stripped == null) {
            return;
        }
        event.setFinalState(stripped);
        hits++;
        if (hits <= 5) {
            SuperLoaderCompanion.LOGGER.info("[sl-bridge] axe strip: {} -> {} (hit #{})", state, stripped, hits);
        }
    }

    private static BlockState strippedViaBclib(BlockState state) {
        Block block = state.getBlock();
        if (!implementsAxeCanStrip(block.getClass())) {
            return null;
        }
        Method m = METHOD_CACHE.get(block.getClass());
        if (m != null) {
            return invoke(m, block, state);
        }
        try {
            m = block.getClass().getMethod("strippedState", BlockState.class);
            METHOD_CACHE.put(block.getClass(), m);
        } catch (NoSuchMethodException e) {
            if (!warned) {
                warned = true;
                SuperLoaderCompanion.LOGGER.warn("[sl-bridge] {} implements AxeCanStrip but has no public strippedState(BlockState)",
                        block.getClass().getName());
            }
            return null;
        }
        return invoke(m, block, state);
    }

    private static BlockState invoke(Method m, Block block, BlockState state) {
        try {
            Object out = m.invoke(block, state);
            return out instanceof BlockState bs ? bs : null;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                SuperLoaderCompanion.LOGGER.warn("[sl-bridge] axe strip bridge failed for {}", block, t);
            }
            return null;
        }
    }

    /** 按接口名字符串判断（不 Class.forName，避免 Connector 独立类加载器的问题）。 */
    private static boolean implementsAxeCanStrip(Class<?> c) {
        for (Class<?> i : c.getInterfaces()) {
            if (i.getName().equals(AXE_CAN_STRIP) || implementsAxeCanStrip(i)) {
                return true;
            }
        }
        Class<?> s = c.getSuperclass();
        return s != null && implementsAxeCanStrip(s);
    }
}
