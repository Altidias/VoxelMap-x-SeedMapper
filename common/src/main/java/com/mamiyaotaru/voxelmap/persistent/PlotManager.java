package com.mamiyaotaru.voxelmap.persistent;

import com.mamiyaotaru.voxelmap.VoxelConstants;
import com.mamiyaotaru.voxelmap.util.TextUtils;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Stores world-map-only lines for one world map view. */
final class PlotManager {
    private final List<Plot> plots = new ArrayList<>();
    private File file;

    void load(String world, String subworld, String dimension) {
        plots.clear();
        String worldPart = TextUtils.scrubNameFile(world == null || world.isBlank() ? "unknown" : world);
        String subworldPart = TextUtils.scrubNameFile(subworld == null || subworld.isBlank() ? "default" : subworld);
        String dimensionPart = TextUtils.scrubNameFile(dimension == null || dimension.isBlank() ? "unknown" : dimension);
        this.file = new File(VoxelConstants.getMinecraft().gameDirectory,
                "voxelmap/plots/" + worldPart + "/" + subworldPart + "/plots.txt");
        File loadFile = file.isFile() ? file : new File(VoxelConstants.getMinecraft().gameDirectory,
                "voxelmap/plots/" + worldPart + "/" + subworldPart + "/" + dimensionPart + ".txt");
        if (!loadFile.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(loadFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length != 4 && parts.length != 8) continue;
                try {
                    plots.add(new Plot(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                            Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                            parts.length == 8 ? parts[4] : dimension,
                            parts.length == 8 && Boolean.parseBoolean(parts[5]),
                            parts.length == 8 ? Integer.parseInt(parts[6]) : 0,
                            parts.length == 8 ? Integer.parseInt(parts[7]) : 0));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException exception) {
            VoxelConstants.getLogger().warn("Failed loading world map plots", exception);
        }
    }

    List<Plot> getPlots() { return plots; }

    void add(Plot plot) { plots.add(plot); save(); }

    void remove(Plot plot) {
        if (plots.remove(plot)) save();
    }

    void replace(Plot oldPlot, Plot newPlot) {
        int index = plots.indexOf(oldPlot);
        if (index >= 0) {
            plots.set(index, newPlot);
            save();
        }
    }

    private void save() {
        if (file == null) return;
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (Plot plot : plots) {
                writer.write(plot.x1() + " " + plot.z1() + " " + plot.x2() + " " + plot.z2() + " "
                        + plot.dimension() + " " + plot.showOppositeDimension() + " "
                        + plot.thickness() + " " + plot.color());
                writer.newLine();
            }
        } catch (IOException exception) {
            VoxelConstants.getLogger().warn("Failed saving world map plots", exception);
        }
    }

    record Plot(double x1, double z1, double x2, double z2, String dimension,
                boolean showOppositeDimension, int thickness, int color) {
        Plot(double x1, double z1, double x2, double z2) {
            this(x1, z1, x2, z2, "minecraft:overworld", false, 0, 0);
        }
    }
}
