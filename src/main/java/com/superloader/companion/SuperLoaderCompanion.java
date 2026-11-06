package com.superloader.companion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
/**
 * SuperLoader Companion —— NeoForge 侧审计采集。
 *
 * 只做一件事：在加载完成后把 NeoForge 公开 API（ModList / LoadingModList）能看到的
 * 运行时事实写成 logs/superloader-report/runtime.json，最终报告由 Python 侧渲染。
 *
 * 不碰 Mixin 内部状态（Mixins.getConfigs 在 ModLauncher 下不可用，已验证）。
 */
@Mod(SuperLoaderCompanion.MODID)
public class SuperLoaderCompanion {
    public static final String MODID = "superloader_companion";
    public static final String VERSION = "0.6.3";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** 类加载器结构探针：这些类分别由哪个 ClassLoader 定义。 */
    private static final String[][] CLASSLOADER_PROBES = {
            {"minecraft", "net.minecraft.world.item.Item"},
            {"minecraft_client", "net.minecraft.client.Minecraft"},
            {"neoforge", "net.neoforged.fml.ModList"},
            {"mixin", "org.spongepowered.asm.mixin.Mixins"},
            {"connector", "org.sinytra.connector.ConnectorEarlyLoader"},
            {"fabric", "net.fabricmc.loader.api.FabricLoader"},
    };

    public SuperLoaderCompanion(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("========== SL COMPANION {} ==========", VERSION);
        // 尽早挂 spy：注册表事件在 common setup 之前就会触发
        EventProbe.installSpies();
        EventProbe.installHooks();
        AxeStripBridge.install();
        modEventBus.addListener(FMLCommonSetupEvent.class, this::onCommonSetup);
        modEventBus.addListener(FMLLoadCompleteEvent.class, this::onLoadComplete);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        // 鏃╂湡蹇収锛氬嵆浣垮悗缁姞杞藉け璐ワ紝涔熷凡缁忔湁鏁版嵁钀界洏
        event.enqueueWork(() -> dump("common_setup"));
    }

    private void onLoadComplete(FMLLoadCompleteEvent event) {
        // 最终快照：所有 mod 均已构造完成
        event.enqueueWork(() -> {
            dump("load_complete");
            EventProbe.snapshot("load_complete");
        });
    }

    private void dump(String stage) {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("schema", 1);
            root.addProperty("companion_version", VERSION);
            root.addProperty("stage", stage);
            root.addProperty("generated_at", OffsetDateTime.now().toString());
            root.addProperty("game_dir", FMLPaths.GAMEDIR.get().toString());

            root.add("system", systemInfo());
            root.add("classloading", classloading());
            root.add("mods", mods());
            root.add("issues", issues());

            Path dir = FMLPaths.GAMEDIR.get().resolve("logs").resolve("superloader-report");
            Files.createDirectories(dir);
            Path target = dir.resolve("runtime.json");
            Path tmp = dir.resolve("runtime.json.tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);

            LOGGER.info("[sl-report] runtime.json written (stage={}, mods={})",
                    stage, root.getAsJsonArray("mods").size());
        } catch (Throwable t) {
            // 报告失败绝不能影响游戏启动
            LOGGER.error("[sl-report] failed to write runtime.json", t);
        }
    }

