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
import org.apache.solr.ltr.feature.ModelStore;
import org.apache.solr.ltr.model.LTRScoringModel;
import org.apache.solr.ltr.model.LTRScoringModel.ModelFeature;
import org.apache.solr.ltr.model.ModelException;
import org.apache.solr.ltr.norm.Normalizer;
import org.apache.solr.ltr.util.CommonLTRParams;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.rest.BaseSolrResource;
import org.apache.solr.rest.ManagedResource;
import org.apache.solr.rest.ManagedResourceStorage.StorageIO;
import org.apache.solr.util.SolrPluginUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Menaged resource for storing a model
 */
public class ManagedModelStore extends ManagedResource implements
    ManagedResource.ChildResourceSupport {

  /** name of the attribute containing a class **/
  public static final String CLASS_KEY = "class";
  /** name of the attribute containing the features **/
  private static final Object FEATURES_KEY = "features";
  /** name of the attribute containing a name **/
  public static final String NAME_KEY = "name";
  /** name of the attribute containing a normalizer **/
  private static final String NORM_KEY = "norm";
  /** name of the attribute containing parameters **/
  public static final String PARAMS_KEY = "params";
  /** name of the attribute containing a store **/
  private static final String STORE_KEY = "store";

  private ModelStore store;
  private ManagedFeatureStore managedFeatureStore;

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  
  public ManagedModelStore(String resourceId, SolrResourceLoader loader,
      StorageIO storageIO) throws SolrException {
    super(resourceId, loader, storageIO);

    store = new ModelStore();

  }

  public void setManagedFeatureStore(ManagedFeatureStore managedFeatureStore) {
    log.info("INIT model store");
    this.managedFeatureStore = managedFeatureStore;
  }

  public ManagedFeatureStore getManagedFeatureStore() {
    return managedFeatureStore;
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
          final LTRScoringModel algo = makeLTRScoringModel(solrResourceLoader, managedFeatureStore, u);
          addModel(algo);
        } catch (final ModelException e) {
          throw new SolrException(ErrorCode.BAD_REQUEST, e);
        }
      }
    }
  }

  @Deprecated // in favour of LTRScoringModel.validate()
  private static void checkFeatureValidity(LTRScoringModel meta) throws ModelException {
    final Set<String> featureNames = new HashSet<>();
    for (final ModelFeature modelFeature : meta.getModelFeatures()) {
      final String fname = modelFeature.getFeatureName();
      if (!featureNames.add(fname)) {
        throw new ModelException("duplicated feature " + fname + " in model "
            + meta.getName());
      }
    }
  }

  public static class ManagedModelFeature extends ModelFeature {

    final SolrResourceLoader solrResourceLoader;
    final FeatureStore featureStore;

    private String featureName;

    public ManagedModelFeature(SolrResourceLoader solrResourceLoader, FeatureStore featureStore) {
      super();
      this.solrResourceLoader = solrResourceLoader;
      this.featureStore = featureStore;
    }

    /**
     * Validate that settings make sense and throws
     * {@link ModelException} if they do not make sense.
     */
    @Override
    public void validate(String modelName) throws ModelException {
      if (feature == null) {
        throw new ModelException("in model "+modelName+" feature "+featureName
            +" not found in store "+featureStore.getName());
      }
      if (normalizer == null) {
        throw new ModelException("null normalizer found in model "+modelName);
      }
      // do our validation first since our exceptions are more informative
      // than the super class exceptions for the same logic
      super.validate(modelName);
    }

    public void setName(String featureName) {
      this.featureName = featureName;
      this.feature = (featureName == null ? null : featureStore.get(featureName));
    }

    @SuppressWarnings("unchecked")
    public void setNorm(Object normalizer) {
      this.normalizer = fromNormalizerMap(solrResourceLoader,
          (Map<String,Object>) normalizer);
    }
  };
  
  public static ModelFeature getModelFeatureInstance(SolrResourceLoader solrResourceLoader,
      FeatureStore featureStore,
      Map<String,Object> params) {
    final ModelFeature modelFeature = new ManagedModelFeature(solrResourceLoader, featureStore);
    if (params != null) {
      SolrPluginUtils.invokeSetters(modelFeature, params.entrySet());
    }
    return modelFeature;
  }

  @SuppressWarnings("unchecked")
  public static LTRScoringModel makeLTRScoringModel(SolrResourceLoader solrResourceLoader,
      ManagedFeatureStore managedFeatureStore,
      Map<String,Object> map)
      throws ModelException {
    final List<ModelFeature> modelFeatures = new ArrayList<>();
    final FeatureStore featureStore = managedFeatureStore.getFeatureStore((String) map.get(STORE_KEY));

    final List<Object> featureList = (List<Object>) map.get(FEATURES_KEY);
    if (featureList != null) {
      for (final Object modelFeature : featureList) {
        modelFeatures.add(getModelFeatureInstance(solrResourceLoader, featureStore, (Map<String,Object>) modelFeature));
      }
    }

    final String type = (String) map.get(CLASS_KEY);
    final LTRScoringModel meta = LTRScoringModel.getInstance(solrResourceLoader,
        (String) map.get(CLASS_KEY), // modelClassName
        (String) map.get(NAME_KEY), // modelName
        modelFeatures,
        featureStore.getName(),
        featureStore.getFeatures(),
        (Map<String,Object>) map.get(PARAMS_KEY));

    checkFeatureValidity(meta); // TODO: remove this since getInstance already did meta.validate()

    return meta;
  }

  public synchronized void addModel(LTRScoringModel meta) throws ModelException {
    try {
      log.info("adding model {}", meta.getName());
      checkFeatureValidity(meta); // TODO: meta.validate(); instead
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
          final LTRScoringModel algo = makeLTRScoringModel(solrResourceLoader, managedFeatureStore, u);
          addModel(algo);
        } catch (final ModelException e) {
          throw new SolrException(ErrorCode.BAD_REQUEST, e);
        }
      }
    }

    if (updates instanceof Map) {
      final Map<String,Object> map = (Map<String,Object>) updates;
      try {
        final LTRScoringModel algo = makeLTRScoringModel(solrResourceLoader, managedFeatureStore, map);
        addModel(algo);
      } catch (final ModelException e) {
        throw new SolrException(ErrorCode.BAD_REQUEST, e);
      }
    }

    return modelsAsManagedResources(store.getModels());
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
        modelsAsManagedResources(store.getModels()));
  }

  public LTRScoringModel getModel(String modelName) {
    // this function replicates getModelStore().getModel(modelName), but
    // it simplifies the testing (we can avoid to mock also a ModelStore).
    return store.getModel(modelName);
  }

  public ModelStore unusedGetModelStore() {
    return store;
  }

  @Override
  public String toString() {
    return "ManagedModelStore [store=" + store + ", featureStores="
        + managedFeatureStore + "]";
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
  private static List<Object> modelsAsManagedResources(List<LTRScoringModel> models) {
    final List<Object> list = new ArrayList<>(models.size());
    for (final LTRScoringModel model : models) {
      list.add(toLTRScoringModelMap(model));
    }
    return list;
  }

  private static LTRScoringModel fromLTRScoringModelMap(SolrResourceLoader solrResourceLoader,
      Map<String,Object> modelMap) {
    return null;
  }

  private static LinkedHashMap<String,Object> toLTRScoringModelMap(LTRScoringModel model) {
    final LinkedHashMap<String,Object> modelMap = new LinkedHashMap<>(5, 1.0f);
    modelMap.put(NAME_KEY, model.getName());
    modelMap.put(CLASS_KEY, model.getClass().getCanonicalName());
    modelMap.put(STORE_KEY, model.getFeatureStoreName());
    final List<Map<String,Object>> modelFeatures = new ArrayList<>();
    for (ModelFeature modelFeature : model.getModelFeatures()) {
      modelFeatures.add(toModelFeatureMap(modelFeature));
    }
    modelMap.put("features", modelFeatures);
    modelMap.put(PARAMS_KEY, model.getParams());
    return modelMap;
  }

  private static ModelFeature fromModelFeatureMap(SolrResourceLoader solrResourceLoader,
      Map<String,Object> modelFeatureMap) {
    return null;
  }

  private static LinkedHashMap<String,Object> toModelFeatureMap(ModelFeature modelFeature) {
    final LinkedHashMap<String,Object> map = new LinkedHashMap<String,Object>(2, 1.0f);
    map.put(NAME_KEY, modelFeature.getFeatureName());
    map.put(NORM_KEY, toNormalizerMap(modelFeature.getNormalizer()));
    return map;
  }

  // TODO: make this private again later
  public static Normalizer fromNormalizerMap(SolrResourceLoader solrResourceLoader,
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
