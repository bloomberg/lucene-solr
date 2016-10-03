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
package org.apache.solr.ltr.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Explanation;
import org.apache.solr.ltr.feature.Feature;
import org.apache.solr.ltr.norm.Normalizer;
import org.apache.solr.ltr.util.LTRUtils;

public class LambdaMARTModel extends LTRScoringModel {

  private final List<RegressionTree> trees = new ArrayList<RegressionTree>();

  class RegressionTreeNode {
    private static final float NODE_SPLIT_SLACK = 1E-6f;
    private final float value;
    private final String feature;
    private final int featureIndex;
    private final float threshold;
    private final RegressionTreeNode left;
    private final RegressionTreeNode right;

    public boolean isLeaf() {
      return feature == null;
    }

    public float score(float[] featureVector) {
      if (isLeaf()) {
        return value;
      }

      // unsupported feature (tree is looking for a feature that does not exist)
      if  ((featureIndex < 0) || (featureIndex >= featureVector.length)) {
        return 0f;
      }

      if (featureVector[featureIndex] <= threshold) {
        return left.score(featureVector);
      } else {
        return right.score(featureVector);
      }
    }

    public String explain(float[] featureVector) {
      if (isLeaf()) {
        return "val: " + value;
      }

      // unsupported feature (tree is looking for a feature that does not exist)
      if  ((featureIndex < 0) || (featureIndex >= featureVector.length)) {
        return  "'" + feature + "' does not exist in FV, Return Zero";
      }

      // could store extra information about how much training data supported
      // each branch and report
      // that here

      if (featureVector[featureIndex] <= threshold) {
        String rval = "'" + feature + "':" + featureVector[featureIndex] + " <= "
            + threshold + ", Go Left | ";
        return rval + left.explain(featureVector);
      } else {
        String rval = "'" + feature + "':" + featureVector[featureIndex] + " > "
            + threshold + ", Go Right | ";
        return rval + right.explain(featureVector);
      }
    }

    public RegressionTreeNode(Map<String,Object> map,
        HashMap<String,Integer> fname2index) throws ModelException {
      if (map.containsKey("value")) {
        value = LTRUtils.convertToFloat(map.get("value"));
        feature = null;
        featureIndex = -1;
        threshold = 0f;
        left = null;
        right = null;
      } else {

        final Object of = map.get("feature");
        if (null == of) {
          throw new ModelException(
              "LambdaMARTModel tree node is missing feature");
        }

        value = 0f;
        feature = (String) of;
        final Integer idx = fname2index.get(feature);
        // this happens if the tree specifies a feature that does not exist
        // this could be due to lambdaSmart building off of pre-existing trees
        // that use a feature that is no longer output during feature extraction
        // TODO: make lambdaSmart (in rank_svm_final repo )
        // either remove trees that depend on such features
        // or prune them back above the split on that feature
        featureIndex = (idx == null) ? -1 : idx;

        final Object ot = map.get("threshold");
        if (null == ot) {
          throw new ModelException(
              "LambdaMARTModel tree node is missing threshold");
        }

        threshold = LTRUtils.convertToFloat(ot) + NODE_SPLIT_SLACK;

        final Object ol = map.get("left");
        if (null == ol) {
          throw new ModelException("LambdaMARTModel tree node is missing left");
        }

        left = new RegressionTreeNode((Map<String,Object>) ol, fname2index);

        final Object or = map.get("right");
        if (null == or) {
          throw new ModelException("LambdaMARTModel tree node is missing right");
        }

        right = new RegressionTreeNode((Map<String,Object>) or, fname2index);
      }
    }

  }

  class RegressionTree {
    private final float weight;
    private final RegressionTreeNode root;

    public float score(float[] featureVector) {
      return weight * root.score(featureVector);
    }

    public String explain(float[] featureVector) {
      return root.explain(featureVector);
    }

    public RegressionTree(Map<String,Object> map,
        HashMap<String,Integer> fname2index) throws ModelException {
      final Object ow = map.get("weight");
      if (null == ow) {
        throw new ModelException(
            "LambdaMARTModel tree doesn't contain a weight");
      }

      weight = LTRUtils.convertToFloat(ow);

      final Object ot = map.get("tree");

      if (null == ot) {
        throw new ModelException("LambdaMARTModel tree doesn't contain a tree");
      }

      root = new RegressionTreeNode((Map<String,Object>) ot, fname2index);
    }
  }

  public LambdaMARTModel(String name, List<Feature> features,
      List<Normalizer> norms,
      String featureStoreName, List<Feature> allFeatures,
      Map<String,Object> params) throws ModelException {
    super(name, features, norms, featureStoreName, allFeatures, params);
    
    if (!hasParams()) {
      throw new ModelException("LambdaMARTModel doesn't contain any params");
    }

    final HashMap<String,Integer> fname2index = new HashMap<String,Integer>();
    for (int i = 0; i < features.size(); ++i) {
      final String key = features.get(i).getName();
      fname2index.put(key, i);
    }

    
    final List<Object> jsonTrees = getParams().containsKey("trees") ?
        (List<Object>) getParams().get("trees") : null;

    if (jsonTrees != null) {
      for (final Object o : jsonTrees) {
        final Map<String,Object> t = (Map<String,Object>) o;
        final RegressionTree rt = new RegressionTree(t, fname2index);
        trees.add(rt);
      }
    }

  }

  @Override
  public float score(float[] modelFeatureValuesNormalized) {
    float score = 0;
    for (final RegressionTree t : trees) {
      score += t.score(modelFeatureValuesNormalized);
    }
    return score;
  }

  // /////////////////////////////////////////
  // produces a string that looks like:
  // 40.0 = lambdamartmodel [ org.apache.solr.ltr.model.LambdaMARTModel ]
  // model applied to
  // features, sum of:
  // 50.0 = tree 0 | 'matchedTitle':1.0 > 0.500001, Go Right |
  // 'this_feature_doesnt_exist' does not
  // exist in FV, Go Left | val: 50.0
  // -10.0 = tree 1 | val: -10.0
  @Override
  public Explanation explain(LeafReaderContext context, int doc,
      float finalScore, List<Explanation> featureExplanations) {
    // FIXME this still needs lots of work
    final float[] fv = new float[featureExplanations.size()];
    int index = 0;
    for (final Explanation featureExplain : featureExplanations) {
      fv[index] = featureExplain.getValue();
      index++;
    }

    final List<Explanation> details = new ArrayList<>();
    index = 0;

    for (final RegressionTree t : trees) {
      final float score = t.score(fv);
      final Explanation p = Explanation.match(score, "tree " + index + " | "
          + t.explain(fv));
      details.add(p);
      index++;
    }

    return Explanation.match(finalScore, toString()
        + " model applied to features, sum of:", details);
  }
}
