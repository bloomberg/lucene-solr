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
package org.apache.solr.ltr.feature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.solr.ltr.feature.norm.Normalizer;
import org.apache.solr.ltr.ranking.Feature;
import org.apache.solr.ltr.util.CommonLTRParams;
import org.apache.solr.ltr.util.ModelException;

/**
 * Contains the model and features declared.
 */
public class ModelStore {

  private final Map<String,LTRScoringAlgorithm> availableModels;

  public ModelStore() {
    availableModels = new HashMap<>();
  }

  public synchronized LTRScoringAlgorithm getModel(String name) {
    return availableModels.get(name);
  }

  public boolean containsModel(String modelName) {
    return availableModels.containsKey(modelName);
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
  public List<Object> modelAsManagedResources() {
    final List<Object> list = new ArrayList<>(availableModels.size());
    for (final LTRScoringAlgorithm modelmeta : availableModels.values()) {
      final Map<String,Object> modelMap = new HashMap<>(5, 1.0f);
      modelMap.put((String)CommonLTRParams.MODEL_NAME, modelmeta.getName());
      modelMap.put((String)CommonLTRParams.MODEL_CLASS, modelmeta.getClass().getCanonicalName());
      modelMap.put((String)CommonLTRParams.MODEL_FEATURE_STORE, modelmeta.getFeatureStoreName());
      final List<Map<String,Object>> features = new ArrayList<>(modelmeta.numFeatures());
      for (final Feature meta : modelmeta.getFeatures()) {
        final Map<String,Object> map = new HashMap<String,Object>(2, 1.0f);
        map.put("name", meta.getName());

        final Normalizer n = meta.getNorm();

        if (n != null) {
          map.put("norm", n.toMap());
        }
        features.add(map);

      }
      modelMap.put("features", features);
      modelMap.put("params", modelmeta.getParams());

      list.add(modelMap);
    }
    return list;
  }

  public void clear() {
    availableModels.clear();
  }

  @Override
  public String toString() {
    return "ModelStore [availableModels=" + availableModels.keySet() + "]";
  }

  public void delete(String modelName) {
    availableModels.remove(modelName);
  }

  public synchronized void addModel(LTRScoringAlgorithm modeldata)
      throws ModelException {
    final String name = modeldata.getName();

    if (containsModel(name)) {
      throw new ModelException("model '" + name
          + "' already exists. Please use a different name");
    }

    availableModels.put(modeldata.getName(), modeldata);
  }

}
