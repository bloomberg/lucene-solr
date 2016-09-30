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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Explanation;
import org.apache.solr.ltr.model.LTRScoringModel;
import org.apache.solr.ltr.model.ModelException;
import org.apache.solr.ltr.norm.Normalizer;
import org.apache.solr.util.SolrPluginUtils;

public class LambdaMARTModel extends LTRScoringModel {

  private final HashMap<String,Integer> fname2index;
  private List<RegressionTree> trees;

  public static RegressionTree createRegressionTree(Map<String,Object> map,
      HashMap<String,Integer> fname2index) {
    final RegressionTree rt = new RegressionTree(fname2index);
    if (map != null) {
      SolrPluginUtils.invokeSetters(rt, map.entrySet());
    }
    return rt;
  }

  public static RegressionTreeNode createRegressionTreeNode(Map<String,Object> map,
      HashMap<String,Integer> fname2index) {
    final RegressionTreeNode rtn = new RegressionTreeNode(fname2index);
    if (map != null) {
      SolrPluginUtils.invokeSetters(rtn, map.entrySet());
    }
    return rtn;
  }

  public static class RegressionTreeNode {
    private static final float NODE_SPLIT_SLACK = 1E-6f;

    private final HashMap<String,Integer> fname2index;

    private float value = 0f;
    private String feature;
    private int featureIndex = -1;
    private Float threshold;
    private RegressionTreeNode left;
    private RegressionTreeNode right;

    public void setValue(float value) {
      this.value = value;
    }

    public void setValue(String value) {
      this.value = Float.parseFloat(value);
    }

    public void setFeature(String feature) {
      this.feature = feature;
      final Integer idx = fname2index.get(this.feature);
      // this happens if the tree specifies a feature that does not exist
      // this could be due to lambdaSmart building off of pre-existing trees
      // that use a feature that is no longer output during feature extraction
      // TODO: make lambdaSmart (in rank_svm_final repo )
      // either remove trees that depend on such features
      // or prune them back above the split on that feature
      featureIndex = (idx == null) ? -1 : idx;
    }

    public void setThreshold(float threshold) {
      this.threshold = threshold + NODE_SPLIT_SLACK;
    }

    public void setThreshold(String threshold) {
      this.threshold = Float.parseFloat(threshold) + NODE_SPLIT_SLACK;
    }

    public void setLeft(Object left) {
      this.left = createRegressionTreeNode((Map<String,Object>) left, fname2index);
    }

    public void setRight(Object right) {
      this.right = createRegressionTreeNode((Map<String,Object>) right, fname2index);
    }

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
        return (left == null ? 0f : left.score(featureVector));
      } else {
        return (right == null ? 0f : right.score(featureVector));
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

    @Override
    public String toString() {
      final StringBuilder sb = new StringBuilder();
      if (isLeaf()) {
        sb.append(value);
      } else {
        sb.append("(feature=").append(feature);
        if (threshold != null) {
          sb.append(",threshold=").append(threshold.floatValue()-NODE_SPLIT_SLACK);
        } else {
          sb.append(",threshold=").append(threshold);
        }
        sb.append(",left=").append(left);
        sb.append(",right=").append(right);
        sb.append(')');
      }
      return sb.toString();
    }

    public RegressionTreeNode(HashMap<String,Integer> fname2index) throws ModelException {
      this.fname2index = fname2index;
    }

    public void validate() throws ModelException {
      if (isLeaf()) {
        return;
      }
      if (null == threshold) {
        throw new ModelException("LambdaMARTModel tree node is missing threshold");
      }
      if (null == left) {
        throw new ModelException("LambdaMARTModel tree node is missing left");
      } else {
        left.validate();
      }
      if (null == right) {
        throw new ModelException("LambdaMARTModel tree node is missing right");
      } else {
        right.validate();
      }
      if (left.isLeaf() && right.isLeaf()) {
        System.out.println("cpoerschke debug: should throw "+new ModelException("LambdaMARTModel tree node has two leaves"));
        //throw new ModelException("LambdaMARTModel tree node has two leaves");
      }
    }

  }

  public static class RegressionTree {

    private final HashMap<String,Integer> fname2index;

    private Float weight;
    private RegressionTreeNode root;

    public void setWeight(float weight) {
      this.weight = new Float(weight);
    }

    public void setWeight(String weight) {
      this.weight = new Float(weight);
    }

    public void setTree(Object root) {
      this.root = createRegressionTreeNode((Map<String,Object>)root, fname2index);
    }

    public float score(float[] featureVector) {
      return (weight == null || root == null ? 0f : weight.floatValue() * root.score(featureVector));
    }

    public String explain(float[] featureVector) {
      return (root == null ? "tree missing" : root.explain(featureVector));
    }

    @Override
    public String toString() {
      final StringBuilder sb = new StringBuilder();
      sb.append("(weight=").append(weight);
      sb.append(",root=").append(root);
      sb.append(")");
      return sb.toString();
    }

    public RegressionTree(HashMap<String,Integer> fname2index) throws ModelException {
      this.fname2index = fname2index;
    }

    public void validate() throws ModelException {
      if (weight == null) {
        throw new ModelException("LambdaMARTModel tree doesn't contain a weight");
      }
      if (root == null) {
        throw new ModelException("LambdaMARTModel tree doesn't contain a tree");
      } else {
        root.validate();
      }
    }
  }

  public void setTrees(Object trees) {
    if (trees != null) {
      this.trees = new ArrayList<RegressionTree>();
      for (final Object o : (List<Object>) trees) {
        final RegressionTree rt = createRegressionTree((Map<String,Object>) o, this.fname2index);
        this.trees.add(rt);
      }
    } else {
      this.trees = null;
    }
  }

  public LambdaMARTModel(String name, List<ModelFeature> modelFeatures,
      String featureStoreName, List<Feature> allFeatures,
      Map<String,Object> params) {
    super(name, modelFeatures, featureStoreName, allFeatures, params);

    fname2index = new HashMap<String,Integer>();
    for (int i = 0; i < modelFeatures.size(); ++i) {
      final String key = modelFeatures.get(i).getFeatureName();
      fname2index.put(key, i);
    }
  }

  @Deprecated
  public LambdaMARTModel(String name, List<Feature> features,
      List<Normalizer> norms,
      String featureStoreName, List<Feature> allFeatures,
      Map<String,Object> params) throws ModelException {
    super(name, features, norms, featureStoreName, allFeatures, params);

    fname2index = new HashMap<String,Integer>();
    for (int i = 0; i < features.size(); ++i) {
      final String key = features.get(i).getName();
      fname2index.put(key, i);
    }
  }

  @Override
  public void validate() throws ModelException {
    super.validate();
    if (trees == null) {
      throw new ModelException("no trees declared for model "+name);
    }
    for (RegressionTree tree : trees) {
      tree.validate();
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
  // 40.0 = lambdamartmodel [ org.apache.solr.ltr.ranking.LambdaMARTModel ]
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

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(getClass().getSimpleName());
    sb.append("(name=").append(getName());
    if (trees != null) {
    sb.append(",trees=[");
    for (int ii = 0; ii < trees.size(); ++ii) {
      if (ii>0) sb.append(',');
      sb.append(trees.get(ii));
    }
    sb.append("])");
    } else {
      sb.append(",trees=null)");
    }
    return sb.toString();
  }

}
