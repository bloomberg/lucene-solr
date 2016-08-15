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

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DisiPriorityQueue;
import org.apache.lucene.search.DisiWrapper;
import org.apache.lucene.search.DisjunctionDISIApproximation;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.solr.ltr.feature.LTRScoringAlgorithm;
import org.apache.solr.ltr.feature.norm.Normalizer;
import org.apache.solr.ltr.feature.norm.impl.IdentityNormalizer;
import org.apache.solr.ltr.log.FeatureLogger;
import org.apache.solr.ltr.ranking.Feature.FeatureWeight;
import org.apache.solr.ltr.ranking.Feature.FeatureWeight.FeatureScorer;
import org.apache.solr.ltr.util.FeatureException;
import org.apache.solr.request.SolrQueryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The ranking query that is run, reranking results using the
 * LTRScoringAlgorithm algorithm
 */
public class ModelQuery extends Query {

  // contains a description of the model
  protected LTRScoringAlgorithm meta;
  // feature logger to output the features.
  protected FeatureLogger<?> fl;
  // Map of external parameters, such as query intent, that can be used by
  // features
  protected Map<String,String> efi;
  // Original solr query used to fetch matching documents
  protected Query originalQuery;
  // Original solr request
  protected SolrQueryRequest request;
  protected boolean featuresRequested;
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public ModelQuery(LTRScoringAlgorithm meta) {
    this(meta, false);
  }
  
  public ModelQuery(LTRScoringAlgorithm meta, boolean featRequested) {
    this.meta = meta;
    this.featuresRequested = featRequested; 
  }

  public LTRScoringAlgorithm getMetadata() {
    return meta;
  }

  public void setFeatureLogger(FeatureLogger fl) {
    this.fl = fl;
  }

  public FeatureLogger getFeatureLogger() {
    return fl;
  }

  public String getFeatureStoreName(){
    return meta.getFeatureStoreName();
  }

  public void setOriginalQuery(Query mainQuery) {
    originalQuery = mainQuery;
  }

  public void setExternalFeatureInfo(Map<String,String> externalFeatureInfo) {
    efi = externalFeatureInfo;
  }

  public Map<String,String> getExternalFeatureInfo() {
    return efi;
  }

  public void setRequest(SolrQueryRequest request) {
    this.request = request;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = classHash();
    result = (prime * result) + ((meta == null) ? 0 : meta.hashCode());
    result = (prime * result)
        + ((originalQuery == null) ? 0 : originalQuery.hashCode());
    result = (prime * result) + ((efi == null) ? 0 : efi.hashCode());
    result = (prime * result) + this.toString().hashCode();
    return result;
  }
@Override
  public boolean equals(Object o) {
    return sameClassAs(o) &&  equalsTo(getClass().cast(o));
  }

  private boolean equalsTo(ModelQuery other) {
    if (meta == null) {
      if (other.meta != null) {
        return false;
      }
    } else if (!meta.equals(other.meta)) {
      return false;
    }
    if (originalQuery == null) {
      if (other.originalQuery != null) {
        return false;
      }
    } else if (!originalQuery.equals(other.originalQuery)) {
      return false;
    }
    if (efi == null) {
      if (other.efi != null) {
        return false;
      }
    } else if (!efi.equals(other.efi)) {
      return false;
    }
    return true;
  }

  public SolrQueryRequest getRequest() {
    return request;
  }

