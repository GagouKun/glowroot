/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.server.storage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.Clock;

public class RollupService implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(RollupService.class);

    private final AggregateDao aggregateDao;
    private final GaugeValueDao gaugeValueDao;
    private final Clock clock;

    private final ExecutorService executor;

    private volatile boolean stopped;

    public RollupService(AggregateDao aggregateDao, GaugeValueDao gaugeValueDao, Clock clock) {
        this.aggregateDao = aggregateDao;
        this.gaugeValueDao = gaugeValueDao;
        this.clock = clock;
        executor = Executors.newSingleThreadExecutor();
        executor.execute(castInitialized(this));
    }

    public void close() {
        stopped = true;
        executor.shutdownNow();
    }

    @Override
    public void run() {
        while (true) {
            try {
                Thread.sleep(millisUntilNextRollup(clock.currentTimeMillis()));
                aggregateDao.rollup();
                gaugeValueDao.rollup();
            } catch (InterruptedException e) {
                if (stopped) {
                    return;
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    @VisibleForTesting
    static long millisUntilNextRollup(long currentTimeMillis) {
        return 60000 - (currentTimeMillis - 10000) % 60000;
    }

    @SuppressWarnings("return.type.incompatible")
    private static <T> /*@Initialized*/ T castInitialized(/*@UnderInitialization*/ T obj) {
        return obj;
    }
}