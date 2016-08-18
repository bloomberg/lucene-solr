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
package org.apache.solr.ltr.ranking;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Explanation;
import org.apache.solr.ltr.feature.LTRScoringAlgorithm;
import org.apache.solr.ltr.util.ModelException;

public class RankSVMModel extends LTRScoringAlgorithm {

  protected float[] featureToWeight;

  /** name of the attribute containing the weight of the SVM model **/
  public static final String WEIGHTS_PARAM = "weights";

  public RankSVMModel(String name, List<Feature> features,
      String featureStoreName, List<Feature> allFeatures,
      Map<String,Object> params) throws ModelException {
    super(name, features, featureStoreName, allFeatures, params);

    if (!hasParams()) {
      throw new ModelException("Model " + name + " doesn't contain any weights");
    }

    final Map<String,Double> modelWeights = (Map<String,Double>) getParams()
        .get(WEIGHTS_PARAM);
    if ((modelWeights == null) || modelWeights.isEmpty()) {
      throw new ModelException("Model " + name + " doesn't contain any weights");
    }

    // List<Feature> features = getFeatures(); // model features
    featureToWeight = new float[features.size()];

    for (int i = 0; i < features.size(); ++i) {
      final String key = features.get(i).getName();
      if (!modelWeights.containsKey(key)) {
        throw new ModelException("no weight for feature " + key);
      }
      featureToWeight[i] = modelWeights.get(key).floatValue();
    }
  }

  @Override
  public float score(float[] modelFeatureValuesNormalized) {
    float score = 0;
    for (int i = 0; i < modelFeatureValuesNormalized.length; ++i) {
      score += modelFeatureValuesNormalized[i] * featureToWeight[i];
    }
    return score;
  }

  @Override
  public Explanation explain(LeafReaderContext context, int doc,
      float finalScore, List<Explanation> featureExplanations) {
    final List<Explanation> details = new ArrayList<>();
    int index = 0;

    for (final Explanation featureExplain : featureExplanations) {
      final List<Explanation> featureDetails = new ArrayList<>();
      featureDetails.add(Explanation.match(featureToWeight[index],
          "weight on feature"));
      featureDetails.add(featureExplain);

      details.add(Explanation.match(featureExplain.getValue()
          * featureToWeight[index], "prod of:", featureDetails));
      index++;
    }

    return Explanation.match(finalScore, toString()
        + " model applied to features, sum of:", details);
  }
}
