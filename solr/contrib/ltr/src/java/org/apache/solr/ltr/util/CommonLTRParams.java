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

public class CommonLTRParams {

  /** name of the attribute containing the feature name **/
  public static final String FEATURE_NAME = "name";
  /** name of the attribute containing the normalizer **/
  public static final String FEATURE_NORM = "norm";
  /** name of the attribute containing the model name **/
  public static final Object MODEL_NAME = "name";
  /** name of the attribute containing the model type **/
  public static final Object MODEL_CLASS = "class";
  /** name of the attribute containing the feature store used **/
  public static final Object MODEL_FEATURE_STORE = "store";
  /** name of the attribute containing the model params **/
  public static final Object MODEL_PARAMS = "params";
  /** name of the attribute containing the features used by the mode **/
  public static final Object MODEL_FEATURE_LIST = "features";


  /**
   * Managed feature store: the name of the attribute containing all the
   * features of a feature store
   **/
  public static final String FEATURES_JSON_FIELD = "features";

  /**
   * Managed feature store: the name of the attribute containing all the feature
   * stores
   **/
  public static final String FEATURE_STORE_JSON_FIELD = "featureStores";
  /**
   * Managed model store: the name of the attribute containing all the models of
   * a model store
   **/
  public static final String MODELS_JSON_FIELD = "models";
  /**
   * Managed model store: the name of the attribute containing all the model
   * stores
   **/
  public static final String MODEL_STORE_JSON_FIELD = "modelStores";

  /** the name of the cache using for storing the feature value **/
  public static final String QUERY_FV_CACHE_NAME = "QUERY_DOC_FV";

  /** query parser plugin: the name of the attribute for setting the model **/
  public static final String MODEL = "model";

  /**
   * query parser plugin: the param that will select the format of the features
   * in output (csv/json)
   **/
  public static final String FV_RESPONSE_WRITER = "fvwt";
  /**
   * if the log feature query param is off features will not be logged.
   **/
  public static final String LOG_FEATURES_QUERY_PARAM = "fvCache";
  /**
   * query parser plugin:the param that will select how the number of document
   * to rerank
   **/
  public static final String RERANK_DOCS = "reRankDocs";
  /** query parser plugin: default number of documents to rerank **/
  public static final int DEFAULT_RERANK_DOCS = 200;
  /** name of the feature store **/
  public static final String FEATURE_STORE_NAME = "feature-store";
  /** name of the model store **/
  public static final String MODEL_STORE_NAME = "model-store";
  /** the feature store rest endpoint **/
  public static final String FEATURE_STORE_END_POINT = "/schema/"
      + FEATURE_STORE_NAME;
  /** the model store rest endpoint **/
  public static final String MODEL_STORE_END_POINT = "/schema/"
      + MODEL_STORE_NAME;

  public static final String LOGGER_NAME = "solr-feature-logger";

  // used inside fl to specify the feature store to use for the feature extraction
  public static final String FV_STORE = "store";
  // used inside fl to specify the format (dense|sparse) of the extracted features
  public static final String FV_FORMAT = "format";

}
