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
import java.util.List;
import java.util.Map;

import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.ltr.feature.FeatureStore;
import org.apache.solr.ltr.ranking.Feature;
import org.apache.solr.ltr.util.CommonLTRParams;
import org.apache.solr.ltr.util.FeatureException;
import org.apache.solr.ltr.util.LTRUtils;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.rest.BaseSolrResource;
import org.apache.solr.rest.ManagedResource;
import org.apache.solr.rest.ManagedResourceStorage.StorageIO;
import org.apache.solr.util.SolrPluginUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Managed resource for a storing a feature.
 */
public class ManagedFeatureStore extends ManagedResource implements
    ManagedResource.ChildResourceSupport {

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
        update(u);
      }
    }
  }

  public void update(Map<String,Object> map) {
    final String name = (String) map.get(CommonLTRParams.MODEL_NAME);
    final String type = (String) map.get(CommonLTRParams.MODEL_CLASS);
    final String store = (String) map.get(CommonLTRParams.MODEL_FEATURE_STORE);

    final Map<String,Object> paramsMap = LTRUtils.createParams(map);

    try {

      addFeature(name, type, store, paramsMap);
    } catch (final FeatureException e) {
      throw new SolrException(ErrorCode.BAD_REQUEST, e);
    }
  }

  public synchronized void addFeature(String name, String type,
      String featureStore, Map<String,Object> params)
      throws FeatureException {
    log.info("register feature {} -> {} in "
        + (featureStore == null ? "default" : "")
        + " store"
        + (featureStore == null ? "" : (" [" + featureStore + "]")),
        name, type);

    final FeatureStore fstore = getFeatureStore(featureStore);

    if (fstore.containsFeature(name)) {
      throw new FeatureException(name
          + " already contained in the store, please use a different name");
    }

    if (params == null) {
      params = LTRUtils.EMPTY_MAP;
    }

    final Feature feature = createFeature(name, type, params, fstore.size());

    fstore.add(feature);
  }

  /**
   * generates an instance this feature.
   */
  private Feature createFeature(String name, String type, Map<String,Object> params,
      int id) throws FeatureException {
    try {
      final Feature f = solrResourceLoader.newInstance(
          type,
          Feature.class,
          new String[0], // no sub packages
          new Class[] { String.class },
          new Object[] { name });
      f.init(params);
      f.setId(id);
      SolrPluginUtils.invokeSetters(f, params.entrySet());
      return f;

    } catch (final Exception e) {
      throw new FeatureException(e.getMessage(), e);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public Object applyUpdatesToManagedData(Object updates) {
    if (updates instanceof List) {
      final List<Map<String,Object>> up = (List<Map<String,Object>>) updates;
      for (final Map<String,Object> u : up) {
        update(u);
      }
    }

    if (updates instanceof Map) {
      // a unique feature
      update((Map<String,Object>) updates);
    }

    // logger.info("fstore updated, features: ");
    // for (String s : store.getFeatureNames()) {
    // logger.info("  - {}", s);
    //
    // }
    final List<Object> features = new ArrayList<>();
    for (final FeatureStore fs : stores.values()) {
      features.addAll(fs.featuresAsManagedResources());
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
          store.featuresAsManagedResources());
    }
  }

}
