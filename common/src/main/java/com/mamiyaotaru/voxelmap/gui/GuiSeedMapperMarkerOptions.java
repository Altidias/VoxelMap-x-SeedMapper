package com.mamiyaotaru.voxelmap.gui;

import com.mamiyaotaru.voxelmap.MapSettingsManager;
import com.mamiyaotaru.voxelmap.VoxelConstants;
import com.mamiyaotaru.voxelmap.gui.overridden.GuiScreenMinimap;
import com.mamiyaotaru.voxelmap.seedmapper.SeedMapperMarkerOption;
import com.mamiyaotaru.voxelmap.seedmapper.SeedMapperSettingsManager;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class GuiSeedMapperMarkerOptions extends GuiScreenMinimap {
    private final SeedMapperMarkerOption.Category category;
    private final SeedMapperSettingsManager settings;

    public GuiSeedMapperMarkerOptions(Screen parent, SeedMapperMarkerOption.Category category) {
        this.lastScreen = parent;
        this.category = category;
        this.settings = VoxelConstants.getVoxelMapInstance().getSeedMapperOptions();
    }

    @Override
    public void init() {
        int left = width / 2 - 155;
        int right = width / 2 + 5;
        int row = 2;
        for (SeedMapperMarkerOption option : SeedMapperMarkerOption.forCategory(category)) {
            int x = (row & 1) == 0 ? left : right;
            int y = 40 + (row / 2) * 24;
            addRenderableWidget(new Button.Builder(Component.empty(), button -> {
                settings.setMarkerOptionEnabled(option, !settings.isMarkerOptionEnabled(option));
                button.setMessage(label(option));
                MapSettingsManager.instance.saveAll();
            }).bounds(x, y, 150, 20).build());
            children().get(children().size() - 1);
            ((Button) children().get(children().size() - 1)).setMessage(label(option));
            row++;
        }
        addRenderableWidget(new Button.Builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    private Component label(SeedMapperMarkerOption option) {
        return Component.literal(option.label() + ": " + (settings.isMarkerOptionEnabled(option) ? "ON" : "OFF"));
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(getFont(), Component.literal(category.name().substring(0, 1) + category.name().substring(1).toLowerCase() + " Markers"), width / 2, 18, 0xFFFFFFFF);
    }
}