  @Override
  public ModelWeight createWeight(IndexSearcher searcher, boolean needsScores, float boost)
      throws IOException {
    boolean newCode = true;
    if (!newCode){
      this.featuresRequested = true;
      long  start = System.currentTimeMillis();
      final FeatureWeight[] allFeatureWeights = createWeights(meta.getAllFeatures(),
          searcher, needsScores);
      final FeatureWeight[] modelFeaturesWeights = createWeights(meta.getFeatures(),
          searcher, needsScores);
      long  end = System.currentTimeMillis();
      log.info("In ModelQuery.java::createWeight() OLD createWeight time:{} ms", (end-start));
      return new ModelWeight(searcher, modelFeaturesWeights, allFeatureWeights);
    }
    else{      
      long  start = System.currentTimeMillis();
      final Collection<Feature> modelFeatures = meta.getFeatures();
      final Collection<Feature> allFeatures = meta.getAllFeatures();
          
      int modelFeatSize = modelFeatures.size();
      int allFeatSize = this.featuresRequested ? allFeatures.size() : modelFeatSize;

      final FeatureWeight[] extractedFeatureWeights = new FeatureWeight[allFeatSize];
      final FeatureWeight[] modelFeaturesWeights = new FeatureWeight[modelFeatSize];
      createWeightsSelectively(allFeatures,modelFeatures,searcher, needsScores, extractedFeatureWeights,modelFeaturesWeights); 
      
      int modelFeatIndex = 0;
      Normalizer[] modelFeatureNorms = new Normalizer[modelFeatures.size()]; // store the featureNorms for modelFeatures to use later for normalization
      for (final Feature modelFeature: modelFeatures){
        modelFeatureNorms[modelFeatIndex++]= modelFeature.getNorm();
      }
      
      long  end = System.currentTimeMillis();
      log.info("In ModelQuery.java::createWeight() NEW!  createWeight time:{} ms", (end-start));
      
      return new ModelWeight(searcher, modelFeaturesWeights, extractedFeatureWeights, modelFeatureNorms);
    }
  }
  
  private void createWeightsSelectively(Collection<Feature> allFeatures, 
      Collection<Feature> modelFeatures,
      IndexSearcher searcher, boolean needsScores, FeatureWeight[] allFeatureWeights,
      FeatureWeight[] modelFeaturesWeights) throws IOException {
    long  start = System.currentTimeMillis();
    int i = 0, j = 0;
    final SolrQueryRequest req = getRequest();
    // since the feature store is a linkedhashmap order is preserved
    if (this.featuresRequested) {
      final HashSet<Feature> modelFeatSet = new HashSet<Feature>(modelFeatures);
      for (final Feature f : allFeatures) {
        try{
        FeatureWeight fw = f.createWeight(searcher, needsScores, req, originalQuery, efi);
        allFeatureWeights[i++] = fw;
        if (modelFeatSet.contains(f)){  
          // Note: by assigning fw created using allFeatures, we lose the normalizer associated with the modelFeature, for this reason, 
          // we store it in the modelFeatureNorms, ahead of time, to use in normalize() and explain()
          modelFeaturesWeights[j++] = fw; 
       }
       }catch (final Exception e) {
          throw new FeatureException("Exception from createWeight for " + f.toString() + " "
              + e.getMessage(), e);
        }
      }
    }
    else{
      for (final Feature f : modelFeatures){
        try {
        FeatureWeight fw = f.createWeight(searcher, needsScores, req, originalQuery, efi);
        allFeatureWeights[i++] = fw;
        modelFeaturesWeights[j++] = fw; 
        }catch (final Exception e) {
          throw new FeatureException("Exception from createWeight for " + f.toString() + " "
              + e.getMessage(), e);
        }
      }
    }
    long  end = System.currentTimeMillis();
    log.info("\tIn ModelQuery.java::createWeightsSelectively()  createWeights time:{} ms totalModelFeatsFound = {} total allFeatureWeights: {}", (end-start), j, i);
  }
  
  private FeatureWeight[] createWeights(Collection<Feature> features,
      IndexSearcher searcher, boolean needsScores) throws IOException {
    final FeatureWeight[] arr = new FeatureWeight[features.size()];
    int i = 0;
    final SolrQueryRequest req = getRequest();
    // since the feature store is a linkedhashmap order is preserved
    for (final Feature f : features) {
      try {
        final FeatureWeight fw = f.createWeight(searcher, needsScores, req, originalQuery, efi);

        arr[i] = fw;
        ++i;
      } catch (final Exception e) {
        throw new FeatureException("Exception from createWeight for " + f.toString() + " "
            + e.getMessage(), e);
      }
    }
    return arr;
  }

