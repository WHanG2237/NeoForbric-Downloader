package com.deepseek.neoforbric.body.client;

import com.deepseek.neoforbric.body.BodyLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.gui.entries.BooleanListEntry;
import me.shedaniel.clothconfig2.gui.entries.IntegerListEntry;
import me.shedaniel.clothconfig2.gui.entries.StringListEntry;
import me.shedaniel.clothconfig2.gui.entries.StringListListEntry;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;


final class NeoForbricClothScreen {
    private static final Path DIR = FMLPaths.CONFIGDIR.get().resolve("neoforbric");

    private static final Path MAIN = DIR.resolve("config.properties");

    private static final Path PORTS = DIR.resolve("ports.properties");

    private static final Path BLACKLIST = DIR.resolve("blacklist.properties");

    private static final String K = "neoforbric.config.";


    private static String lastSaveError;

    private NeoForbricClothScreen() {}


    static void register(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (modContainer, parent) -> create(parent));
    }

    static Screen create(Screen parent) {
        BodyConfigFile config = BodyConfigFile.load(MAIN);
        BodyConfigFile.EntryList portList = BodyConfigFile.readEntries(PORTS);
        BodyConfigFile.EntryList blacklistList = BodyConfigFile.readBlacklist(BLACKLIST);
        List<String> portEntries = new ArrayList<>(portList.entries());
        List<String> blacklist = new ArrayList<>(blacklistList.entries());

        List<String> originalPortEntries = List.copyOf(portEntries);
        List<String> originalBlacklist = List.copyOf(blacklist);
        java.util.concurrent.atomic.AtomicBoolean recommendedDownloads = new java.util.concurrent.atomic.AtomicBoolean();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable(K + "title"))
                .setDoesConfirmSave(true)
                .setSavingRunnable(() -> {
                    if (recommendedDownloads.get()) { config.applyRecommendedDownloads(); }
                    save(config, portList.readable(), portEntries, originalPortEntries,
                            blacklistList.readable(), blacklist, originalBlacklist);
                });

        ConfigEntryBuilder entries = builder.entryBuilder();
        generalCategory(builder, entries, config);
        restartCategory(builder, entries, config);
        transpileCategory(builder, entries, config);
        networkCategory(builder, entries, config, recommendedDownloads);
        portsCategory(builder, entries, portEntries);
        blacklistCategory(builder, entries, blacklist);
        debugCategory(builder, entries, config);
        return builder.build();
    }


    private static void save(BodyConfigFile config, boolean portsReadable, List<String> portEntries,
            List<String> originalPortEntries, boolean blacklistReadable, List<String> blacklist,
            List<String> originalBlacklist) {
        try {
            config.save();


            if (!portEntries.equals(originalPortEntries)) {
                if (!portsReadable) {
                    throw new IOException(PORTS + " could not be read; refusing to overwrite it");
                }
                BodyConfigFile.saveEntries(PORTS, portEntries);
            }
            if (!blacklist.equals(originalBlacklist)) {
                if (!blacklistReadable) {
                    throw new IOException(BLACKLIST + " could not be read; refusing to overwrite it");
                }
                BodyConfigFile.saveBlacklist(BLACKLIST, blacklist);
            }
            lastSaveError = null;
            BodyLog.info("config saved from the in-game screen: %s", MAIN);
        } catch (IOException | RuntimeException e) {
            lastSaveError = e.toString();
            BodyLog.error("config save FAILED, some changes may not have been saved: %s", e);
            BodyLog.gameError(BodyLog.tag() + " settings could not be saved: " + e);
            saveFailedToast();
        }
    }


    private static void saveFailedToast() {
        try {
            SystemToast toast = SystemToast.multiline(Minecraft.getInstance(),
                    new SystemToast.SystemToastId(10_000L),
                    Component.translatable(K + "toast.savefail.title"),
                    Component.translatable(K + "toast.savefail"));
            Minecraft.getInstance().getToasts().addToast(toast);
        } catch (Throwable t) {
            BodyLog.warn("could not show the save-failed toast: %s", t.toString());
        }
    }


    private static void generalCategory(ConfigBuilder builder, ConfigEntryBuilder entries, BodyConfigFile config) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable(K + "category.general"));
        category.addEntry(entries.startTextDescription(Component.translatable(K + "general.hint"))
                .setTooltip(Component.translatable(K + "general.hint.tip"))
                .build());
        category.addEntry(toggle(entries, config, "general.enable", "enable", true));
        category.addEntry(toggle(entries, config, "general.fabric", "fabric.enable", true));
        category.addEntry(toggle(entries, config, "general.forge", "forge.enable", true));
        category.addEntry(toggle(entries, config, "general.backup", "forge.backup", true));
        category.addEntry(toggle(entries, config, "general.keepname", "forge.keepFileName", true));
    }


    private static void restartCategory(ConfigBuilder builder, ConfigEntryBuilder entries, BodyConfigFile config) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable(K + "category.restart"));
        category.addEntry(entries.startTextDescription(Component.translatable(K + "restart.hint")).build());
        category.addEntry(toggle(entries, config, "restart.enable", "restart.enable", true));
        category.addEntry(intField(entries, config, "restart.max", "restart.maxAttempts", 1, 0, 10));
        category.addEntry(intField(entries, config, "restart.cooldown", "restart.cooldownSeconds", 120, 0, 86400));
        category.addEntry(toggle(entries, config, "restart.keepalive", "restart.keepAlive", true));
    }


    private static void transpileCategory(ConfigBuilder builder, ConfigEntryBuilder entries, BodyConfigFile config) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable(K + "category.transpile"));
        category.addEntry(entries.startTextDescription(Component.translatable(K + "transpile.hint")).build());
        category.addEntry(toggle(entries, config, "transpile.crawl", "transpile.crawl.enable", true));
        category.addEntry(toggle(entries, config, "transpile.strict", "transpile.crawl.strict", true));
        category.addEntry(toggle(entries, config, "transpile.deps", "dependencies.autoInstall", true));
        category.addEntry(intField(entries, config, "transpile.depth", "dependencies.maxDepth", 2, 0, 10));
        category.addEntry(toggle(entries, config, "transpile.duplicates", "duplicates.removeRedundant", true));
    }


    private static void networkCategory(ConfigBuilder builder, ConfigEntryBuilder entries, BodyConfigFile config,
            java.util.concurrent.atomic.AtomicBoolean recommendedDownloads) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable(K + "category.network"));
        category.addEntry(entries.startTextDescription(Component.translatable(K + "network.hint")).build());
        category.addEntry(intField(entries, config, "network.deadline", "http.totalDeadlineSeconds", 600, 0, 86400));
        category.addEntry(intField(entries, config, "network.stall", "http.stallTimeoutSeconds", 45, 0, 3600));
        category.addEntry(intField(entries, config, "network.retries", "http.retries", 3, 0, 100));
        category.addEntry(entries.startBooleanToggle(Component.translatable(K + "network.recommended"), false)
                .setDefaultValue(false).setTooltip(Component.translatable(K + "network.recommended.tip"))
                .setSaveConsumer(recommendedDownloads::set).requireRestart().build());
        category.addEntry(intField(entries, config, "network.threads", "http.parallelism", 1, 1, 128));
        category.addEntry(toggle(entries, config, "network.segmented", "http.segmented.enable", false));
        category.addEntry(intField(entries, config, "network.segmented.max", "http.segmented.maxSegments", 2, 2, 8));
        category.addEntry(intField(entries, config, "network.segmented.threshold", "http.segmented.thresholdMb", 16, 1,
                1024));
        category.addEntry(intField(entries, config, "network.segmented.min", "http.segmented.minSegmentMb", 4, 1, 256));
        category.addEntry(intField(entries, config, "network.maxactive", "http.maxActiveRequests", 4, 2, 64));
        category.addEntry(intField(entries, config, "network.maxperhost", "http.maxRequestsPerHost", 4, 1, 32));
        category.addEntry(toggle(entries, config, "network.progress.files", "progress.files", true));
        category.addEntry(toggle(entries, config, "network.mirror", "mirror.enable", true));
        category.addEntry(toggle(entries, config, "network.thirdparty.modrinth",
                "mirror.thirdparty.modrinth.enable", false));
        category.addEntry(toggle(entries, config, "network.thirdparty.github", "mirror.thirdparty.github.enable",
                false));
        category.addEntry(toggle(entries, config, "network.mr.official", "source.modrinth.official.enable", true));
        category.addEntry(toggle(entries, config, "network.mr.mirror", "source.modrinth.mirror.enable", false));
    }


    private static void portsCategory(ConfigBuilder builder, ConfigEntryBuilder entries, List<String> portEntries) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable(K + "category.ports"));
        category.addEntry(entries.startTextDescription(Component.translatable(K + "ports.hint"))
                .setTooltip(Component.translatable(K + "ports.hint.tip.1"),
                        Component.translatable(K + "ports.hint.tip.2"),
                        Component.translatable(K + "ports.hint.tip.3"),
                        Component.translatable(K + "ports.hint.tip.4"),
                        Component.translatable(K + "ports.hint.tip.5"))
                .build());
        category.addEntry(entries.startStrList(Component.translatable(K + "ports.list"), portEntries)
                .setExpanded(true)
                .setDefaultValue(new ArrayList<>(portEntries))
                .setTooltip(Component.translatable(K + "ports.list.tip"))
                .setCellErrorSupplier(line -> BodyConfigFile.isValidEntry(line)
                        ? Optional.empty()
                        : Optional.of(Component.translatable(K + "ports.error")))
                .setSaveConsumer(newLines -> {
                    portEntries.clear();
                    portEntries.addAll(newLines);
                })
                .requireRestart()
                .build());
    }


    private static void blacklistCategory(ConfigBuilder builder, ConfigEntryBuilder entries, List<String> blacklist) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable(K + "category.blacklist"));
        category.addEntry(entries.startTextDescription(Component.translatable(K + "blacklist.hint"))
                .setTooltip(Component.translatable(K + "blacklist.hint.tip.1"),
                        Component.translatable(K + "blacklist.hint.tip.2"),
                        Component.translatable(K + "blacklist.hint.tip.3"),
                        Component.translatable(K + "blacklist.hint.tip.4"))
                .build());
        category.addEntry(entries.startStrList(Component.translatable(K + "blacklist.list"), blacklist)
                .setExpanded(true)
                .setDefaultValue(new ArrayList<>(blacklist))
                .setTooltip(Component.translatable(K + "blacklist.list.tip"))
                .setCellErrorSupplier(line -> BodyConfigFile.isValidBlacklistEntry(line)
                        ? Optional.empty()
                        : Optional.of(Component.translatable(K + "blacklist.error")))
                .setSaveConsumer(newLines -> {
                    blacklist.clear();
                    blacklist.addAll(newLines);
                })
                .requireRestart()
                .build());
    }


    private static void debugCategory(ConfigBuilder builder, ConfigEntryBuilder entries, BodyConfigFile config) {
        ConfigCategory category = builder.getOrCreateCategory(Component.translatable(K + "category.debug"));
        category.addEntry(entries.startTextDescription(Component.translatable(K + "debug.hint")).build());
        category.addEntry(toggle(entries, config, "debug.progress", "debug.progress", false));


        SubCategoryBuilder lastRun = entries.startSubCategory(Component.translatable(K + "debug.lastrun"));
        lastRun.setExpanded(true);
        lastRun.setTooltip(Component.translatable(K + "debug.lastrun.tip"));
        lastRun.add(entries.startTextDescription(lastRunText()).build());
        lastRun.add(entries.startTextDescription(logsLine()).build());
        if (lastSaveError != null) {
            lastRun.add(entries.startTextDescription(
                    Component.translatable(K + "debug.savefail", lastSaveError)).build());
        }
        category.addEntry(lastRun.build());

        category.addEntry(strListField(entries, config, "debug.hide.mods", "hide.mods",
                "connector,connectorextras,fabric_api"));
        category.addEntry(strListField(entries, config, "debug.hide.prefixes", "hide.prefixes",
                "connector,connectorextras,fabric_api,fabric_,fabric_convention_tags"));
        category.addEntry(strListField(entries, config, "debug.hide.files", "hide.files",
                "connector-,connectorextras-,forgified-fabric-api,fabric-"));
        category.addEntry(strListField(entries, config, "debug.hide.names", "hide.names",
                "Sinytra Connector,Connector Extras,Forgified Fabric API"));
        category.addEntry(intField(entries, config, "debug.resume.days", "http.resumeKeepDays", 3, 0, 3650));
        category.addEntry(intField(entries, config, "debug.resume.mb", "http.resumeCacheMaxMb", 500, 0, 1048576));
        category.addEntry(intField(entries, config, "debug.notfound.days", "cache.notFoundDays", 7, 1, 3650));
        category.addEntry(intField(entries, config, "debug.timeout.connect", "http.connectTimeoutMs", 8000, 0, 600000));
        category.addEntry(intField(entries, config, "debug.timeout.read", "http.readTimeoutMs", 20000, 0, 3600000));
        category.addEntry(intField(entries, config, "debug.timeout.request", "http.downloadTimeoutSeconds", 600, 0, 86400));
        category.addEntry(intField(entries, config, "debug.timeout.confirm", "restart.confirmMs", 1500, 0, 60000));
        category.addEntry(intField(entries, config, "debug.startup", "startup.stallMs", 1200, 0, 60000));
    }


    private static Component lastRunText() {
        Path report = FMLPaths.GAMEDIR.get().resolve("logs").resolve("neoforbric-report.json");
        if (!Files.isRegularFile(report)) {
            return Component.translatable(K + "debug.lastrun.none");
        }
        try {
            String text = Files.readString(report, StandardCharsets.UTF_8);
            String time = value(text, "\"time\": \"");
            String summary = value(text, "\"summary\": \"");
            int decisions = count(text, "\"action\":");
            boolean notesEmpty = text.contains("\"notes\": []");
            boolean errorsEmpty = text.contains("\"errors\": []");
            return Component.translatable(K + "debug.lastrun.text",
                    time.isEmpty() ? Component.translatable(K + "debug.lastrun.notime")
                            : Component.literal(prettyTime(time)),
                    summary.isEmpty() ? Component.translatable(K + "debug.lastrun.nosummary")
                            : Component.literal(summary),
                    String.valueOf(decisions),
                    Component.translatable(K + (notesEmpty ? "debug.lastrun.none.short" : "debug.lastrun.warn")),
                    Component.translatable(K + (errorsEmpty ? "debug.lastrun.none.short" : "debug.lastrun.error")));
        } catch (IOException e) {
            return Component.translatable(K + "debug.lastrun.failed", e.toString());
        }
    }


    private static String prettyTime(String iso) {
        String trimmed = iso.length() > 16 ? iso.substring(0, 16) : iso;
        return trimmed.replace('T', ' ');
    }


    private static Component logsLine() {
        Path logs = FMLPaths.GAMEDIR.get().resolve("logs");
        return Component.translatable(K + "debug.logs", logs.toString())
                .withStyle(Style.EMPTY.withClickEvent(
                        new ClickEvent(ClickEvent.Action.OPEN_FILE, logs.toString())));
    }


    private static String value(String text, String prefix) {
        int at = text.indexOf(prefix);
        if (at < 0) {
            return "";
        }
        int start = at + prefix.length();
        int end = text.indexOf('"', start);
        return end < 0 ? "" : text.substring(start, end);
    }

    private static int count(String text, String needle) {
        int total = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            total++;
            at = text.indexOf(needle, at + needle.length());
        }
        return total;
    }


    private static BooleanListEntry toggle(ConfigEntryBuilder entries, BodyConfigFile config, String key,
            String configKey, boolean fallback) {
        return entries.startBooleanToggle(Component.translatable(K + key), config.getBool(configKey, fallback))
                .setDefaultValue(fallback)
                .setTooltip(tip(key, configKey))
                .setSaveConsumer(value -> config.set(configKey, value))
                .requireRestart()
                .build();
    }

    private static IntegerListEntry intField(ConfigEntryBuilder entries, BodyConfigFile config, String key,
            String configKey, int fallback, int min, int max) {
        return entries.startIntField(Component.translatable(K + key), config.getInt(configKey, fallback))
                .setDefaultValue(fallback)
                .setMin(min)
                .setMax(max)
                .setTooltip(tip(key, configKey))
                .setSaveConsumer(value -> config.set(configKey, value))
                .requireRestart()
                .build();
    }

    private static StringListEntry strField(ConfigEntryBuilder entries, BodyConfigFile config, String key,
            String configKey, String fallback) {
        return entries.startStrField(Component.translatable(K + key), config.getRaw(configKey, fallback))
                .setDefaultValue(fallback)
                .setTooltip(tip(key, configKey))
                .setSaveConsumer(value -> config.set(configKey, value))
                .requireRestart()
                .build();
    }

    private static StringListListEntry strListField(ConfigEntryBuilder entries, BodyConfigFile config, String key,
            String configKey, String fallback) {
        List<String> values = new ArrayList<>();
        for (String part : config.getRaw(configKey, fallback).split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return entries.startStrList(Component.translatable(K + key), values)
                .setExpanded(false)
                .setDefaultValue(new ArrayList<>(values))
                .setTooltip(tip(key, configKey))
                .setSaveConsumer(lines -> config.set(configKey, String.join(",", lines)))
                .requireRestart()
                .build();
    }


    private static Component[] tip(String key, String configKey) {
        return new Component[] {
            Component.translatable(K + key + ".tip"),
            Component.translatable(K + "tip.key", configKey),
        };
    }
}
