package org.ofbiz.entity.cache.redis;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.GenericEntity;
import org.ofbiz.entity.GenericPK;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.cache.redis.util.JedisUtilCache;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.model.ModelEntity;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

public abstract class JedisAbstractEntityConditionCache<K, V> extends JedisAbstractCache<EntityCondition, ConcurrentMap<K, V>> {

    public static final String module = JedisAbstractEntityConditionCache.class.getName();

    protected JedisAbstractEntityConditionCache(String delegatorName, String id) {
        super(delegatorName, id);
    }

    protected V get(String entityName, EntityCondition condition, K key) {
        String cacheName =  getCacheName(entityName);
        String conditionStr =  getFrozenConditionKey(condition)!=null?getFrozenConditionKey(condition).toString():"{null}";
        String keyStr = key!=null?key.toString():"{null}";
        return (V) JedisUtilCache.findConditionEntityByKey(cacheName, conditionStr,  keyStr, getDelegator());
    }

    protected V put(String entityName, EntityCondition condition, K key, V value) {
        ModelEntity entity = this.getDelegator().getModelEntity(entityName);
        if (entity.getNeverCache()) {
            Debug.logWarning("Tried to put a value of the " + entityName + " entity in the cache but this entity has never-cache set to true, not caching.", module);
            return null;
        }
        String conditionStr =  getFrozenConditionKey(condition)!=null?getFrozenConditionKey(condition).toString():"{null}";
        List values = (List)JedisUtilCache.findEntityListCache(getCacheName(entityName),conditionStr,getDelegator());
        values.add(value);

        JedisUtilCache.putEntityListCache(getCacheName(entityName),conditionStr,values);
        return (V) values;
    }

    /**
     * Removes all condition caches that include the specified entity.
     */
    public void remove(GenericEntity entity) {
        JedisUtilCache.removeAllEntityList(getCacheName(entity.getEntityName()));
        ModelEntity model = entity.getModelEntity();
        if (model != null) {
            Iterator<String> it = model.getViewConvertorsIterator();
            while (it.hasNext()) {
                String targetEntityName = it.next();
                JedisUtilCache.removeEntityAll(getCacheName(targetEntityName));
//                UtilCache.clearCache(getCacheName(targetEntityName));
            }
        }
    }

    public List<GenericValue>  remove(String entityName, EntityCondition condition) {
        String conditionStr =  getFrozenConditionKey(condition)!=null?getFrozenConditionKey(condition).toString():"{null}";
        return JedisUtilCache.removeEntityList(getCacheName(entityName), conditionStr,getDelegator());
    }

    protected V remove(String entityName, EntityCondition condition, K key) {
        String conditionStr =  getFrozenConditionKey(condition)!=null?getFrozenConditionKey(condition).toString():"{null}";
        return JedisUtilCache.removeConditionByKey(getCacheName(entityName), conditionStr, key, getDelegator());

    }

    public static final EntityCondition getConditionKey(EntityCondition condition) {
        return condition != null ? condition : null;
    }

    public static final EntityCondition getFrozenConditionKey(EntityCondition condition) {
        EntityCondition frozenCondition = condition != null ? condition.freeze() : null;
        // This is no longer needed, fixed issue with unequal conditions after freezing
        //if (condition != null) {
        //    if (!condition.equals(frozenCondition)) {
        //        Debug.logWarning("Frozen condition does not equal condition:\n -=-=-=-Original=" + condition + "\n -=-=-=-Frozen=" + frozenCondition, module);
        //        Debug.logWarning("Frozen condition not equal info: condition class=" + condition.getClass().getName() + "; frozenCondition class=" + frozenCondition.getClass().getName(), module);
        //    }
        //}
        return frozenCondition;
    }





    protected static final <K,V> boolean isNull(Map<K,V> value) {
        return value == null || value == GenericEntity.NULL_ENTITY || value == GenericValue.NULL_VALUE;
    }

    protected ModelEntity getModelCheckValid(GenericEntity oldEntity, GenericEntity newEntity) {
        ModelEntity model;
        if (!isNull(newEntity)) {
            model = newEntity.getModelEntity();
            String entityName = model.getEntityName();
            if (oldEntity != null && !entityName.equals(oldEntity.getEntityName())) {
                throw new IllegalArgumentException("internal error: storeHook called with 2 different entities(old=" + oldEntity.getEntityName() + ", new=" + entityName + ")");
            }
        } else {
            if (!isNull(oldEntity)) {
                model = oldEntity.getModelEntity();
            } else {
                throw new IllegalArgumentException("internal error: storeHook called with 2 null arguments");
            }
        }
        return model;
    }

    public void storeHook(GenericEntity newEntity) {
        storeHook(null, newEntity);
    }

