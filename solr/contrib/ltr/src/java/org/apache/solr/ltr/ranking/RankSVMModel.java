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
import org.apache.solr.ltr.model.LTRScoringModel;
import org.apache.solr.ltr.model.ModelException;
import org.apache.solr.ltr.norm.Normalizer;

public class RankSVMModel extends LTRScoringModel {

  protected Float[] featureToWeight;

  public void setWeights(Object weights) {
    final Map<String,Double> modelWeights = (Map<String,Double>) weights;
    if (modelWeights != null) {
      for (int ii = 0; ii < modelFeatures.size(); ++ii) {
        final String key = modelFeatures.get(ii).getFeatureName();
        final Double val = modelWeights.get(key);
        featureToWeight[ii] = (val == null ? null : new Float(val.floatValue()));
      }
    }
  }

  public RankSVMModel(String name, List<ModelFeature> modelFeatures,
      String featureStoreName, List<Feature> allFeatures,
      Map<String,Object> params) {
    super(name, modelFeatures, featureStoreName, allFeatures, params);
    featureToWeight = new Float[modelFeatures.size()];
  }

  @Deprecated
  public RankSVMModel(String name, List<Feature> features,
      List<Normalizer> norms,
      String featureStoreName, List<Feature> allFeatures,
      Map<String,Object> params) {
    super(name, features, norms, featureStoreName, allFeatures, params);
    featureToWeight = new Float[features.size()];
  }

  @Override
  public void validate() throws ModelException {
    super.validate();

    final ArrayList<String> missingWeightFeatureNames = new ArrayList<String>();
    for (int i = 0; i < modelFeatures.size(); ++i) {
      if (featureToWeight[i] == null) {
        missingWeightFeatureNames.add(modelFeatures.get(i).getFeatureName());
      }
    }
    if (missingWeightFeatureNames.size() == modelFeatures.size()) {
      throw new ModelException("Model " + name + " doesn't contain any weights");
    }
    if (!missingWeightFeatureNames.isEmpty()) {
      throw new ModelException("Model " + name + " lacks weight(s) for "+missingWeightFeatureNames);
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

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(getClass().getSimpleName());
    sb.append("(name=").append(getName());
    sb.append(",featureWeights=[");
    for (int ii = 0; ii < modelFeatures.size(); ++ii) {
      if (ii>0) sb.append(',');
      final String key = modelFeatures.get(ii).getFeatureName();
      sb.append(key).append('=').append(featureToWeight[ii]);
    }
    sb.append("])");
    return sb.toString();
  }

}
