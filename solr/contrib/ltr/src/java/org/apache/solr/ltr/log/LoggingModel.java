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
package org.apache.solr.ltr.log;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Explanation;
import org.apache.solr.ltr.feature.LTRScoringAlgorithm;
import org.apache.solr.ltr.ranking.Feature;
import org.apache.solr.ltr.util.LTRUtils;

/**
 * a stubbed reranking model that will be used only for computing the features.
 **/
public class LoggingModel extends LTRScoringAlgorithm {

  final static String LOGGING_MODEL_NAME = "logging-model";

  public LoggingModel(String featureStoreName, List<Feature> allFeatures){
    this(LOGGING_MODEL_NAME, Collections.emptyList(), featureStoreName, allFeatures, LTRUtils.EMPTY_MAP);
  }


  protected LoggingModel(String name, List<Feature> features, String featureStoreName,
      List<Feature> allFeatures, Map<String,Object> params) {
    super(name, features, featureStoreName, allFeatures, params);
  }

  @Override
  public float score(float[] modelFeatureValuesNormalized) {
    return 0;
  }

  @Override
  public Explanation explain(LeafReaderContext context, int doc, float finalScore, List<Explanation> featureExplanations) {
    return Explanation.match(finalScore, toString()
        + " logging model, used only for logging the features");
  }

}
