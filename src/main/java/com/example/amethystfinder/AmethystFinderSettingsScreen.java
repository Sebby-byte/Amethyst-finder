package com.example.amethystfinder;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Settings: on/off, minimum growth stage to detect, and range. */
public class AmethystFinderSettingsScreen extends Screen {
    private Button enableButton;
    private Button stageButton;
    private Button seeThroughButton;

    public AmethystFinderSettingsScreen() {
        super(Component.literal("Amethyst Finder Settings"));
    }

    @Override
    protected void init() {
        int w = 220;
        int x = this.width / 2 - w / 2;
        int y = this.height / 2 - 65;

        enableButton = this.addRenderableWidget(Button.builder(enableLabel(), b -> {
            AmethystFinderClient.toggle(this.minecraft);
            enableButton.setMessage(enableLabel());
        }).bounds(x, y, w, 20).build());

        stageButton = this.addRenderableWidget(Button.builder(stageLabel(), b -> {
            AmethystFinderClient.setMinStage((AmethystFinderClient.getMinStage() + 1) % 4);
            stageButton.setMessage(stageLabel());
        }).bounds(x, y + 26, w, 20).build());

        this.addRenderableWidget(new RangeSlider(x, y + 52, w, 20));

        seeThroughButton = this.addRenderableWidget(Button.builder(seeThroughLabel(), b -> {
            AmethystFinderClient.setSeeThrough(!AmethystFinderClient.isSeeThrough());
            seeThroughButton.setMessage(seeThroughLabel());
        }).bounds(x, y + 78, w, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(x, y + 112, w, 20).build());
    }

    private static Component enableLabel() {
        return Component.literal("Amethyst Finder: " + (AmethystFinderClient.isEnabled() ? "ON" : "OFF"));
    }

    private static Component seeThroughLabel() {
        return Component.literal("See through blocks: " + (AmethystFinderClient.isSeeThrough() ? "ON" : "OFF"));
    }

    private static Component stageLabel() {
        int s = AmethystFinderClient.getMinStage();
        String suffix = s == 3 ? "" : " and larger";
        return Component.literal("Detect: " + AmethystFinderClient.STAGE_NAMES[s] + suffix);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        Component title = Component.literal("Amethyst Finder Settings");
        graphics.text(this.font, title, this.width / 2 - this.font.width(title) / 2,
                this.height / 2 - 89, 0xFFFFFFFF, true);
    }

    @Override
    public void onClose() {
        AmethystFinderClient.saveConfig();
        super.onClose();
    }

    private static double toSlider(int range) {
        return (range - AmethystFinderClient.MIN_RANGE)
                / (double) (AmethystFinderClient.MAX_RANGE - AmethystFinderClient.MIN_RANGE);
    }

    private static int fromSlider(double value) {
        return AmethystFinderClient.MIN_RANGE + (int) Math.round(value
                * (AmethystFinderClient.MAX_RANGE - AmethystFinderClient.MIN_RANGE));
    }

    private static class RangeSlider extends AbstractSliderButton {
        RangeSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), toSlider(AmethystFinderClient.getRange()));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal("Range: " + fromSlider(this.value) + " blocks"));
        }

        @Override
        protected void applyValue() {
            AmethystFinderClient.setRange(fromSlider(this.value));
        }
    }
}
