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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.apache.solr.ltr.log.FeatureLogger;
import org.apache.solr.ltr.ranking.Feature.FeatureWeight;
import org.apache.solr.ltr.ranking.Feature.FeatureWeight.FeatureScorer;
import org.apache.solr.ltr.util.FeatureException;
import org.apache.solr.request.SolrQueryRequest;

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

  public ModelQuery(LTRScoringAlgorithm meta) {
    this.meta = meta;
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
    final FeatureWeight[] allFeatureWeights = createWeights(meta.getAllFeatures(),
        searcher, needsScores);
    final FeatureWeight[] modelFeaturesWeights = createWeights(meta.getFeatures(),
        searcher, needsScores);

    return new ModelWeight(searcher, modelFeaturesWeights, allFeatureWeights);
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

  public class ModelWeight extends Weight {

    IndexSearcher searcher;

    // List of the model's features used for scoring. This is a subset of the
    // features used for logging.
    FeatureWeight[] modelFeatures;
    float[] modelFeatureValuesNormalized;

    // List of all the feature values, used for both scoring and logging
    FeatureWeight[] allFeatureWeights;
    float[] allFeatureValues;
    String[] allFeatureNames;
    boolean[] allFeaturesUsed;

    public ModelWeight(IndexSearcher searcher, FeatureWeight[] modelFeatures,
        FeatureWeight[] allFeatures) {
      super(ModelQuery.this);
      this.searcher = searcher;
      allFeatureWeights = allFeatures;
      this.modelFeatures = modelFeatures;
      modelFeatureValuesNormalized = new float[modelFeatures.length];
      allFeatureValues = new float[allFeatures.length];
      allFeatureNames = new String[allFeatures.length];
      allFeaturesUsed = new boolean[allFeatures.length];

      for (int i = 0; i < allFeatures.length; ++i) {
        allFeatureNames[i] = allFeatures[i].getName();
      }
    }

    /**
     * Goes through all the stored feature values, and calculates the normalized
     * values for all the features that will be used for scoring.
     */
    private void makeNormalizedFeatures() {
      int pos = 0;
      for (final FeatureWeight feature : modelFeatures) {
        final int featureId = feature.getId();
        if (allFeaturesUsed[featureId]) {
          modelFeatureValuesNormalized[pos] =
              allFeatureValues[featureId];
        } else {
          modelFeatureValuesNormalized[pos] = feature.getDefaultValue();
        }
        pos++;
      }
      meta.normalizeFeaturesInPlace(modelFeatureValuesNormalized);
    }

    @Override
    public Explanation explain(LeafReaderContext context, int doc)
        throws IOException {
      // FIXME: This explain doens't skip null scorers like the scorer()
      // function
      final Explanation[] explanations = new Explanation[allFeatureValues.length];
      int index = 0;
      for (final FeatureWeight feature : allFeatureWeights) {
        explanations[index++] = feature.explain(context, doc);
      }

      final List<Explanation> featureExplanations = new ArrayList<>();
      for (int idx = 0; idx < modelFeatures.length; ++idx) {
        final FeatureWeight f = modelFeatures[idx];
        Explanation e = explanations[f.getId()];
        e = meta.getNormalizerExplanation(e, idx);
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
      for (final FeatureWeight feature : allFeatureWeights) {
        feature.extractTerms(terms);
      }
    }

    protected void reset() {
      for (int i = 0, len = allFeaturesUsed.length; i < len; i++) {
        allFeaturesUsed[i] = false;
        allFeatureValues[i] = allFeatureWeights[i].getDefaultValue();
      }
    }

    @Override
    public ModelScorer scorer(LeafReaderContext context) throws IOException {
      final List<FeatureScorer> featureScorers = new ArrayList<FeatureScorer>(
          allFeatureWeights.length);
      for (final FeatureWeight allFeatureWeight : allFeatureWeights) {
        final FeatureScorer scorer = allFeatureWeight.scorer(context);
        if (scorer != null) {
          featureScorers.add(allFeatureWeight.scorer(context));
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
              allFeaturesUsed[featureId] = true;
              allFeatureValues[featureId] = subScorer.score();
            }
          }
          makeNormalizedFeatures();
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
                allFeaturesUsed[featureId] = true;
                allFeatureValues[featureId] = scorer.score();
              }
            }
          }
          makeNormalizedFeatures();
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
