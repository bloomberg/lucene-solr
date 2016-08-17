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

import java.lang.invoke.MethodHandles;
import java.util.Map;

import org.apache.lucene.search.Query;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.ltr.feature.LTRScoringAlgorithm;
import org.apache.solr.ltr.log.FeatureLogger;
import org.apache.solr.ltr.rest.ManagedModelStore;
import org.apache.solr.ltr.util.CommonLTRParams;
import org.apache.solr.ltr.util.LTRRerankHelper;
import org.apache.solr.ltr.util.LTRUtils;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QParserPlugin;
import org.apache.solr.search.SyntaxError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plug into solr a rerank model.
 *
 * Learning to Rank Query Parser Syntax: rq={!ltr model=6029760550880411648 reRankDocs=300
 * efi.myCompanyQueryIntent=0.98}
 *
 */
public class LTRQParserPlugin extends QParserPlugin {
  public static final String NAME = "ltr";

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Override
  public void init(@SuppressWarnings("rawtypes") NamedList args) {}

  @Override
  public QParser createParser(String qstr, SolrParams localParams,
      SolrParams params, SolrQueryRequest req) {
    return new LTRQParser(qstr, localParams, params, req);
  }

  public class LTRQParser extends QParser {

    LTRRerankHelper ltrHelper;

    public LTRQParser(String qstr, SolrParams localParams, SolrParams params,
        SolrQueryRequest req) {
      super(qstr, localParams, params, req);
      ltrHelper = new LTRRerankHelper(req, localParams);
    }
    
    private void setupLogger(ModelQuery reRankModel){
      final FeatureLogger<?> solrLogger = FeatureLogger
          .getFeatureLogger(params.get(CommonLTRParams.FV_RESPONSE_WRITER));
      reRankModel.setFeatureLogger(solrLogger);
      req.getContext().put(CommonLTRParams.LOGGER_NAME, solrLogger);
    }

    @Override
    public Query parse() throws SyntaxError {
      // ReRanking Model
      final String modelName = ltrHelper.getModelName();
      final LTRScoringAlgorithm meta = ltrHelper.getModel(modelName);
      final ModelQuery reRankModel = ltrHelper.getRerankModel(meta);

      int reRankDocs = localParams.getInt(CommonLTRParams.RERANK_DOCS,
          CommonLTRParams.DEFAULT_RERANK_DOCS);
      final int start = params.getInt(CommonParams.START,
          CommonParams.START_DEFAULT);
      final int rows = params.getInt(CommonParams.ROWS,
          CommonParams.ROWS_DEFAULT);

      // Enable the feature vector cache if we are extracting features, and the features
      // we requested are the same ones we are reranking with
      final boolean extractFeatures = ltrHelper.isLoggingFeatures();
      final String featureStoreName = ltrHelper.getStoreName();
      final boolean isUsingFeatureStoreCache = (extractFeatures &&
          (featureStoreName == null || featureStoreName.equals(reRankModel.getFeatureStoreName())));
      if (isUsingFeatureStoreCache) {
        setupLogger(reRankModel);
      }
      ltrHelper.setModelInContext(reRankModel);
      
      if ((start + rows) > reRankDocs) {
        throw new SolrException(ErrorCode.BAD_REQUEST,
            "Requesting more documents than being reranked.");
      }
      reRankDocs = Math.max(start + rows, reRankDocs);
      
      log.info("Reranking {} docs using model {}", reRankDocs, reRankModel.getMetadata().getName());
      return new LTRQuery(reRankModel, reRankDocs);
    }
  }
}
