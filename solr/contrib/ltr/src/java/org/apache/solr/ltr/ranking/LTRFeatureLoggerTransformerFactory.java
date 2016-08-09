package org.apache.solr.ltr.ranking;

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

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.search.Weight;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.ltr.feature.FeatureStore;
import org.apache.solr.ltr.log.FeatureLogger;
import org.apache.solr.ltr.log.LoggingModel;
import org.apache.solr.ltr.ranking.ModelQuery.ModelWeight;
import org.apache.solr.ltr.ranking.ModelQuery.ModelWeight.ModelScorer;
import org.apache.solr.ltr.rest.ManagedFeatureStore;
import org.apache.solr.ltr.util.CommonLTRParams;
import org.apache.solr.ltr.util.LTRRerankHelper;
import org.apache.solr.ltr.util.LTRUtils;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.ResultContext;
import org.apache.solr.response.transform.DocTransformer;
import org.apache.solr.response.transform.TransformerFactory;
import org.apache.solr.search.SolrIndexSearcher;

/**
 * This transformer will take care to generate and append in the response the
 * features declared in the feature store of the current model. The class is
 * useful if you are not interested in the reranking (e.g., bootstrapping a
 * machine learning framework).
 */
public class LTRFeatureLoggerTransformerFactory extends TransformerFactory {

  @Override
  public void init(@SuppressWarnings("rawtypes") NamedList args) {
    super.init(args);
  }

  @Override
  public DocTransformer create(String name, SolrParams params,
      SolrQueryRequest req) {

    // Hint to enable feature vector cache since we are requesting features
    req.getContext().put(CommonLTRParams.LOG_FEATURES_QUERY_PARAM, true);
    req.getContext().put(CommonLTRParams.STORE, params.get(CommonLTRParams.STORE));

    return new FeatureTransformer(name, params, req);
  }

  class FeatureTransformer extends DocTransformer {

    LTRRerankHelper ltrHelper;
    String name;
    SolrParams params;
    SolrQueryRequest req;

    List<LeafReaderContext> leafContexts;
    SolrIndexSearcher searcher;
    ModelQuery reRankModel;
    ModelWeight modelWeight;
    FeatureLogger<?> featureLogger;
    boolean resultsReranked;

    /**
     * @param name
     *          Name of the field to be added in a document representing the feature vectors
     */
    public FeatureTransformer(String name, SolrParams params,
        SolrQueryRequest req) {
      this.name = name;
      this.params = params;
      this.req = req;
      this.ltrHelper = new LTRRerankHelper(req, params);
    }

    @Override
    public String getName() {
      return name;
    }

    private boolean setSearcher(ResultContext context) {
      if (context == null) {
        return false;
      }
      if (context.getSearcher() == null) {
        return false;
      }
      searcher = context.getSearcher();
      return true;
    }

    private void setupRerankModel(String featureStoreName) {
      if (featureStoreName == null) {
        featureStoreName = ltrHelper.getDefaultFeatureStoreName();
      }

      final ManagedFeatureStore fr = ltrHelper.getFeatureStore();
      FeatureStore store = null;
      try{
        store = fr.getFeatureStore(featureStoreName);
      }catch (final Exception e) {
        throw new SolrException(ErrorCode.BAD_REQUEST,
            "retrieving the feature store "+featureStoreName, e);
      }
      final LoggingModel loggingModel = new LoggingModel(featureStoreName, store.getFeatures());
      reRankModel = ltrHelper.getRerankModel(loggingModel);
      reRankModel.setOriginalQuery(context.getQuery());
  }

    @Override
    public void setContext(ResultContext context) {
      super.setContext(context);
      if (! setSearcher(context)) {
        throw new SolrException(
            org.apache.solr.common.SolrException.ErrorCode.BAD_REQUEST,
            "searcher is null");
      }
      leafContexts = searcher.getTopReaderContext().leaves();

      // Setup ModelQuery
      
      reRankModel = ltrHelper.getModelFromContext();
      resultsReranked = ltrHelper.hasModelInContext();
      String featureStoreName = ltrHelper.getStoreName();
      if (!resultsReranked || (featureStoreName != null && (!featureStoreName.equals(reRankModel.getFeatureStoreName())))) {
        // if store is set in the trasformer we should overwrite the logger
        setupRerankModel(featureStoreName);
      }
      if (reRankModel.getFeatureLogger() == null){
        final String featureResponseFormat = req.getParams().get(CommonLTRParams.FV_RESPONSE_WRITER,"csv");
        reRankModel.setFeatureLogger(FeatureLogger.getFeatureLogger(featureResponseFormat));
      }
      reRankModel.setRequest(req);
      featureLogger = reRankModel.getFeatureLogger();
      modelWeight = ltrHelper.getModelWeight(reRankModel, searcher);
    }

    @Override
    public void transform(SolrDocument doc, int docid, float score)
        throws IOException {
      final Object fv = featureLogger.getFeatureVector(docid, reRankModel,
          searcher);
      if (fv == null) { // FV for this document was not in the cache
        final int n = ReaderUtil.subIndex(docid, leafContexts);
        final LeafReaderContext atomicContext = leafContexts.get(n);
        final int deBasedDoc = docid - atomicContext.docBase;
        final ModelScorer r = modelWeight.scorer(atomicContext);
        if (((r == null) || (r.iterator().advance(deBasedDoc) != docid))
            && (fv == null)) {
          doc.addField(name, featureLogger.makeFeatureVector(new String[0],
              new float[0], new boolean[0]));
        } else {
          if (!resultsReranked) {
            // If results have not been reranked, the score passed in is the original query's
            // score, which some features can use instead of recalculating it
            r.setDocInfoParam(CommonLTRParams.ORIGINAL_DOC_SCORE, new Float(score));
          }
          r.score();
          final String[] names = modelWeight.allFeatureNames;
          final float[] values = modelWeight.allFeatureValues;
          final boolean[] valuesUsed = modelWeight.allFeaturesUsed;
          doc.addField(name,
              featureLogger.makeFeatureVector(names, values, valuesUsed));
        }
      } else {
        doc.addField(name, fv);
      }

    }

  }

}
