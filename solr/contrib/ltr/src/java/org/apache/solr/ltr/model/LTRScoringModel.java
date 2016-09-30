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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.ltr.feature.FeatureException;
import org.apache.solr.ltr.norm.IdentityNormalizer;
import org.apache.solr.ltr.norm.Normalizer;
import org.apache.solr.ltr.ranking.Feature;
import org.apache.solr.ltr.ranking.Feature.FeatureWeight;
import org.apache.solr.ltr.ranking.FeatureWeightCreator;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.util.SolrPluginUtils;

/**
 * Contains all the data needed for loading a model.
 */

public abstract class LTRScoringModel {

  public static class ModelFeature  implements FeatureWeightCreator {

    protected Feature feature;
    protected Normalizer normalizer = IdentityNormalizer.INSTANCE;

    public Normalizer getNormalizer() {
      return normalizer;
    }

    protected ModelFeature() {
    }

    public ModelFeature(Feature feature, Normalizer normalizer) {
      this.feature = feature;
      this.normalizer = normalizer;
    }

    /**
     * Validate that settings make sense and throws
     * {@link ModelException} if they do not make sense.
     */
    public void validate(String modelName) throws ModelException {
      if (feature == null) {
        throw new ModelException("null feature found in model "+modelName);
      }
      if (normalizer == null) {
        throw new ModelException("null normalizer found in model "+modelName);
      }
    }

    public String getFeatureName() {
      return feature.getName();
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((feature == null) ? 0 : feature.hashCode());
      result = prime * result + ((normalizer == null) ? 0 : normalizer.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (obj == null) return false;
      if (getClass() != obj.getClass()) return false;
      ModelFeature other = (ModelFeature) obj;
      if (feature == null) {
        if (other.feature != null) return false;
      } else if (!feature.equals(other.feature)) return false;
      if (normalizer == null) {
        if (other.normalizer != null) return false;
      } else if (!normalizer.equals(other.normalizer)) return false;
      return true;
    }

    @Override
    public FeatureWeight createFeatureWeight(IndexSearcher searcher, boolean needsScores, SolrQueryRequest request,
        Query originalQuery, Map<String,String[]> efi) throws IOException {
      return feature.createFeatureWeight(searcher, needsScores, request, originalQuery, efi);
    }

    @Override
    public int getIndex() {
      return feature.getIndex();
    }

  }

  protected final String name;
  private final String featureStoreName;
  protected final List<ModelFeature> modelFeatures;
  private final List<Feature> allFeatures;
  private final Map<String,Object> params;

  public static LTRScoringModel getInstance(SolrResourceLoader solrResourceLoader,
      String className, String name, List<ModelFeature> modelFeatures,
      String featureStoreName, List<Feature> allFeatures,
      Map<String,Object> params) throws ModelException {
    final LTRScoringModel model;
    try {
      // create an instance of the model
      model = solrResourceLoader.newInstance(
          className,
          LTRScoringModel.class,
          new String[0], // no sub packages
          new Class[] { String.class, List.class, String.class, List.class, Map.class },
          new Object[] { name, modelFeatures, featureStoreName, allFeatures, params });
      if (params != null) {
        SolrPluginUtils.invokeSetters(model, params.entrySet());
      }
    } catch (final Exception e) {
      System.out.println("cpoerschke debug: caught "+e);
      throw new ModelException("Model type does not exist " + className, e);
    }
    model.validate();
    return model;
  }

  @Deprecated
  public static LTRScoringModel getInstance(SolrResourceLoader solrResourceLoader,
      String className, String name, List<Feature> features,
      List<Normalizer> norms,
      String featureStoreName, List<Feature> allFeatures,
      Map<String,Object> params) throws ModelException {
    final LTRScoringModel model;
    try {
      // create an instance of the model
      model = solrResourceLoader.newInstance(
          className,
          LTRScoringModel.class,
          new String[0], // no sub packages
          new Class[] { String.class, List.class, List.class, String.class, List.class, Map.class },
          new Object[] { name, features, norms, featureStoreName, allFeatures, params });
      if (params != null) {
        SolrPluginUtils.invokeSetters(model, params.entrySet());
      }
    } catch (final Exception e) {
      System.out.println("cpoerschke debug: caught "+e);
      throw new ModelException("Model type does not exist " + className, e);
    }
    model.validate();
    return model;
  }

  public LTRScoringModel(String name, List<ModelFeature> modelFeatures,
      String featureStoreName, List<Feature> allFeatures,
      Map<String,Object> params) {
    this.name = name;
    this.featureStoreName = featureStoreName;
    this.allFeatures = allFeatures;
    this.params = params;
    this.modelFeatures = modelFeatures;
  }

