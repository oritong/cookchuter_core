/* SPDX-License-Identifier: GPL-3.0-only */
package com.oritong.cookchuter_core.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.oritong.cookchuter_core.CookchuterCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = CookchuterCore.MOD_ID, value = Dist.CLIENT)
public final class DevelopmentNotice {
    private static final String TEXT = "该整合包[名称待定]正在开发中    测试画面不代表最终品质    解释权归哦里冻、Water2H2O所有";
    private static boolean visible;
    private static boolean combinationWasDown;

    private DevelopmentNotice() {
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();
        long window = minecraft.getWindow().getWindow();
        boolean combinationDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_D)
                && eitherKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT)
                && eitherKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)
                && eitherKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT);
        if (combinationDown && !combinationWasDown && minecraft.isWindowActive()
                && minecraft.screen == null && minecraft.player != null) {
            visible = !visible;
        }
        combinationWasDown = combinationDown;
    }

    private static boolean eitherKeyDown(long window, int left, int right) {
        return InputConstants.isKeyDown(window, left) || InputConstants.isKeyDown(window, right);
    }

    private static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!visible || minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        int textWidth = minecraft.font.width(TEXT);
        float scale = Math.min(1.0F, graphics.guiWidth() * 0.9F / textWidth);
        float centerX = graphics.guiWidth() / 2.0F;
        float centerY = graphics.guiHeight() * 0.26F;
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(minecraft.font, TEXT, -textWidth / 2, -minecraft.font.lineHeight / 2, 0xFFFFFFFF, true);
        graphics.pose().popPose();
    }

    @EventBusSubscriber(modid = CookchuterCore.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Layers {
        @SubscribeEvent
        public static void registerLayers(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.CROSSHAIR,
                    ResourceLocation.fromNamespaceAndPath(CookchuterCore.MOD_ID, "development_notice"),
                    (graphics, partialTick) -> render(graphics));
        }
    }
}
