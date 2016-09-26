/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.ltr.rest;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.ltr.feature.FeatureException;
import org.apache.solr.ltr.feature.FeatureStore;
import org.apache.solr.ltr.ranking.Feature;
import org.apache.solr.ltr.util.CommonLTRParams;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.rest.BaseSolrResource;
import org.apache.solr.rest.ManagedResource;
import org.apache.solr.rest.ManagedResourceStorage.StorageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Managed resource for a storing a feature.
 */
public class ManagedFeatureStore extends ManagedResource implements
    ManagedResource.ChildResourceSupport {

  /** name of the attribute containing the feature class **/
  public static final String CLASS_KEY = "class";
  /** name of the attribute containing the feature name **/
  public static final String NAME_KEY = "name";
  /** name of the attribute containing the feature params **/
  public static final String PARAMS_KEY = "params";
  /** name of the attribute containing the feature store used **/
  private static final String FEATURE_STORE_NAME_KEY = "store";

  private final Map<String,FeatureStore> stores = new HashMap<>();

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  
  public ManagedFeatureStore(String resourceId, SolrResourceLoader loader,
      StorageIO storageIO) throws SolrException {
    super(resourceId, loader, storageIO);

  }

  public synchronized FeatureStore getFeatureStore(String name) {
    if (name == null) {
      name = FeatureStore.DEFAULT_FEATURE_STORE_NAME;
    }
    if (!stores.containsKey(name)) {
      stores.put(name, new FeatureStore(name));
    }
    return stores.get(name);
  }

  @Override
  protected void onManagedDataLoadedFromStorage(NamedList<?> managedInitArgs,
      Object managedData) throws SolrException {

    stores.clear();
    log.info("------ managed feature ~ loading ------");
    if (managedData instanceof List) {
      @SuppressWarnings("unchecked")
      final List<Map<String,Object>> up = (List<Map<String,Object>>) managedData;
      for (final Map<String,Object> u : up) {
        final String featureStore = (String) u.get(FEATURE_STORE_NAME_KEY);
        addFeature(u, featureStore);
      }
    }
  }

  public synchronized void addFeature(Map<String,Object> map, String featureStore)
      throws FeatureException {
    try {
      log.info("register feature based on {}", map);
      final FeatureStore fstore = getFeatureStore(featureStore);
      final Feature feature = fromFeatureMap(solrResourceLoader, map);
      fstore.add(feature);
    } catch (final FeatureException e) {
      throw new SolrException(ErrorCode.BAD_REQUEST, e);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public Object applyUpdatesToManagedData(Object updates) {
    if (updates instanceof List) {
      final List<Map<String,Object>> up = (List<Map<String,Object>>) updates;
      for (final Map<String,Object> u : up) {
        final String featureStore = (String) u.get(FEATURE_STORE_NAME_KEY);
        addFeature(u, featureStore);
      }
    }

    if (updates instanceof Map) {
      // a unique feature
      Map<String,Object> updatesMap = (Map<String,Object>) updates;
      final String featureStore = (String) updatesMap.get(FEATURE_STORE_NAME_KEY);
      addFeature(updatesMap, featureStore);
    }

    // logger.info("fstore updated, features: ");
    // for (String s : store.getFeatureNames()) {
    // logger.info("  - {}", s);
    //
    // }
    final List<Object> features = new ArrayList<>();
    for (final FeatureStore fs : stores.values()) {
      features.addAll(featuresAsManagedResources(fs));
    }
    return features;
  }

  @Override
  public synchronized void doDeleteChild(BaseSolrResource endpoint, String childId) {
    if (childId.equals("*")) {
      stores.clear();
    }
    if (stores.containsKey(childId)) {
      stores.remove(childId);
    }
    storeManagedData(applyUpdatesToManagedData(null));
  }

  /**
   * Called to retrieve a named part (the given childId) of the resource at the
   * given endpoint. Note: since we have a unique child feature store we ignore
   * the childId.
   */
  @Override
  public void doGet(BaseSolrResource endpoint, String childId) {
    final SolrQueryResponse response = endpoint.getSolrResponse();

    // If no feature store specified, show all the feature stores available
    if (childId == null) {
      response.add(CommonLTRParams.FEATURE_STORE_JSON_FIELD, stores.keySet());
    } else {
      final FeatureStore store = getFeatureStore(childId);
      if (store == null) {
        throw new SolrException(ErrorCode.BAD_REQUEST,
            "missing feature store [" + childId + "]");
      }
      response.add(CommonLTRParams.FEATURES_JSON_FIELD,
          featuresAsManagedResources(store));
    }
  }

  private static List<Object> featuresAsManagedResources(FeatureStore store) {
    final List<Object> features = new ArrayList<Object>(store.size());
    for (final Feature f : store.getFeatures()) {
      final LinkedHashMap<String,Object> m = toFeatureMap(f);
      m.put(FEATURE_STORE_NAME_KEY, store.getName());
      features.add(m);
    }
    return features;
  }

  private static LinkedHashMap<String,Object> toFeatureMap(Feature feat) {
    final LinkedHashMap<String,Object> o = new LinkedHashMap<>(4, 1.0f); // 1 extra for caller to add store
    o.put(NAME_KEY, feat.getName());
    o.put(CLASS_KEY, feat.getClass().getCanonicalName());
    o.put(PARAMS_KEY, feat.paramsToMap());
    return o;
  }
  
  private static Feature fromFeatureMap(SolrResourceLoader solrResourceLoader,
      Map<String,Object> featureMap) {
    final String className = (String) featureMap.get(CLASS_KEY);

    final String name = (String) featureMap.get(NAME_KEY);

    @SuppressWarnings("unchecked")
    final Map<String,Object> params = (Map<String,Object>) featureMap.get(PARAMS_KEY);

    return Feature.getInstance(solrResourceLoader, className, name, params);
  }
}