    // if oldValue == null, then this is a new entity
    // if newValue == null, then
    public void storeHook(GenericEntity oldEntity, GenericEntity newEntity) {
        storeHook(false, oldEntity, newEntity);
    }

    // if oldValue == null, then this is a new entity
    // if newValue == null, then
    public void storeHook(GenericPK oldPK, GenericEntity newEntity) {
        storeHook(true, oldPK, newEntity);
    }

    protected List<? extends Map<String, Object>> convert(boolean isPK, String targetEntityName, GenericEntity entity) {
        if (isNull(entity)) return null;
        if (isPK) {
            return entity.getModelEntity().convertToViewValues(targetEntityName, entity);
        } else {
            return entity.getModelEntity().convertToViewValues(targetEntityName, entity);
        }
    }

    public void storeHook(boolean isPK, GenericEntity oldEntity, GenericEntity newEntity) {
        ModelEntity model = getModelCheckValid(oldEntity, newEntity);
        String entityName = model.getEntityName();
        // for info about cache clearing
        if (newEntity == null) {
            //Debug.logInfo("In storeHook calling sub-storeHook for entity name [" + entityName + "] for the oldEntity: " + oldEntity, module);
        }
        storeHook(entityName, isPK, UtilMisc.toList(oldEntity), UtilMisc.toList(newEntity));
        Iterator<String> it = model.getViewConvertorsIterator();
        while (it.hasNext()) {
            String targetEntityName = it.next();
            storeHook(targetEntityName, isPK, convert(isPK, targetEntityName, oldEntity), convert(false, targetEntityName, newEntity));
        }
    }

    protected <T1 extends Map<String, Object>, T2 extends Map<String, Object>> void storeHook(String entityName, boolean isPK, List<T1> oldValues, List<T2> newValues) {
        JedisUtilCache.removeAllEntityList(getCacheName(entityName));

        /*
        Map<String,String> entityListMap = JedisUtilCache.findEntityListAllCache(getCacheName(entityName),getDelegator());

        // for info about cache clearing
        if (UtilValidate.isEmpty(newValues) || newValues.get(0) == null) {
            //Debug.logInfo("In storeHook (cache clear) for entity name [" + entityName + "], got entity cache with name: " + (entityCache == null ? "[No cache found to remove from]" : entityCache.getName()), module);
        }
        if (UtilValidate.isEmpty(entityListMap) ) {
            return;
        }*/

//        Iterator keyIter = entityListMap.keySet().iterator();
        /*while (keyIter.hasNext()) {

            String obj = (String)keyIter.next();

            //Debug.logInfo("In storeHook entityName [" + entityName + "] checking against condition: " + condition, module);
            boolean shouldRemove = false;
            if (obj == null) {
                shouldRemove = true;
            } else if (oldValues == null) {
                Iterator<T2> newValueIter = newValues.iterator();
                while (newValueIter.hasNext() && !shouldRemove) {
                    T2 newValue = newValueIter.next();
                    shouldRemove |= condition.mapMatches(getDelegator(), newValue);
                }
            } else {
                boolean oldMatched = false;
                Iterator<T1> oldValueIter = oldValues.iterator();
                while (oldValueIter.hasNext() && !shouldRemove) {
                    T1 oldValue = oldValueIter.next();
                    if (condition.mapMatches(getDelegator(), oldValue)) {
                        oldMatched = true;
                        //Debug.logInfo("In storeHook, oldMatched for entityName [" + entityName + "]; shouldRemove is false", module);
                        if (newValues != null) {
                            Iterator<T2> newValueIter = newValues.iterator();
                            while (newValueIter.hasNext() && !shouldRemove) {
                                T2 newValue = newValueIter.next();
                                shouldRemove |= isNull(newValue) || condition.mapMatches(getDelegator(), newValue);
                                //Debug.logInfo("In storeHook, for entityName [" + entityName + "] shouldRemove is now " + shouldRemove, module);
                            }
                        } else {
                            shouldRemove = true;
                        }
                    }
                }
                // QUESTION: what is this? why would we do this?
                if (!oldMatched && isPK) {
                    //Debug.logInfo("In storeHook, for entityName [" + entityName + "] oldMatched is false and isPK is true, so setting shouldRemove to true (will remove from cache)", module);
                    shouldRemove = true;
                }
            }
            if (shouldRemove) {
                if (Debug.verboseOn()) Debug.logVerbose("In storeHook, matched condition, removing from cache for entityName [" + entityName + "] in cache with name [" + entityCache.getName() + "] entry with condition: " + condition, module);
                // doesn't work anymore since this is a copy of the cache keySet, can call remove directly though with a concurrent mod exception: cacheKeyIter.remove();
                entityCache.remove(condition);
            }
        }*/
    }


}
