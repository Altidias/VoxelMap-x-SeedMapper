package com.mamiyaotaru.voxelmap.chunkanalysis;

import com.mamiyaotaru.voxelmap.MapSettingsManager;
import com.mamiyaotaru.voxelmap.gui.overridden.EnumOptionsMinimap;
import com.mamiyaotaru.voxelmap.interfaces.ISubSettingsManager;
import net.minecraft.util.Mth;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;

public final class ChunkAnalysisSettingsManager implements ISubSettingsManager {
    public boolean ghostBlocks = true;
    public boolean espFill = false;
    public boolean highConfidenceOnly = true;
    public int scanRadius = ChunkAnalysisService.DEFAULT_RADIUS;
    public int renderDistance = 192;
    public int renderLimit = 12000;
    public int autoClearMinutes = 3;
    public double ghostOpacity = 0.38D;
    public double fillOpacity = 0.13D;

    @Override
    public void loadAll(File settingsFile) {
        try (BufferedReader in = new BufferedReader(new FileReader(settingsFile))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] value = line.split(":", 2);
                if (value.length != 2) continue;
                switch (value[0]) {
                    case "ChunkAnalysis Ghost Blocks" -> ghostBlocks = Boolean.parseBoolean(value[1]);
                    case "ChunkAnalysis ESP Fill" -> espFill = Boolean.parseBoolean(value[1]);
                    case "ChunkAnalysis High Confidence Only" -> highConfidenceOnly = Boolean.parseBoolean(value[1]);
                    case "ChunkAnalysis Scan Radius" -> scanRadius = Mth.clamp(Integer.parseInt(value[1]), 0, ChunkAnalysisService.MAX_RADIUS);
                    case "ChunkAnalysis Render Distance" -> renderDistance = Mth.clamp(Integer.parseInt(value[1]), 32, 512);
                    case "ChunkAnalysis Render Limit" -> renderLimit = Mth.clamp(Integer.parseInt(value[1]), 1000, 100000);
                    case "ChunkAnalysis Auto Clear Minutes" -> autoClearMinutes = Mth.clamp(Integer.parseInt(value[1]), 1, 5);
                    case "ChunkAnalysis Ghost Opacity" -> ghostOpacity = Mth.clamp(Double.parseDouble(value[1]), 0.05D, 0.8D);
                    case "ChunkAnalysis Fill Opacity" -> fillOpacity = Mth.clamp(Double.parseDouble(value[1]), 0.02D, 0.5D);
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
    }

    @Override
    public void saveAll(PrintWriter out) {
        out.println("ChunkAnalysis Ghost Blocks:" + ghostBlocks);
        out.println("ChunkAnalysis ESP Fill:" + espFill);
        out.println("ChunkAnalysis High Confidence Only:" + highConfidenceOnly);
        out.println("ChunkAnalysis Scan Radius:" + scanRadius);
        out.println("ChunkAnalysis Render Distance:" + renderDistance);
        out.println("ChunkAnalysis Render Limit:" + renderLimit);
        out.println("ChunkAnalysis Auto Clear Minutes:" + autoClearMinutes);
        out.println("ChunkAnalysis Ghost Opacity:" + ghostOpacity);
        out.println("ChunkAnalysis Fill Opacity:" + fillOpacity);
    }

    @Override public String getKeyText(EnumOptionsMinimap option) { return MapSettingsManager.ERROR_STRING; }
    @Override public boolean getBooleanValue(EnumOptionsMinimap option) { return false; }
    @Override public void toggleBooleanValue(EnumOptionsMinimap option) { }
    @Override public String getListValue(EnumOptionsMinimap option) { return MapSettingsManager.ERROR_STRING; }
    @Override public void cycleListValue(EnumOptionsMinimap option) { }
    @Override public float getFloatValue(EnumOptionsMinimap option) { return 0.0F; }
    @Override public void setFloatValue(EnumOptionsMinimap option, float value) { }
}