    private JsonObject systemInfo() {
        JsonObject o = new JsonObject();
        o.addProperty("java_version", System.getProperty("java.version"));
        o.addProperty("java_vendor", System.getProperty("java.vendor"));
        o.addProperty("java_vm", System.getProperty("java.vm.name"));
        o.addProperty("java_home", System.getProperty("java.home"));
        o.addProperty("os_name", System.getProperty("os.name"));
        o.addProperty("os_arch", System.getProperty("os.arch"));
        o.addProperty("os_version", System.getProperty("os.version"));
        o.addProperty("processors", Runtime.getRuntime().availableProcessors());
        o.addProperty("max_memory_mb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        o.addProperty("user_dir", System.getProperty("user.dir"));
        return o;
    }

    private JsonArray classloading() {
        JsonArray arr = new JsonArray();
        ClassLoader selfLoader = SuperLoaderCompanion.class.getClassLoader();
        JsonObject self = new JsonObject();
        self.addProperty("role", "companion");
        self.addProperty("class", SuperLoaderCompanion.class.getName());
        self.addProperty("loader", loaderName(selfLoader));
        self.addProperty("loader_identity", selfLoader == null ? -1 : System.identityHashCode(selfLoader));
        arr.add(self);

        for (String[] probe : CLASSLOADER_PROBES) {
            JsonObject o = new JsonObject();
            o.addProperty("role", probe[0]);
            o.addProperty("class", probe[1]);
            try {
                Class<?> c = Class.forName(probe[1], false, selfLoader);
                ClassLoader cl = c.getClassLoader();
                o.addProperty("found", true);
                o.addProperty("loader", loaderName(cl));
                o.addProperty("loader_identity", cl == null ? -1 : System.identityHashCode(cl));
                o.addProperty("same_as_companion", cl == selfLoader);
            } catch (Throwable t) {
                o.addProperty("found", false);
                o.addProperty("error", t.getClass().getSimpleName());
            }
            arr.add(o);
        }
        return arr;
    }

    private static String loaderName(ClassLoader cl) {
        return cl == null ? "bootstrap" : cl.getClass().getName();
    }

    private JsonArray mods() {
        JsonArray arr = new JsonArray();
        ModList modList = ModList.get();
        if (modList == null) {
            return arr;
        }
        int order = 0;
        for (IModInfo info : modList.getMods()) {
            JsonObject o = new JsonObject();
            o.addProperty("load_order", order++);
            o.addProperty("mod_id", info.getModId());
            o.addProperty("name", info.getDisplayName());
            o.addProperty("version", String.valueOf(info.getVersion()));
            o.addProperty("namespace", info.getNamespace());
            o.addProperty("loaded", modList.isLoaded(info.getModId()));

            try {
                IModFileInfo owning = info.getOwningFile();
                IModFile file = owning == null ? null : owning.getFile();
                if (file != null) {
                    o.addProperty("file", file.getFilePath().toString());
                    o.addProperty("file_name", file.getFileName());
                    o.addProperty("file_type", file.getType() == null ? null : file.getType().name());
                    addDiscovery(o, file);
                } else {
                    o.add("file_name", JsonNull.INSTANCE);
                }
                o.addProperty("license", owning == null ? null : owning.getLicense());
            } catch (Throwable t) {
                o.addProperty("file_error", t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            arr.add(o);
        }
        return arr;
    }

    private void addDiscovery(JsonObject o, IModFile file) {
        try {
            ModFileDiscoveryAttributes attrs = file.getDiscoveryAttributes();
            if (attrs == null) {
                return;
            }
            var locator = attrs.locator();
            o.addProperty("locator", locator == null ? null : locator.getClass().getName());
            IModFile parent = attrs.parent();
            o.addProperty("parent_file", parent == null ? null : parent.getFilePath().toString());
            var reader = attrs.reader();
            o.addProperty("reader", reader == null ? null : reader.getClass().getName());
        } catch (Throwable t) {
            o.addProperty("discovery_error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private JsonArray issues() {
        JsonArray arr = new JsonArray();
        try {
            LoadingModList loading = LoadingModList.get();
            if (loading == null) {
                return arr;
            }
            for (ModLoadingIssue issue : loading.getModLoadingIssues()) {
                JsonObject o = new JsonObject();
                o.addProperty("severity", issue.severity() == null ? null : issue.severity().name());
                o.addProperty("translation_key", issue.translationKey());
                JsonArray args = new JsonArray();
                if (issue.translationArgs() != null) {
                    for (Object a : issue.translationArgs()) {
                        args.add(String.valueOf(a));
                    }
                }
                o.add("translation_args", args);
                Throwable cause = issue.cause();
                o.addProperty("cause", cause == null ? null
                        : cause.getClass().getName() + ": " + cause.getMessage());
                IModInfo affected = issue.affectedMod();
                o.addProperty("mod_id", affected == null ? null : affected.getModId());
                o.addProperty("path", issue.affectedPath() == null ? null : issue.affectedPath().toString());
                arr.add(o);
            }
        } catch (Throwable t) {
            LOGGER.warn("[sl-report] failed to read loading issues: {}", t.toString());
        }
        return arr;
    }
}
