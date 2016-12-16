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
package org.apache.solr.ltr;

import org.apache.solr.search.SolrIndexSearcher;

/**
 * FeatureLogger can be registered in a model and provide a strategy for logging
 * the feature values.
 */
public abstract class FeatureLogger {

  /** the name of the cache using for storing the feature value **/
  private static final String QUERY_FV_CACHE_NAME = "QUERY_DOC_FV";

  public enum FeatureFormat {DENSE, SPARSE};
  protected final FeatureFormat featureFormat;

  protected FeatureLogger(FeatureFormat f) {
    this.featureFormat = f;
  }

  /**
   * Log will be called every time that the model generates the feature values
   * for a document and a query.
   *
   * @param docid
   *          Solr document id whose features we are saving
   * @param featuresInfo
   *          List of all the {@link LTRScoringQuery.FeatureInfo} objects which contain name and value
   *          for all the features triggered by the result set
   * @return true if the logger successfully logged the features, false
   *         otherwise.
   */

  public boolean log(int docid, LTRScoringQuery scoringQuery,
      SolrIndexSearcher searcher, LTRScoringQuery.FeatureInfo[] featuresInfo) {
    final String featureVector = makeFeatureVector(featuresInfo);
    if (featureVector == null) {
      return false;
    }

    return searcher.cacheInsert(QUERY_FV_CACHE_NAME,
        fvCacheKey(scoringQuery, docid), featureVector) != null;
  }

  public abstract String makeFeatureVector(LTRScoringQuery.FeatureInfo[] featuresInfo);

  private static int fvCacheKey(LTRScoringQuery scoringQuery, int docid) {
    return  scoringQuery.hashCode() + (31 * docid);
  }

  /**
   * populate the document with its feature vector
   *
   * @param docid
   *          Solr document id
   * @return String representation of the list of features calculated for docid
   */

  public String getFeatureVector(int docid, LTRScoringQuery scoringQuery,
      SolrIndexSearcher searcher) {
    return (String) searcher.cacheLookup(QUERY_FV_CACHE_NAME, fvCacheKey(scoringQuery, docid));
  }

  public static class CSVFeatureLogger extends FeatureLogger {
    public static final char DEFAULT_KEY_VALUE_SEPARATOR = '=';
    public static final char DEFAULT_FEATURE_SEPARATOR = ',';
    private final char keyValueSep;
    private final char featureSep;

    public CSVFeatureLogger(FeatureFormat f) {
      super(f);
      this.keyValueSep = DEFAULT_KEY_VALUE_SEPARATOR;
      this.featureSep = DEFAULT_FEATURE_SEPARATOR;
    }

    public CSVFeatureLogger(FeatureFormat f, char keyValueSep, char featureSep) {
      super(f);
      this.keyValueSep = keyValueSep;
      this.featureSep = featureSep;
    }

    @Override
    public String makeFeatureVector(LTRScoringQuery.FeatureInfo[] featuresInfo) {
      // Allocate the buffer to a size based on the number of features instead of the 
      // default 16.  You need space for the name, value, and two separators per feature, 
      // but not all the features are expected to fire, so this is just a naive estimate. 
      StringBuilder sb = new StringBuilder(featuresInfo.length * 3);
      boolean isDense = featureFormat.equals(FeatureFormat.DENSE);
      for (LTRScoringQuery.FeatureInfo featInfo:featuresInfo) {
        if (featInfo.isUsed() || isDense){
          sb.append(featInfo.getName())
          .append(keyValueSep)
          .append(featInfo.getValue())
          .append(featureSep);
        }
      }

      final String features = (sb.length() > 0 ? 
          sb.substring(0, sb.length() - 1) : "");

      return features;
    }

  }

}
