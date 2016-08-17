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
package org.apache.solr.ltr.util;

import java.io.IOException;
import java.util.Map;

import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Weight;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.ltr.feature.LTRScoringAlgorithm;
import org.apache.solr.ltr.ranking.ModelQuery;
import org.apache.solr.ltr.ranking.ModelQuery.ModelWeight;
import org.apache.solr.ltr.rest.ManagedFeatureStore;
import org.apache.solr.ltr.rest.ManagedModelStore;
import org.apache.solr.request.SolrQueryRequest;

public class LTRRerankHelper {
  private final SolrQueryRequest request;
  private final SolrParams params;
  
  public LTRRerankHelper(SolrQueryRequest request, SolrParams params){
    this.request = request;
    this.params = params;
  }
  
  public ManagedModelStore getModelStore(){
    return (ManagedModelStore) request.getCore().getRestManager()
        .getManagedResource(CommonLTRParams.MODEL_STORE_END_POINT);
  }
  
  public ManagedFeatureStore getFeatureStore(){
    return (ManagedFeatureStore) request.getCore().getRestManager()
        .getManagedResource(CommonLTRParams.FEATURE_STORE_END_POINT);
  }
  
  
  public LTRScoringAlgorithm getModel(String modelName){
    ManagedModelStore modelStore = getModelStore();
    final LTRScoringAlgorithm rerankModel = modelStore.getModel(modelName);
    if (rerankModel == null) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
          "cannot find " + CommonLTRParams.MODEL + " " + modelName);
    }
    return rerankModel;
  }
  
  public String getDefaultFeatureStoreName(){
   return CommonLTRParams.DEFAULT_FEATURE_STORE_NAME;
  }
  
  
  public String getStoreName(){
    String store = params.get(CommonLTRParams.STORE);
    return store;
  }
  
  public String getModelName(){
    String modelName = params.get(CommonLTRParams.MODEL);
    if ((modelName == null) || modelName.isEmpty()) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
          "cannot find a model in the request");
    }
    return modelName;
  }
  
  public String getStoreNameFromContext(){
    String store = (String)request.getContext().get(CommonLTRParams.STORE);
    return store;
  }
  
  public ModelQuery getModelFromContext(){
    return (ModelQuery)request.getContext().get(CommonLTRParams.MODEL);
  }
  
  public void setModelInContext(ModelQuery reRankModel){
    request.getContext().put(CommonLTRParams.MODEL, reRankModel);
  }
  
  public boolean isLoggingFeatures(){
    Boolean log = (Boolean)request.getContext().get(CommonLTRParams.LOG_FEATURES_QUERY_PARAM);
    if (log == null) return true;
    return log;
  }
  
  public boolean hasModelInContext(){
    return request.getContext().containsKey(CommonLTRParams.MODEL);
  }
  
  public ModelQuery getRerankModel(LTRScoringAlgorithm model){
    if (model == null) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
          "invalid model");
    }
    final ModelQuery reRankModel = new ModelQuery(model);
    
    // External features
    final Map<String,String> externalFeatureInfo = LTRUtils.extractEFIParams(params);
    reRankModel.setExternalFeatureInfo(externalFeatureInfo);
    // Request
    reRankModel.setRequest(request);
    return reRankModel;
  }
  
  public ModelWeight getModelWeight(ModelQuery reRankModel, IndexSearcher searcher){
    Weight w;
    try {
      w = searcher.createNormalizedWeight(reRankModel, true);
    } catch (final IOException e) {
      throw new SolrException(ErrorCode.BAD_REQUEST, e.getMessage(), e);
    }
    if ((w == null) || !(w instanceof ModelWeight)) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
          "model weight is null");
    }
    return (ModelWeight) w;
  }
}
