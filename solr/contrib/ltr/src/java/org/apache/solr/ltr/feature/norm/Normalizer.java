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
package org.apache.solr.ltr.feature.norm;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.lucene.search.Explanation;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.util.SolrPluginUtils;

/**
 * A normalizer normalizes the value of a feature. Once that the feature values
 * will be computed, the normalizer will be applied and the resulting values
 * will be received by the model.
 */
public abstract class Normalizer {

  /** name of the attribute containing the normalizer type **/
  private static final String TYPE_KEY = "type";
  /** name of the attribute containing the normalizer params **/
  private static final String PARAMS_KEY = "params";

  public abstract float normalize(float value);

  protected abstract LinkedHashMap<String,Object> paramsToMap();

  public Explanation explain(Explanation explain) {
    final float normalized = normalize(explain.getValue());
    final String explainDesc = "normalized using " + toString();

    return Explanation.match(normalized, explainDesc, explain);
  }

  public static Normalizer getInstance(SolrResourceLoader solrResourceLoader,
      String type, Map<String,Object> params) {
    final Normalizer f = solrResourceLoader.newInstance(type, Normalizer.class);
    if (params != null) {
      SolrPluginUtils.invokeSetters(f, params.entrySet());
    }
    return f;
  }

  public static Normalizer fromMap(SolrResourceLoader solrResourceLoader,
      Map<String,Object> normMap) {
    final String type =
        (String) normMap.get(TYPE_KEY);

    @SuppressWarnings("unchecked")
    final Map<String,Object> params =
    (Map<String,Object>) normMap.get(PARAMS_KEY);

    return Normalizer.getInstance(solrResourceLoader,
        type, params);
  }

  public LinkedHashMap<String,Object> toMap() {
    final LinkedHashMap<String,Object> normalizer = new LinkedHashMap<>(2, 1.0f);

    normalizer.put(TYPE_KEY, getClass().getCanonicalName());

    final LinkedHashMap<String,Object> params = paramsToMap();
    if (params != null) {
      normalizer.put(PARAMS_KEY, params);
    }

    return normalizer;
  }

}
