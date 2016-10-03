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
package org.apache.solr.response.transform;

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
import org.apache.solr.ltr.model.LoggingModel;
import org.apache.solr.ltr.ranking.ModelQuery;
import org.apache.solr.ltr.ranking.ModelQuery.FeatureInfo;
import org.apache.solr.ltr.ranking.ModelQuery.ModelWeight;
import org.apache.solr.ltr.ranking.ModelQuery.ModelWeight.ModelScorer;
import org.apache.solr.ltr.rest.ManagedFeatureStore;
import org.apache.solr.ltr.util.CommonLTRParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.ResultContext;
import org.apache.solr.response.transform.DocTransformer;
import org.apache.solr.response.transform.TransformerFactory;
import org.apache.solr.search.LTRQParserPlugin;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.SolrPluginUtils;

/**
 * This transformer will take care to generate and append in the response the
 * features declared in the feature store of the current model. The class is
 * useful if you are not interested in the reranking (e.g., bootstrapping a
 * machine learning framework).
 */
public class LTRFeatureLoggerTransformerFactory extends TransformerFactory {

  // used inside fl to specify the output format (csv/json) of the extracted features
  public static final String FV_RESPONSE_WRITER = "fvwt";

  // used inside fl to specify the format (dense|sparse) of the extracted features
  public static final String FV_FORMAT = "format";

  // used inside fl to specify the feature store to use for the feature extraction
  public static final String FV_STORE = "store";

  public static FeatureLogger<?> getFeatureLogger(SolrQueryRequest req) {
    final String stringFormat = (String) req.getContext().get(FV_RESPONSE_WRITER);
    final String featureFormat = (String) req.getContext().get(FV_FORMAT);
    return FeatureLogger.getFeatureLogger(stringFormat, featureFormat);
  }

  public static String DEFAULT_LOGGING_MODEL_NAME = "logging-model";

  private String loggingModelName = DEFAULT_LOGGING_MODEL_NAME;

  public void setLoggingModelName(String loggingModelName) {
    this.loggingModelName = loggingModelName;
  }

  @Override
  public void init(@SuppressWarnings("rawtypes") NamedList args) {
    super.init(args);
    SolrPluginUtils.invokeSetters(this, args);
  }

  @Override
  public DocTransformer create(String name, SolrParams params,
      SolrQueryRequest req) {

    // Hint to enable feature vector cache since we are requesting features
    req.getContext().put(CommonLTRParams.LOG_FEATURES_QUERY_PARAM, true);
    req.getContext().put(FV_STORE, params.get(FV_STORE));
    req.getContext().put(FV_FORMAT, params.get(FV_FORMAT));
    req.getContext().put(FV_RESPONSE_WRITER, params.get(FV_RESPONSE_WRITER));

    return new FeatureTransformer(name, params, req);
  }

  class FeatureTransformer extends DocTransformer {

    final private String name;
    final private SolrParams params;
    final private SolrQueryRequest req;

    private List<LeafReaderContext> leafContexts;
    private SolrIndexSearcher searcher;
    private ModelQuery reRankModel;
    private ModelWeight modelWeight;
    private FeatureLogger<?> featureLogger;
    private boolean resultsReranked;

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
      String featureStoreName = (String)req.getContext().get(FV_STORE);
      if (!resultsReranked || (featureStoreName != null && (!featureStoreName.equals(reRankModel.getScoringModel().getFeatureStoreName())))) {
        // if store is set in the trasformer we should overwrite the logger

        final ManagedFeatureStore fr = (ManagedFeatureStore) req.getCore().getRestManager()
            .getManagedResource(ManagedFeatureStore.REST_END_POINT);

        final FeatureStore store = fr.getFeatureStore(featureStoreName);
        featureStoreName = store.getName(); // if featureStoreName was null before this gets actual name

        try {
          final LoggingModel lm = new LoggingModel(loggingModelName,
              featureStoreName, store.getFeatures());

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
        reRankModel.setFeatureLogger( getFeatureLogger(req) );
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
            r.getDocInfo().setOriginalDocScore(new Float(score));
          }
          r.score();
          doc.addField(name,
              featureLogger.makeFeatureVector(modelWeight.getFeaturesInfo()));
        }
      } else {
        doc.addField(name, fv);
      }

    }

  }

}
