/*
 * Copyright 2016 Santanu Sinha <santanu.sinha@gmail.com>
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
 *
 */

package io.dropwizard.sharding.dao;

import com.google.common.base.Preconditions;
import io.dropwizard.sharding.sharding.ShardManager;
import io.dropwizard.sharding.utils.ShardCalculator;
import io.dropwizard.sharding.utils.Transactions;
import io.dropwizard.hibernate.AbstractDAO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;

import javax.persistence.Id;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A dao used to work with entities related to a parent shard. The parent may or maynot be physically present.
 * A murmur 128 hash of the string parent key is used to route the save and retrieve calls from the proper shard.
 */
@Slf4j
public class RelationalDao<T> {

    private final class LookupDaoPriv extends AbstractDAO<T> {

        private final SessionFactory sessionFactory;

        /**
         * Creates a new DAO with a given session provider.
         *
         * @param sessionFactory a session provider
         */
        public LookupDaoPriv(SessionFactory sessionFactory) {
            super(sessionFactory);
            this.sessionFactory = sessionFactory;
        }

        T get(Object lookupKey) {
            return uniqueResult(currentSession()
                                .createCriteria(entityClass)
                                .add(
                                        Restrictions.eq(keyField.getName(), lookupKey)));
        }

        T save(T entity) {
            return persist(entity);
        }

        List<T> select(DetachedCriteria criteria) {
            return list(criteria.getExecutableCriteria(currentSession()));
        }

        long count(DetachedCriteria criteria) {
            return  (long)criteria.getExecutableCriteria(currentSession())
                            .setProjection(Projections.rowCount())
                            .uniqueResult();
        }

    }

    private List<LookupDaoPriv> daos;
    private final Class<T> entityClass;
    private final ShardManager shardManager;
    private final Field keyField;

    /**
     * Create a relational DAO.
     * @param sessionFactories List of session factories. One for each shard.
     * @param entityClass The class for which the dao will be used.
     * @param shardManager The {@link ShardManager} used to manage the bucket to shard mapping.
     */
    public RelationalDao(List<SessionFactory> sessionFactories, Class<T> entityClass, ShardManager shardManager) {
        this.shardManager = shardManager;
        this.daos = sessionFactories.stream().map(LookupDaoPriv::new).collect(Collectors.toList());
        this.entityClass = entityClass;

        Field fields[] = FieldUtils.getFieldsWithAnnotation(entityClass, Id.class);
        Preconditions.checkArgument(fields.length != 0, "A field needs to be designated as @Id");
        Preconditions.checkArgument(fields.length == 1, "Only one field can be designated as @Id");
        keyField = fields[0];
        if(!keyField.isAccessible()) {
            try {
                keyField.setAccessible(true);
            } catch (SecurityException e) {
                log.error("Error making key field accessible please use a public method and mark that as @Id", e);
                throw new IllegalArgumentException("Invalid class, DAO cannot be created.", e);
            }
        }
    }


    public Optional<T> get(String parentKey, Object key) throws Exception {
        return Optional.ofNullable(get(parentKey, key, t-> t));
    }

    public<U> U get(String parentKey, Object key, Function<T, U> function) throws Exception {
        int shardId = ShardCalculator.shardId(shardManager, parentKey);
        LookupDaoPriv dao = daos.get(shardId);
        return Transactions.<T, Object, U>execute(dao.sessionFactory, true, dao::get, key, function);
    }

    public Optional<T> save(String parentKey, T entity) throws Exception {
        return Optional.ofNullable(save(parentKey, entity, t -> t));
    }

    public <U> U save(String parentKey, T entity, Function<T, U> handler) throws Exception {
        int shardId = ShardCalculator.shardId(shardManager, parentKey);
        LookupDaoPriv dao = daos.get(shardId);
        return Transactions.execute(dao.sessionFactory, false, dao::save, entity, handler);
    }

    public List<T> select(String parentKey, DetachedCriteria criteria) throws Exception {
        return select(parentKey, criteria, t-> t);
    }

    public<U> U select(String parentKey, DetachedCriteria criteria, Function<List<T>, U> handler) throws Exception {
        int shardId = ShardCalculator.shardId(shardManager, parentKey);
        LookupDaoPriv dao = daos.get(shardId);
        return Transactions.execute(dao.sessionFactory, true, dao::select, criteria, handler);
    }

    public long count(String parentKey, DetachedCriteria criteria) throws Exception {
        int shardId = ShardCalculator.shardId(shardManager, parentKey);
        LookupDaoPriv dao = daos.get(shardId);
        return Transactions.<Long, DetachedCriteria>execute(dao.sessionFactory, true, dao::count, criteria);
    }

    public boolean exists(String parentKey, Object key) throws Exception {
        int shardId = ShardCalculator.shardId(shardManager, parentKey);
        LookupDaoPriv dao = daos.get(shardId);
        Optional<T> result = Transactions.<T, Object>executeAndResolve(dao.sessionFactory, true, dao::get, key);
        return result.isPresent();
    }

    public List<T> scatterGather(DetachedCriteria criteria) {
        return daos.stream().map(dao -> {
            try {
                return Transactions.execute(dao.sessionFactory, true, dao::select, criteria);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).flatMap(Collection::stream).collect(Collectors.toList());
    }

}