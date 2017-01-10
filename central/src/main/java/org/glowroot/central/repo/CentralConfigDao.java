/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.central.repo;

import java.io.IOException;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;

public class CentralConfigDao {

    private static final Logger logger = LoggerFactory.getLogger(CentralConfigDao.class);

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final Session session;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    private final LoadingCache<CacheKey, Optional<Object>> cache =
            CacheBuilder.newBuilder().build(new CentralConfigCacheLoader());

    public CentralConfigDao(Session session) {
        this.session = session;

        session.execute("create table if not exists central_config (key varchar, value varchar,"
                + " primary key (key)) " + WITH_LCS);

        insertPS = session.prepare("insert into central_config (key, value) values (?, ?)");
        readPS = session.prepare("select value from central_config where key = ?");
    }

    void write(String key, Object config) throws JsonProcessingException {
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, key);
        boundStatement.setString(i++, mapper.writeValueAsString(config));
        session.execute(boundStatement);
        cache.invalidate(ImmutableCacheKey.of(key, null));
    }

    @Nullable
    <T> T read(String key, Class<T> clazz) throws Exception {
        return clazz.cast(cache.get(ImmutableCacheKey.of(key, clazz)).orNull());
    }

    @Value.Immutable
    @Styles.AllParameters
    interface CacheKey {

        String key();

        // type is marked auxiliary so that it won't be included in hashCode or equals
        // which is needed so that cache.invalidate() can be performed using the key alone
        @Value.Auxiliary
        @Nullable
        @SuppressWarnings("immutables")
        Class<?> type();
    }

    private class CentralConfigCacheLoader extends CacheLoader<CacheKey, Optional<Object>> {
        @Override
        public Optional<Object> load(CacheKey key) throws Exception {
            BoundStatement boundStatement = readPS.bind();
            boundStatement.bind(key.key());
            ResultSet results = session.execute(boundStatement);
            Row row = results.one();
            if (row == null) {
                return Optional.absent();
            }
            String value = row.getString(0);
            if (value == null) {
                return Optional.absent();
            }
            try {
                Object config = mapper.readValue(value, checkNotNull(key.type()));
                // config is non-null b/c text "null" is never stored
                return Optional.of(checkNotNull(config));
            } catch (IOException e) {
                logger.error("error parsing config node '{}': ", key, e);
                return Optional.absent();
            }
        }
    }
}
