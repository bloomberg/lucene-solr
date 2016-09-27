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

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.ltr.feature.FeatureStore;
import org.apache.solr.ltr.feature.LTRScoringModel;
import org.apache.solr.ltr.feature.ModelStore;
import org.apache.solr.ltr.feature.norm.Normalizer;
import org.apache.solr.ltr.feature.norm.impl.IdentityNormalizer;
import org.apache.solr.ltr.model.ModelException;
import org.apache.solr.ltr.ranking.Feature;
import org.apache.solr.ltr.util.CommonLTRParams;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.rest.BaseSolrResource;
import org.apache.solr.rest.ManagedResource;
import org.apache.solr.rest.ManagedResourceStorage.StorageIO;
import org.noggit.ObjectBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Menaged resource for storing a model
 */
public class ManagedModelStore extends ManagedResource implements
    ManagedResource.ChildResourceSupport {

  /** name of the attribute containing the normalizer type **/
  public static final String CLASS_KEY = "class";
  /** name of the attribute containing the normalizer params **/
  public static final String PARAMS_KEY = "params";
 
  ModelStore store;
  private ManagedFeatureStore featureStores;

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  
  public ManagedModelStore(String resourceId, SolrResourceLoader loader,
      StorageIO storageIO) throws SolrException {
    super(resourceId, loader, storageIO);

    store = new ModelStore();

  }

  public void init(ManagedFeatureStore featureStores) {
    log.info("INIT model store");
    this.featureStores = featureStores;
  }

  private Object managedData;

  @SuppressWarnings("unchecked")
  @Override
  protected void onManagedDataLoadedFromStorage(NamedList<?> managedInitArgs,
      Object managedData) throws SolrException {
    store.clear();
    // the managed models on the disk or on zookeeper will be loaded in a lazy
    // way, since we need to set the managed features first (unfortunately
    // managed resources do not
    // decouple the creation of a managed resource with the reading of the data
    // from the storage)
    this.managedData = managedData;

  }

  public void loadStoredModels() {
    log.info("------ managed models ~ loading ------");

    if ((managedData != null) && (managedData instanceof List)) {
      final List<Map<String,Object>> up = (List<Map<String,Object>>) managedData;
      for (final Map<String,Object> u : up) {
        try {
          final LTRScoringModel algo = makeLTRScoringModel(u);
          addModel(algo);
        } catch (final ModelException e) {
          throw new SolrException(ErrorCode.BAD_REQUEST, e);
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  public LTRScoringModel makeLTRScoringModel(String json)
      throws ModelException {
    Object parsedJson = null;
    try {
      parsedJson = ObjectBuilder.fromJSON(json);
    } catch (final IOException ioExc) {
      throw new ModelException("ObjectBuilder failed parsing json", ioExc);
    }
    return makeLTRScoringModel((Map<String,Object>) parsedJson);
  }
  
  private void checkFeatureValidity(LTRScoringModel meta) throws ModelException {
    final List<Feature> featureList = meta.getFeatures();
    final String modelName = meta.getName();
    if (featureList.isEmpty()) {
      throw new ModelException("no features declared for model "
          + modelName);
    }

    final Set<String> featureNames = new HashSet<>();
    for (final Feature feature : featureList) {
      final String fname = feature.getName();
      if (!featureNames.add(fname)) {
        throw new ModelException("duplicated feature " + fname + " in model "
            + modelName);
      }
    }
  }

  @SuppressWarnings("unchecked")
  public LTRScoringModel makeLTRScoringModel(Map<String,Object> map)
      throws ModelException {
    final String name = (String) map.get(CommonLTRParams.MODEL_NAME);
    String featureStoreName = (String) map.get(CommonLTRParams.MODEL_FEATURE_STORE);
    final FeatureStore fstore = featureStores.getFeatureStore(featureStoreName);
    featureStoreName = fstore.getName();  // if featureStoreName was null before this gets actual name
    if (!map.containsKey(CommonLTRParams.MODEL_FEATURE_LIST)) {
      // check if the model has a list of features to be used for computing the
      // ranking score
      throw new SolrException(ErrorCode.BAD_REQUEST,
          "Missing mandatory field features");
    }
    final List<Object> featureList = (List<Object>) map
        .get(CommonLTRParams.MODEL_FEATURE_LIST);
    final List<Feature> features = new ArrayList<>();
    final List<Normalizer> norms = new ArrayList<>();
    for (final Object modelFeature : featureList) {
      final Map<String,Object> modelFeatureMap =
          (Map<String,Object>) modelFeature;

      final String featureName = (String) modelFeatureMap.get(CommonLTRParams.FEATURE_NAME);
      final Feature feature = (featureName == null ? null : fstore.get(featureName));
      if (feature == null) {
        throw new SolrException(ErrorCode.BAD_REQUEST,
            "feature " + featureName + " not found in store " + fstore.getName());
      }

      final Object normObj = modelFeatureMap.get(CommonLTRParams.FEATURE_NORM);
      final Normalizer norm = (normObj == null ? IdentityNormalizer.INSTANCE :
        fromNormalizerMap(solrResourceLoader, (Map<String,Object>) normObj));

      features.add(feature);
      norms.add(norm);
    }
    
    @SuppressWarnings("unchecked")
    final Map<String,Object> params = (Map<String,Object>) map.get(CommonLTRParams.MODEL_PARAMS);

    final String type = (String) map.get(CommonLTRParams.MODEL_CLASS);
    LTRScoringModel meta = null;
    try {
      // create an instance of the model
      meta = solrResourceLoader.newInstance(
          type,
          LTRScoringModel.class,
          new String[0], // no sub packages
          new Class[] { String.class, List.class, List.class, String.class, List.class, Map.class },
          new Object[] { name, features, norms, featureStoreName, fstore.getFeatures(), params });
    } catch (final Exception e) {
      throw new ModelException("Model type does not exist " + type, e);
    }

    checkFeatureValidity(meta);
    
    return meta;
  }



  public synchronized void addModel(LTRScoringModel meta) throws ModelException {
    try {
      log.info("adding model {}", meta.getName());
      checkFeatureValidity(meta);
      store.addModel(meta);
    } catch (final ModelException e) {
      throw new SolrException(ErrorCode.BAD_REQUEST, e);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  protected Object applyUpdatesToManagedData(Object updates) {
    if (updates instanceof List) {
      final List<Map<String,Object>> up = (List<Map<String,Object>>) updates;
      for (final Map<String,Object> u : up) {
        try {
          final LTRScoringModel algo = makeLTRScoringModel(u);
          addModel(algo);
        } catch (final ModelException e) {
          throw new SolrException(ErrorCode.BAD_REQUEST, e);
        }
      }
    }

    if (updates instanceof Map) {
      final Map<String,Object> map = (Map<String,Object>) updates;
      try {
        final LTRScoringModel algo = makeLTRScoringModel(map);
        addModel(algo);
      } catch (final ModelException e) {
        throw new SolrException(ErrorCode.BAD_REQUEST, e);
      }
    }

    return modelAsManagedResources(store);
  }

  @Override
  public synchronized void doDeleteChild(BaseSolrResource endpoint, String childId) {
    // FIXME: hack to delete all the stores
    if (childId.equals("*")) {
      store.clear();
    }
    if (store.containsModel(childId)) {
      store.delete(childId);
    }
    storeManagedData(applyUpdatesToManagedData(null));
  }

  /**
   * Called to retrieve a named part (the given childId) of the resource at the
   * given endpoint. Note: since we have a unique child managed store we ignore
   * the childId.
   */
  @Override
  public void doGet(BaseSolrResource endpoint, String childId) {

    final SolrQueryResponse response = endpoint.getSolrResponse();
    response.add(CommonLTRParams.MODELS_JSON_FIELD,
        modelAsManagedResources(store));
  }

  public LTRScoringModel getModel(String modelName) {
    // this function replicates getModelStore().getModel(modelName), but
    // it simplifies the testing (we can avoid to mock also a ModelStore).
    return store.getModel(modelName);
  }

  public ModelStore getModelStore() {
    return store;
  }

  @Override
  public String toString() {
    return "ManagedModelStore [store=" + store + ", featureStores="
        + featureStores + "]";
  }
  
  /**
   * Returns the available models as a list of Maps objects. After an update the
   * managed resources needs to return the resources in this format in order to
   * store in json somewhere (zookeeper, disk...)
   *
   * TODO investigate if it is possible to replace the managed resources' json
   * serializer/deserialiazer.
   *
   * @return the available models as a list of Maps objects
   */
  private static List<Object> modelAsManagedResources(ModelStore store) {
    final List<Object> list = new ArrayList<>(store.size());
    for (final LTRScoringModel modelmeta : store.getModels()) {
      final Map<String,Object> modelMap = new HashMap<>(5, 1.0f);
      modelMap.put((String)CommonLTRParams.MODEL_NAME, modelmeta.getName());
      modelMap.put((String)CommonLTRParams.MODEL_CLASS, modelmeta.getClass().getCanonicalName());
      modelMap.put((String)CommonLTRParams.MODEL_FEATURE_STORE, modelmeta.getFeatureStoreName());
      final List<Map<String,Object>> features = new ArrayList<>(modelmeta.numFeatures());
      final List<Feature> featureList = modelmeta.getFeatures();
      final List<Normalizer> normList = modelmeta.getNorms();
      if (normList.size() != featureList.size()) {
        throw new ModelException("Every feature must have a normalizer");
      }
      for (int idx = 0; idx <  featureList.size(); ++idx) {
        final Feature feature = featureList.get(idx);
        final Normalizer norm = normList.get(idx);
        final Map<String,Object> map = new HashMap<String,Object>(2, 1.0f);
        map.put("name", feature.getName());
        map.put("norm", toNormalizerMap(norm));
        features.add(map);
      }
      modelMap.put("features", features);
      modelMap.put("params", modelmeta.getParams());

      list.add(modelMap);
    }
    return list;
  }
  
  private static Normalizer fromNormalizerMap(SolrResourceLoader solrResourceLoader,
      Map<String,Object> normMap) {
    final String className = (String) normMap.get(CLASS_KEY);

    @SuppressWarnings("unchecked")
    final Map<String,Object> params = (Map<String,Object>) normMap.get(PARAMS_KEY);

    return Normalizer.getInstance(solrResourceLoader, className, params);
  }

  private static LinkedHashMap<String,Object> toNormalizerMap(Normalizer norm) {
    final LinkedHashMap<String,Object> normalizer = new LinkedHashMap<>(2, 1.0f);

    normalizer.put(CLASS_KEY, norm.getClass().getCanonicalName());

    final LinkedHashMap<String,Object> params = norm.paramsToMap();
    if (params != null) {
      normalizer.put(PARAMS_KEY, params);
    }

    return normalizer;
  }

}
