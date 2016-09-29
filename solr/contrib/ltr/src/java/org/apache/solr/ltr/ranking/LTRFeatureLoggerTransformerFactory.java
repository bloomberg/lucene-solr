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
import java.util.List;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.search.Weight;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.ltr.feature.FeatureStore;
import org.apache.solr.ltr.feature.OriginalScoreFeature;
import org.apache.solr.ltr.log.FeatureLogger;
import org.apache.solr.ltr.log.LoggingModel;
import org.apache.solr.ltr.ranking.ModelQuery.FeatureInfo;
import org.apache.solr.ltr.ranking.ModelQuery.ModelWeight;
import org.apache.solr.ltr.ranking.ModelQuery.ModelWeight.ModelScorer;
import org.apache.solr.ltr.rest.ManagedFeatureStore;
import org.apache.solr.ltr.util.CommonLTRParams;
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
    req.getContext().put(CommonLTRParams.FV_STORE, params.get(CommonLTRParams.FV_STORE));
    req.getContext().put(CommonLTRParams.FV_FORMAT, params.get(CommonLTRParams.FV_FORMAT));

    return new FeatureTransformer(name, params, req);
  }

  class FeatureTransformer extends DocTransformer {

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
     *          Name of the field to be added in a document representing the
     *          feature vectors
     */
    public FeatureTransformer(String name, SolrParams params,
        SolrQueryRequest req) {
      this.name = name;
      this.params = params;
      this.req = req;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public void setContext(ResultContext context) {
      super.setContext(context);
      if (context == null) {
        return;
      }
      if (context.getRequest() == null) {
        return;
      }

      searcher = context.getSearcher();
      if (searcher == null) {
        throw new SolrException(
            org.apache.solr.common.SolrException.ErrorCode.BAD_REQUEST,
            "searcher is null");
      }
      leafContexts = searcher.getTopReaderContext().leaves();

      // Setup ModelQuery
      reRankModel = (ModelQuery) req.getContext().get(CommonLTRParams.MODEL);
      resultsReranked = (reRankModel != null);
      String featureStoreName = params.get(CommonLTRParams.FV_STORE);
      if (!resultsReranked || (featureStoreName != null && (!featureStoreName.equals(reRankModel.getFeatureStoreName())))) {
        // if store is set in the trasformer we should overwrite the logger

        final ManagedFeatureStore fr = (ManagedFeatureStore) req.getCore().getRestManager()
            .getManagedResource(CommonLTRParams.FEATURE_STORE_END_POINT);

        final FeatureStore store = fr.getFeatureStore(featureStoreName);
        featureStoreName = store.getName(); // if featureStoreName was null before this gets actual name

        try {
          final LoggingModel lm = new LoggingModel(featureStoreName,store.getFeatures());
          reRankModel = new ModelQuery(lm, true); // request feature weights to be created for all features

          // Local transformer efi if provided
          reRankModel.setExternalFeatureInfo( LTRQParserPlugin.extractEFIParams(params) );
          reRankModel.setOriginalQuery(context.getQuery());

        }catch (final Exception e) {
          throw new SolrException(ErrorCode.BAD_REQUEST,
              "retrieving the feature store "+featureStoreName, e);
        }
      }

      if (reRankModel.getFeatureLogger() == null){
        final String featureResponseFormat = req.getParams().get(CommonLTRParams.FV_RESPONSE_WRITER,"csv");
        final String featureFormat = req.getParams().get(CommonLTRParams.FV_FORMAT,"sparse");
        reRankModel.setFeatureLogger(FeatureLogger.getFeatureLogger(featureResponseFormat,featureFormat));
      }
      reRankModel.setRequest(req);

      featureLogger = reRankModel.getFeatureLogger();

      Weight w;
      try {
        w = reRankModel.createWeight(searcher, true, 1f);
      } catch (final IOException e) {
        throw new SolrException(ErrorCode.BAD_REQUEST, e.getMessage(), e);
      }
      if ((w == null) || !(w instanceof ModelWeight)) {
        throw new SolrException(ErrorCode.BAD_REQUEST,
            "error logging the features, model weight is null");
      }
      modelWeight = (ModelWeight) w;

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
          doc.addField(name, featureLogger.makeFeatureVector(new FeatureInfo[0]));
        } else {
          if (!resultsReranked) {
            // If results have not been reranked, the score passed in is the original query's
            // score, which some features can use instead of recalculating it
            r.setDocInfoParam(OriginalScoreFeature.ORIGINAL_DOC_SCORE, new Float(score));
          }
          r.score();
          doc.addField(name,
              featureLogger.makeFeatureVector(modelWeight.featuresInfo));
        }
      } else {
        doc.addField(name, fv);
      }

    }

  }

}
