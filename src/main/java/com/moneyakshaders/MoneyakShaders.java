package com.moneyakshaders;

import java.util.concurrent.ForkJoinPool;
import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MoneyakShaders implements ModInitializer {
   public static final Logger LOGGER = LoggerFactory.getLogger("moneyakshaders");

   public void onInitialize() {
      MoneyakShadersConfig config = MoneyakShadersConfig.get();
      String actualPoolSize = Util.getMainWorkerExecutor().service() instanceof ForkJoinPool pool ? Integer.toString(pool.getParallelism()) : "unknown";
      LOGGER.info(
         "[Optimized Loading] Active - shared worker pool: {} threads{}, effective background threads: {}, dedicated chunk mesh threads: {} ({} cores available)",
         new Object[]{
            actualPoolSize,
            config.backgroundThreads > 0 ? "" : " (vanilla default or auto-balanced)",
            config.effectiveBackgroundThreads(),
            config.effectiveChunkBuilderThreads(),
            Runtime.getRuntime().availableProcessors()
         }
      );
   }
}