  @Deprecated
  public LTRScoringModel(String name, List<Feature> features,
      List<Normalizer> norms,
      String featureStoreName, List<Feature> allFeatures,
      Map<String,Object> params) {
    this.name = name;
    this.featureStoreName = featureStoreName;
    this.allFeatures = allFeatures;
    this.params = params;
    this.modelFeatures = new ArrayList<>(features.size());
    for (int ii=0; ii<features.size(); ++ii) {
      this.modelFeatures.add(new ModelFeature(features.get(ii), norms.get(ii)));
    }
  }

  /**
   * Validate that settings make sense and throws
   * {@link ModelException} if they do not make sense.
   */
  public void validate() throws ModelException {
    if (modelFeatures.isEmpty()) {
      throw new ModelException("no features declared for model "+name);
    }
    for (ModelFeature modelFeature : modelFeatures) {
      modelFeature.validate(name);
    }
  }

  /**
   * @return the name
   */
  public String getName() {
    return name;
  }

  /**
   * @return the model features
   */
  public List<ModelFeature> getModelFeatures() {
    return Collections.unmodifiableList(modelFeatures);
  }

  /**
   * @return the feature weight creators
   */
  public List<FeatureWeightCreator> getFeatureWeightCreators() {
    return Collections.unmodifiableList(modelFeatures);
  }

  public Map<String,Object> getParams() {
    return params;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = (prime * result) + ((modelFeatures == null) ? 0 : modelFeatures.hashCode());
    result = (prime * result) + ((name == null) ? 0 : name.hashCode());
    result = (prime * result) + ((params == null) ? 0 : params.hashCode());
    result = (prime * result) + ((featureStoreName == null) ? 0 : featureStoreName.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    final LTRScoringModel other = (LTRScoringModel) obj;
    if (modelFeatures == null) {
      if (other.modelFeatures != null) {
        return false;
      }
    } else if (!modelFeatures.equals(other.modelFeatures)) {
      return false;
    }
    if (name == null) {
      if (other.name != null) {
        return false;
      }
    } else if (!name.equals(other.name)) {
      return false;
    }
    if (params == null) {
      if (other.params != null) {
        return false;
      }
    } else if (!params.equals(other.params)) {
      return false;
    }
    if (featureStoreName == null) {
      if (other.featureStoreName != null) {
        return false;
      }
    } else if (!featureStoreName.equals(other.featureStoreName)) {
      return false;
    }


    return true;
  }

  public boolean hasParams() {
    return !((params == null) || params.isEmpty());
  }

  public Collection<FeatureWeightCreator> getAllFeatureWeightCreators() {
    return Collections.unmodifiableList(allFeatures);
  }

  public String getFeatureStoreName() {
    return featureStoreName;
  }
  
  /**
   * Given a list of normalized values for all features a scoring algorithm
   * cares about, calculate and return a score.
   *
   * @param modelFeatureValuesNormalized
   *          List of normalized feature values. Each feature is identified by
   *          its id, which is the index in the array
   * @return The final score for a document
   */
  public abstract float score(float[] modelFeatureValuesNormalized);

  /**
   * Similar to the score() function, except it returns an explanation of how
   * the features were used to calculate the score.
   *
   * @param context
   *          Context the document is in
   * @param doc
   *          Document to explain
   * @param finalScore
   *          Original score
   * @param featureExplanations
   *          Explanations for each feature calculation
   * @return Explanation for the scoring of a document
   */
  public abstract Explanation explain(LeafReaderContext context, int doc,
      float finalScore, List<Explanation> featureExplanations);

  @Override
  public String toString() {
    return  getClass().getSimpleName() + "(name="+getName()+")";
  }
  
  /**
   * Goes through all the stored feature values, and calculates the normalized
   * values for all the features that will be used for scoring.
   */
  public void normalizeFeaturesInPlace(float[] modelFeatureValues) {
    float[] modelFeatureValuesNormalized = modelFeatureValues;
    if (modelFeatureValues.length != modelFeatures.size()) {
      throw new FeatureException("Got "
          + modelFeatureValues.length + " modelFeatureValues to use with "
          + modelFeatures.size() + " modelFeatures");
    }
    for(int idx = 0; idx < modelFeatureValuesNormalized.length; ++idx) {
      modelFeatureValuesNormalized[idx] = 
          modelFeatures.get(idx).getNormalizer().normalize(modelFeatureValuesNormalized[idx]);
    }
  }
  
  public Explanation getNormalizerExplanation(Explanation e, int idx) {
    final Normalizer n = modelFeatures.get(idx).getNormalizer();
    if (n != IdentityNormalizer.INSTANCE) {
      return n.explain(e);
    }
    return e;
  } 

}
