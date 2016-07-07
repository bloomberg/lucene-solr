package org.apache.solr.ltr.ranking;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Explanation;
import org.apache.solr.ltr.util.ModelException;
import org.apache.solr.ltr.util.NamedParams;

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

/**
 * This model introduces a manual boosting to the score calculation,
 * which adds flexibility when you don't have much data for training.
 * However, you will loose some of the benefits
 * of a machine learned model, which was optimized to rerank your results.
 */
public abstract class HeuristicBoostedModel extends CompositeLTRScoringAlgorithm {
  public static final String HEURISTIC_BOOST_PARAM = "boost";

  protected HeuristicFeatureBoost heuristicFeatureBoost;

  public enum BoostType {PRODUCT, SUM};

  class HeuristicFeatureBoost {
    public static final String FEATURE_NAME = "feature";
    public static final String WEIGHT = "weight";
    public static final String BOOST_TYPE = "type";

    public String feature;
    public int featureIndex;
    public float weight;
    public BoostType type;

    public HeuristicFeatureBoost(Map<String, Object> featureBoostParam) {
      checkParams(featureBoostParam);
      feature = ((String)featureBoostParam.get(FEATURE_NAME));
      weight = NamedParams.convertToFloat(featureBoostParam.get(WEIGHT));
      featureIndex = getFeatureIndex(feature);
      type = BoostType.PRODUCT;

      if (featureBoostParam.get(BOOST_TYPE) != null) {
        String fetchedType = (String) featureBoostParam.get(BOOST_TYPE);
        try {
          type = BoostType.valueOf(StringUtils.strip(fetchedType).toUpperCase());
        } catch (IllegalArgumentException e) {
          throw new ModelException("Model " + HeuristicBoostedModel.this.getName() + " boost type : " + fetchedType + " is not supported");
        }
      }
    }

    private void checkParams(Map<String, Object> euristicBoostParams) {
      checkParam(euristicBoostParams, FEATURE_NAME);
      checkParam(euristicBoostParams, WEIGHT);
    }

    private void checkParam(Map<String, Object> featureBoostParams, String paramName) {
      String modelName = HeuristicBoostedModel.this.getName();
      if (!featureBoostParams.containsKey(paramName)) {
        throw new ModelException("Model " + modelName + " required param " + paramName + " not defined");
      }
    }

    private int getFeatureIndex( String featureName) {
      String modelName = HeuristicBoostedModel.this.getName();
      final List<Feature> features = getFeatures();
      Feature featureToBoost = null;
      for (Feature f : features) {
        if (f.getName().equals(featureName)) {
          featureToBoost = f;
          break;
        }
      }

      if (featureToBoost != null) {
        return featureToBoost.getId();
      } else {
        throw new ModelException("Model " + modelName + " doesn't contain any feature with the name=[" + feature + "}");
      }
    }
  }

  public HeuristicBoostedModel(String name, List<Feature> features,
                               String featureStoreName, List<Feature> allFeatures,
                               NamedParams params) throws ModelException {
    super(name, features, featureStoreName, allFeatures, params);
    initHeuristicBoost();
  }

  protected void initHeuristicBoost() {
    Map<String, Object> params = (Map<String, Object>) HeuristicBoostedModel.this.getParams().get(HEURISTIC_BOOST_PARAM);
    if (params != null) {
      this.heuristicFeatureBoost = new HeuristicFeatureBoost(params);
    }
  }

  @Override
  public float score(float[] modelFeatureValuesNormalized) {
    float internalModelScore = scoringAlgorithm.score(modelFeatureValuesNormalized);
    float finalScore = internalModelScore;
    if (heuristicFeatureBoost != null) {
      float boostFeatureValue = modelFeatureValuesNormalized[heuristicFeatureBoost.featureIndex];
      float weightedBoostFeatureValue = getWeightedBoostFeatureValue(internalModelScore, boostFeatureValue);

      switch (heuristicFeatureBoost.type) {
        case PRODUCT:
          finalScore *= weightedBoostFeatureValue;
          break;

        case SUM:
          finalScore += weightedBoostFeatureValue;
          break;
      }
    }
    return finalScore;
  }

  private float getWeightedBoostFeatureValue(float internalModelScore, float boostFeatureValue) {
    float weightedBoostFeatureValue = heuristicFeatureBoost.weight * boostFeatureValue;
    switch (heuristicFeatureBoost.type) {
      case PRODUCT:
        if ((internalModelScore < 0 && weightedBoostFeatureValue > 0) || (internalModelScore > 0 && weightedBoostFeatureValue < 0)) {
          weightedBoostFeatureValue = 1 / weightedBoostFeatureValue;
        }
        break;
    }
    return weightedBoostFeatureValue;
  }

  @Override
  public Explanation explain(LeafReaderContext context, int doc, float finalScore, List<Explanation> featureExplanations) {
    final float[] modelFeatureValuesNormalized = getFeatureVector(featureExplanations);
    final float internalModelScore = scoringAlgorithm.score(modelFeatureValuesNormalized);
    if (heuristicFeatureBoost == null) {
      return scoringAlgorithm.explain(context, doc, internalModelScore, featureExplanations);
    }

    final List<Explanation> details = new ArrayList<>();
    final float boostFeatureValue = modelFeatureValuesNormalized[heuristicFeatureBoost.featureIndex];
    float weightedBoostFeatureValue = getWeightedBoostFeatureValue(internalModelScore, boostFeatureValue);
    final Explanation boostExplain = Explanation.match(weightedBoostFeatureValue,
        heuristicFeatureBoost.weight + " weight on feature [" + heuristicFeatureBoost.feature + "] : " + boostFeatureValue);
    details.add(boostExplain);
    final Explanation internalModelExplain = scoringAlgorithm.explain(context, doc, internalModelScore, featureExplanations);
    details.add(internalModelExplain);

    return Explanation.match(finalScore, toString()
        + " model applied to features, " + heuristicFeatureBoost.type + " of:", details);
  }

}