  @Override
  public String toString(String field) {
    return field;
  }

  public class FeatureInfo {
      String name;
      float value;
      boolean used;
      
      FeatureInfo(String n, float v, boolean u){
        name = n; value = v; used = u;
      }
       
      public boolean getUsed(){
        return used;
      }
      
      public void setUsed(boolean u){
        used = u;
      }
      
      public void setScoreUsed(float score, boolean used){
        this.value = score;
        this.used = used;
      }
      
      public String getName(){
        return name;
      }
      
      public float getValue(){
        return value;
      }
  }
  
  public class ModelWeight extends Weight {

    IndexSearcher searcher;

    // List of the model's features used for scoring. This is a subset of the
    // features used for logging.
    FeatureWeight[] modelFeatureWeights;
    float[] modelFeatureValuesNormalized;
    FeatureWeight[] extractedFeatureWeights;
    Normalizer[] modelFeatureNorms;

    // List of all the feature names, values, used for both scoring and logging
    LinkedHashMap<Integer, FeatureInfo> featuresInfo;

    public ModelWeight(IndexSearcher searcher, FeatureWeight[] modelFeatureWeights,
        FeatureWeight[] extractedFeatureWeights) {
      this(searcher, modelFeatureWeights, extractedFeatureWeights, null);   
    }
    
    public ModelWeight(IndexSearcher searcher, FeatureWeight[] modelFeatureWeights,
        FeatureWeight[] extractedFeatureWeights, Normalizer[] modelFeatNorms) {
      super(ModelQuery.this);
      this.searcher = searcher;
      this.extractedFeatureWeights = extractedFeatureWeights;
      this.modelFeatureWeights = modelFeatureWeights;
      modelFeatureValuesNormalized = new float[modelFeatureWeights.length];
      featuresInfo = new LinkedHashMap<Integer, FeatureInfo>(extractedFeatureWeights.length);
 
      if (modelFeatNorms == null){
        modelFeatureNorms = new Normalizer[modelFeatureWeights.length];
        int pos = 0;
        for (final FeatureWeight feature : modelFeatureWeights){
          modelFeatureNorms[pos] = feature.getNorm();
        }
      }
      else{
         this.modelFeatureNorms = modelFeatNorms;
      }

      for (int i = 0; i < extractedFeatureWeights.length; ++i) {
        featuresInfo.put(extractedFeatureWeights[i].getId(), new FeatureInfo(extractedFeatureWeights[i].getName(), 0, false));
      }
    }

    /**
     * Goes through all the stored feature values, and calculates the normalized
     * values for all the features that will be used for scoring.
     */
    public void normalize() {
      int pos = 0;
      for (final FeatureWeight feature : modelFeatureWeights) {
        final int featureId = feature.getId();
        FeatureInfo fInfo = featuresInfo.get(featureId);
        if (fInfo.getUsed()) {
          final Normalizer norm = this.modelFeatureNorms[pos];
          modelFeatureValuesNormalized[pos] = norm
              .normalize(fInfo.getValue());
        } else {
          modelFeatureValuesNormalized[pos] = feature.getDefaultValue();
        }
        pos++;
      }
    }

    @Override
    public Explanation explain(LeafReaderContext context, int doc)
        throws IOException {
      // FIXME: This explain doens't skip null scorers like the scorer()
      // function
     // final Explanation[] explanations = new Explanation[extractedFeatureWeights.length];
    //  for (final FeatureWeight feature : extractedFeatureWeights) {
    //    explanations[feature.getId()] = feature.explain(context, doc);
    //  }

      final List<Explanation> featureExplanations = new ArrayList<>();
      int pos = 0;
      for (final FeatureWeight f : modelFeatureWeights) {
        final Normalizer n = modelFeatureNorms[pos++];
        Explanation e = f.explain(context, doc); //explanations[f.getId()];
        if (n != IdentityNormalizer.INSTANCE) {
          e = n.explain(e);
        }
        featureExplanations.add(e);
      }
      // TODO this calls twice the scorers, could be optimized.
      final ModelScorer bs = scorer(context);
      bs.iterator().advance(doc);

      final float finalScore = bs.score();

      return meta.explain(context, doc, finalScore, featureExplanations);

    }

