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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import org.apache.solr.ltr.ranking.Feature;

public class FeatureStore {

  /** the name of the default feature store **/
  public static final String DEFAULT_FEATURE_STORE_NAME = "_DEFAULT_";

  private final LinkedHashMap<String,Feature> store = new LinkedHashMap<>(); // LinkedHashMap because we need predictable iteration order
  private final String name;

  public FeatureStore(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public Feature get(String name) {
    return store.get(name);
  }

  public int size() {
    return store.size();
  }

  public boolean containsFeature(String name) {
    return store.containsKey(name);
  }

  public List<Object> featuresAsManagedResources() {
    final List<Object> features = new ArrayList<Object>(store.size());
    for (final Feature f : store.values()) {
      features.add(f.toMap(name));
    }
    return features;
  }

  public void add(Feature feature) {
    store.put(feature.getName(), feature);
  }

  public List<Feature> getFeatures() {
    final List<Feature> storeValues = new ArrayList<Feature>(store.values());
    return Collections.unmodifiableList(storeValues);
  }

  public void clear() {
    store.clear();
  }

  @Override
  public String toString() {
    return "FeatureStore [features=" + store.keySet() + "]";
  }

}
