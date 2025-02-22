package com.tterrag.registrate.providers;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Lists;
import com.tterrag.registrate.AbstractRegistrate;
import com.tterrag.registrate.util.DebugMarkers;
import com.tterrag.registrate.util.nullness.NonnullType;
import lombok.extern.log4j.Log4j2;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.resources.ResourceKey;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Log4j2
public class RegistrateDataProvider implements DataProvider {

    @SuppressWarnings("null")
    static final BiMap<String, ProviderType<?>> TYPES = HashBiMap.create();

    static final Map<ResourceKey<? extends Registry<?>>, ProviderType<?>> TAG_TYPES = new ConcurrentHashMap<>();

    public static @Nullable String getTypeName(ProviderType<?> type) {
        return TYPES.inverse().get(type);
    }

    private final String mod;
    private final Map<ProviderType<?>, RegistrateProvider> subProviders = new LinkedHashMap<>();
    private final CompletableFuture<HolderLookup.Provider> registriesLookup;

    public RegistrateDataProvider(AbstractRegistrate<?> parent, String modid, GatherDataEvent event) {
        this.mod = modid;
        this.registriesLookup = event.getLookupProvider();

        EnumSet<LogicalSide> sides = EnumSet.noneOf(LogicalSide.class);
        if (event.includeServer()) {
            sides.add(LogicalSide.SERVER);
        }
        if (event.includeClient()) {
            sides.add(LogicalSide.CLIENT);
        }

        log.debug(DebugMarkers.DATA, "Gathering providers for sides: {}", sides);
        Map<ProviderType<?>, RegistrateProvider> known = new HashMap<>();
        for (DataProviderInitializer.Sorted sorted :parent.getDataGenInitializer().getSortedProviders()) {
            ProviderType<?> type = sorted.type();
            var lookup = registriesLookup;
            if (sorted.parent() != null) lookup = ((RegistrateLookupFillerProvider) known.get(sorted.parent())).getFilledProvider();
            RegistrateProvider prov = ProviderType.create(type, parent, event, known, lookup);
            if (prov instanceof RegistrateTagsProvider<?> tagsProvider && TAG_TYPES.get(tagsProvider.registry()) != type) {
				throw new IllegalStateException("Tag providers must be registered through ProviderType::registerTag");
            }
            known.put(type, prov);
            if (sides.contains(prov.getSide())) {
                log.debug(DebugMarkers.DATA, "Adding provider for type: {}", sorted.id());
                subProviders.put(type, prov);
            }
        }
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        return registriesLookup.thenCompose(provider -> {
            var list = Lists.<CompletableFuture<?>>newArrayList();

            for (Map.Entry<@NonnullType ProviderType<?>, RegistrateProvider> e : subProviders.entrySet()) {
                log.debug(DebugMarkers.DATA, "Generating data for type: {}", getTypeName(e.getKey()));
                list.add(e.getValue().run(cache));
            };

            return CompletableFuture.allOf(list.toArray(CompletableFuture[]::new));
        });
    }

    @Override
    public String getName() {
        return "Registrate Provider for " + mod + " [" + subProviders.values().stream().map(DataProvider::getName).collect(Collectors.joining(", ")) + "]";
    }

    @SuppressWarnings("unchecked")
    public <P extends RegistrateProvider> Optional<P> getSubProvider(ProviderType<P> type) {
        return Optional.ofNullable((P) subProviders.get(type));
    }
}