    @Override
    public void extractTerms(Set<Term> terms) {
      for (final FeatureWeight feature : extractedFeatureWeights) {
        feature.extractTerms(terms);
      }
    }

    protected void reset() {
      for (FeatureInfo featInfo:featuresInfo.values()) {
        featInfo.setUsed(false);
      }
    }

    @Override
    public ModelScorer scorer(LeafReaderContext context) throws IOException {
      final List<FeatureScorer> featureScorers = new ArrayList<FeatureScorer>(
          extractedFeatureWeights.length);
      for (final FeatureWeight featureWeight : extractedFeatureWeights) {
        final FeatureScorer scorer = featureWeight.scorer(context);
        if (scorer != null) {
          featureScorers.add(featureWeight.scorer(context));
        }
      }

      // Always return a ModelScorer, even if no features match, because we
      // always need to call
      // score on the model for every document, since 0 features matching could
      // return a
      // non 0 score for a given model.
      return new ModelScorer(this, featureScorers);
    }

    public class ModelScorer extends Scorer {
      protected HashMap<String,Object> docInfo;
      protected Scorer featureTraversalScorer;

      public ModelScorer(Weight weight, List<FeatureScorer> featureScorers) {
        super(weight);
        docInfo = new HashMap<String,Object>();
        for (final FeatureScorer subSocer : featureScorers) {
          subSocer.setDocInfo(docInfo);
        }

        if (featureScorers.size() <= 1) { // TODO: Allow the use of dense
          // features in other cases
          featureTraversalScorer = new DenseModelScorer(weight, featureScorers);
        } else {
          featureTraversalScorer = new SparseModelScorer(weight, featureScorers);
        }
      }

      @Override
      public Collection<ChildScorer> getChildren() {
        return featureTraversalScorer.getChildren();
      }

      public void setDocInfoParam(String key, Object value) {
        docInfo.put(key, value);
      }

      @Override
      public int docID() {
        return featureTraversalScorer.docID();
      }

      @Override
      public float score() throws IOException {
        return featureTraversalScorer.score();
      }

      @Override
      public int freq() throws IOException {
        return featureTraversalScorer.freq();
      }

      @Override
      public DocIdSetIterator iterator() {
        return featureTraversalScorer.iterator();
      }

      public class SparseModelScorer extends Scorer {
        protected DisiPriorityQueue subScorers;
        protected ModelQuerySparseIterator itr;

        protected int targetDoc = -1;
        protected int activeDoc = -1;

        protected SparseModelScorer(Weight weight,
            List<FeatureScorer> featureScorers) {
          super(weight);
          if (featureScorers.size() <= 1) {
            throw new IllegalArgumentException(
                "There must be at least 2 subScorers");
          }
          subScorers = new DisiPriorityQueue(featureScorers.size());
          for (final Scorer scorer : featureScorers) {
            final DisiWrapper w = new DisiWrapper(scorer);
            subScorers.add(w);
          }

          itr = new ModelQuerySparseIterator(subScorers);
        }

        @Override
        public int docID() {
          return itr.docID();
        }

        @Override
        public float score() throws IOException {
          final DisiWrapper topList = subScorers.topList();
          // If target doc we wanted to advance to matches the actual doc
          // the underlying features advanced to, perform the feature
          // calculations,
          // otherwise just continue with the model's scoring process with empty
          // features.
          reset();
          if (activeDoc == targetDoc) {
            for (DisiWrapper w = topList; w != null; w = w.next) {
              final Scorer subScorer = w.scorer;
              final int featureId = ((FeatureWeight) subScorer.getWeight())
                  .getId();
              featuresInfo.get(featureId).setScoreUsed(subScorer.score(), true);
            }
          }
          normalize();
          return meta.score(modelFeatureValuesNormalized);
        }

