/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client/).
 * Copyright (c) 2021 Meteor Development.
 */

package meteordevelopment.meteorclient;

import meteordevelopment.meteorclient.addons.AddonManager;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.events.meteor.CharTypedEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseButtonEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.DiscordPresence;
import meteordevelopment.meteorclient.utils.Init;
import meteordevelopment.meteorclient.utils.InitStage;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.misc.input.KeyBinds;
import meteordevelopment.meteorclient.utils.network.OnlinePlayers;
import meteordevelopment.orbit.EventBus;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.IEventBus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

public class MeteorClient implements ClientModInitializer {
    public static MinecraftClient mc;
    public static MeteorClient INSTANCE;
    public static final IEventBus EVENT_BUS = new EventBus();
    public static final File FOLDER = new File(FabricLoader.getInstance().getGameDir().toString(), "meteor-client");
    public static final Logger LOG = LogManager.getLogger();

    @Override
    public void onInitializeClient() {
        if (INSTANCE == null) {
            INSTANCE = this;
            return;
        }

        LOG.info("Initializing Meteor Client");

        // Global minecraft client accessor
        mc = MinecraftClient.getInstance();

        // Register event handlers
        EVENT_BUS.registerLambdaFactory("meteordevelopment.meteorclient", (lookupInMethod, klass) -> (MethodHandles.Lookup) lookupInMethod.invoke(null, klass, MethodHandles.lookup()));

        // Pre-load
        Systems.addPreLoadTask(() -> {
            if (!Modules.get().getFile().exists()) {
                Modules.get().get(DiscordPresence.class).toggle();
                Utils.addMeteorPvpToServerList();
            }
        });

        // Pre init
        init(InitStage.Pre);

        // Register module categories
        Categories.init();

        // Load systems
        Systems.init();

        EVENT_BUS.subscribe(this);

        AddonManager.ADDONS.forEach(MeteorAddon::onInitialize);
        Modules.get().sortModules();

        // Load saves
        Systems.load();

        // Post init
        init(InitStage.Post);

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            OnlinePlayers.leave();
            Systems.save();
            GuiThemes.save();
        }));
    }

    // GUI

    private void openClickGui() {
        Tabs.get().get(0).openScreen(GuiThemes.get());
    }

    @EventHandler
    private void onKeyGUI(KeyEvent event) {
        if (event.action == KeyAction.Press && KeyBinds.OPEN_CLICK_GUI.matchesKey(event.key, 0)) {
            if (Utils.canOpenClickGUI()) openClickGui();
        }
    }

    @EventHandler
    private void onMouseButtonGUI(MouseButtonEvent event) {
        if (event.action == KeyAction.Press && event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT && KeyBinds.OPEN_CLICK_GUI.matchesMouse(event.button)) {
            if (Utils.canOpenClickGUI()) openClickGui();
        }
    }

    // Console

    @EventHandler
    private void onCharTyped(CharTypedEvent event) {
        if (mc.currentScreen != null || !Config.get().prefixOpensConsole || Config.get().prefix.isBlank()) return;

        if (event.c == Config.get().prefix.charAt(0)) {
            mc.setScreen(new ChatScreen(Config.get().prefix));
            event.cancel();
        }
    }

    // Reflection initialisation

    private static void init(InitStage initStage) {
        Reflections reflections = new Reflections("meteordevelopment.meteorclient", Scanners.MethodsAnnotated);
        Set<Method> initTasks = reflections.getMethodsAnnotatedWith(Init.class);
        if (initTasks == null || initTasks.size() < 1) return;

        for (Method initTask : initTasks) {
            Init annotation = initTask.getAnnotation(Init.class);
            if (annotation != null && annotation.stage().equals(initStage)) reflectInit(initTask, annotation);
        }
    }

    private static void reflectInit(Method task, Init annotation) {
        Class<?>[] preTasks = annotation.dependencies();

        try {
            if (preTasks == null || preTasks.length < 1) {
                task.invoke(null);
            }
            else {
                for (Class<?> aClass : preTasks) {
                    Set<Method> preInits = new Reflections(aClass).getMethodsAnnotatedWith(Init.class);

                    for (Method preInit : preInits) {
                        Init preInitAnnotation = preInit.getAnnotation(Init.class);

                        if (preInitAnnotation != null && preInitAnnotation.stage().equals(annotation.stage())) {
                            reflectInit(preInit, preInitAnnotation);
                        }
                    }

                    task.invoke(null);
                }
            }
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
    }
}
