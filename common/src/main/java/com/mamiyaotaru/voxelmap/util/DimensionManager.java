package com.mamiyaotaru.voxelmap.util;

import com.mamiyaotaru.voxelmap.VoxelConstants;
import java.util.ArrayList;
import java.util.Optional;
import net.minecraft.core.Holder.Reference;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

public class DimensionManager {
    public enum Environment {
        OVERWORLD,
        NETHER,
        END,
        CUSTOM_OR_UNKNOWN
    }
    public final ArrayList<DimensionContainer> dimensions;
    private final ArrayList<ResourceKey<Level>> vanillaWorlds = new ArrayList<>();

    public DimensionManager() {
        this.dimensions = new ArrayList<>();
        this.vanillaWorlds.add(Level.OVERWORLD);
        this.vanillaWorlds.add(Level.NETHER);
        this.vanillaWorlds.add(Level.END);
    }

    /** Classifies the environment without using the world's identity as its type. */
    public static Environment getEnvironment(Level world) {
        if (world == null) {
            return Environment.CUSTOM_OR_UNKNOWN;
        }

        Optional<ResourceKey<DimensionType>> registeredType = world.dimensionTypeRegistration().unwrapKey();
        if (registeredType.isPresent()) {
            Identifier typeId = registeredType.get().identifier();
            if (typeId.equals(Level.OVERWORLD.identifier()) || typeId.equals(Identifier.fromNamespaceAndPath("minecraft", "overworld_caves"))) {
                return Environment.OVERWORLD;
            }
            if (typeId.equals(Identifier.fromNamespaceAndPath("minecraft", "the_nether"))) {
                return Environment.NETHER;
            }
            if (typeId.equals(Identifier.fromNamespaceAndPath("minecraft", "the_end"))) {
                return Environment.END;
            }
        }

        // The current mappings do not expose an effects identifier. This is the
        // only useful property fallback for custom Nether-like dimension types.
        if (world.dimensionType().cardinalLightType() == net.minecraft.world.level.CardinalLighting.Type.NETHER) {
            return Environment.NETHER;
        }

        // Preserve vanilla compatibility if a registry key is unavailable.
        if (world.dimension() == Level.OVERWORLD) return Environment.OVERWORLD;
        if (world.dimension() == Level.NETHER) return Environment.NETHER;
        if (world.dimension() == Level.END) return Environment.END;
        return Environment.CUSTOM_OR_UNKNOWN;
    }

    public ArrayList<DimensionContainer> getDimensions() {
        return this.dimensions;
    }

    public void populateDimensions(Level world) {
        this.dimensions.clear();
        Registry<DimensionType> dimensionTypeRegistry = VoxelConstants.getMinecraft().getConnection().registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);

        for (ResourceKey<Level> vanillaWorldKey : this.vanillaWorlds) {
            ResourceKey<DimensionType> typeKey = ResourceKey.create(Registries.DIMENSION_TYPE, vanillaWorldKey.identifier());
            Optional<Reference<DimensionType>> optionalDimension = dimensionTypeRegistry.get(typeKey);
            if (optionalDimension.isPresent()) {
                DimensionType dimensionType = optionalDimension.get().value();
                DimensionContainer dimensionContainer = new DimensionContainer(dimensionType, vanillaWorldKey.identifier().getPath(), vanillaWorldKey.identifier());
                this.dimensions.add(dimensionContainer);
            }
        }

        this.sort();
    }

    public void enteredWorld(Level world) {
        Identifier Identifier = world.dimension().identifier();
        DimensionContainer dim = this.getDimensionContainerByIdentifier(Identifier);
        if (dim == null) {
            dim = new DimensionContainer(world.dimensionType(), Identifier.getPath(), Identifier);
            this.dimensions.add(dim);
            this.sort();
        }

        if (dim.type == null) {
            try {
                dim.type = world.dimensionType();
            } catch (RuntimeException ignored) {}
        }

    }

    private void sort() {
        this.dimensions.sort((dim1, dim2) -> {
            if (dim1.Identifier.equals(Level.OVERWORLD.identifier())) {
                return -1;
            } else if (dim1.Identifier.equals(Level.NETHER.identifier()) && !dim2.Identifier.equals(Level.OVERWORLD.identifier())) {
                return -1;
            } else {
                return dim1.Identifier.equals(Level.END.identifier()) && !dim2.Identifier.equals(Level.OVERWORLD.identifier()) && !dim2.Identifier.equals(Level.NETHER.identifier()) ? -1 : String.CASE_INSENSITIVE_ORDER.compare(dim1.name, dim2.name);
            }
        });
    }

    public DimensionContainer getDimensionContainerByWorld(Level world) {
        Identifier Identifier = world.dimension().identifier();
        DimensionContainer dim = this.getDimensionContainerByIdentifier(Identifier);
        if (dim == null) {
            dim = new DimensionContainer(world.dimensionType(), Identifier.getPath(), Identifier);
            this.dimensions.add(dim);
            this.sort();
        }

        return dim;
    }

    public DimensionContainer getDimensionContainerByIdentifier(String ident) {
        DimensionContainer dim;
        Identifier identifier = Identifier.parse(ident);
        dim = this.getDimensionContainerByIdentifier(identifier);
        if (dim == null) {
            dim = new DimensionContainer(null, identifier.getPath(), identifier);
            this.dimensions.add(dim);
            this.sort();
        }

        return dim;
    }

    public DimensionContainer getDimensionContainerByIdentifier(Identifier Identifier) {
        return this.dimensions.stream().filter(dim -> Identifier.equals(dim.Identifier)).findFirst().orElse(null);
    }
}