        @Override
        public int freq() throws IOException {
          final DisiWrapper subMatches = subScorers.topList();
          int freq = 1;
          for (DisiWrapper w = subMatches.next; w != null; w = w.next) {
            freq += 1;
          }
          return freq;
        }

        @Override
        public DocIdSetIterator iterator() {
          return itr;
        }

        @Override
        public final Collection<ChildScorer> getChildren() {
          final ArrayList<ChildScorer> children = new ArrayList<>();
          for (final DisiWrapper scorer : subScorers) {
            children.add(new ChildScorer(scorer.scorer, "SHOULD"));
          }
          return children;
        }

        protected class ModelQuerySparseIterator extends
            DisjunctionDISIApproximation {

          public ModelQuerySparseIterator(DisiPriorityQueue subIterators) {
            super(subIterators);
          }

          @Override
          public final int nextDoc() throws IOException {
            if (activeDoc == targetDoc) {
              activeDoc = super.nextDoc();
            } else if (activeDoc < targetDoc) {
              activeDoc = super.advance(targetDoc + 1);
            }
            return ++targetDoc;
          }

          @Override
          public final int advance(int target) throws IOException {
            // If target doc we wanted to advance to matches the actual doc
            // the underlying features advanced to, perform the feature
            // calculations,
            // otherwise just continue with the model's scoring process with
            // empty features.
            if (activeDoc < target) {
              activeDoc = super.advance(target);
            }
            targetDoc = target;
            return targetDoc;
          }
        }

      }

      public class DenseModelScorer extends Scorer {
        int activeDoc = -1; // The doc that our scorer's are actually at
        int targetDoc = -1; // The doc we were most recently told to go to
        int freq = -1;
        List<FeatureScorer> featureScorers;

        protected DenseModelScorer(Weight weight,
            List<FeatureScorer> featureScorers) {
          super(weight);
          this.featureScorers = featureScorers;
        }

        @Override
        public int docID() {
          return targetDoc;
        }

        @Override
        public float score() throws IOException {
          reset();
          freq = 0;
          if (targetDoc == activeDoc) {
            for (final Scorer scorer : featureScorers) {
              if (scorer.docID() == activeDoc) {
                freq++;
                final int featureId = ((FeatureWeight) scorer.getWeight())
                    .getId();
                featuresInfo.get(featureId).setScoreUsed(scorer.score(), true);
              }
            }
          }
          normalize();
          return meta.score(modelFeatureValuesNormalized);
        }

        @Override
        public final Collection<ChildScorer> getChildren() {
          final ArrayList<ChildScorer> children = new ArrayList<>();
          for (final Scorer scorer : featureScorers) {
            children.add(new ChildScorer(scorer, "SHOULD"));
          }
          return children;
        }

        @Override
        public int freq() throws IOException {
          return freq;
        }

        @Override
        public DocIdSetIterator iterator() {
          return new DenseIterator();
        }

        class DenseIterator extends DocIdSetIterator {

          @Override
          public int docID() {
            return targetDoc;
          }

          @Override
          public int nextDoc() throws IOException {
            if (activeDoc <= targetDoc) {
              activeDoc = NO_MORE_DOCS;
              for (final Scorer scorer : featureScorers) {
                if (scorer.docID() != NO_MORE_DOCS) {
                  activeDoc = Math.min(activeDoc, scorer.iterator().nextDoc());
                }
              }
            }
            return ++targetDoc;
          }

          @Override
          public int advance(int target) throws IOException {
            if (activeDoc < target) {
              activeDoc = NO_MORE_DOCS;
              for (final Scorer scorer : featureScorers) {
                if (scorer.docID() != NO_MORE_DOCS) {
                  activeDoc = Math.min(activeDoc,
                      scorer.iterator().advance(target));
                }
              }
            }
            targetDoc = target;
            return target;
          }

          @Override
          public long cost() {
            long sum = 0;
            for (int i = 0; i < featureScorers.size(); i++) {
              sum += featureScorers.get(i).iterator().cost();
            }
            return sum;
          }

        }
      }
    }
  }

}
