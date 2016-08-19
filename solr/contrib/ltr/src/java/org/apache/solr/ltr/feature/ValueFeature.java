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
package org.apache.solr.ltr.feature;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.solr.ltr.ranking.Feature;
import org.apache.solr.request.SolrQueryRequest;

public class ValueFeature extends Feature {
  /** name of the attribute containing the value of this feature **/
  private static final String VALUE_FIELD = "value";
  private static final String REQUIRED_PARAM = "required";

  private float configValue = -1f;
  private String configValueStr = null;

  private Object value = null;
  private Boolean required = null;

  public Object getValue() {
    return value;
  }

  public void setValue(Object value) {
    this.value = value;
    if (value instanceof String) {
      configValueStr = (String) value;
      if (configValueStr.trim().isEmpty()) {
        throw new FeatureException("Empty field 'value' in params for " + this);
      }
    } else if (value instanceof Double) {
      configValue = ((Double) value).floatValue();
    } else if (value instanceof Float) {
      configValue = ((Float) value).floatValue();
    } else if (value instanceof Integer) {
      configValue = ((Integer) value).floatValue();
    } else if (value instanceof Long) {
      configValue = ((Long) value).floatValue();
    } else {
      throw new FeatureException("Invalid type for 'value' in params for " + this);
    }
  }

  public boolean isRequired() {
    return Boolean.TRUE.equals(required);
  }

  public void setRequired(boolean required) {
    this.required = required;
  }

  @Override
  public LinkedHashMap<String,Object> paramsToMap() {
    final LinkedHashMap<String,Object> params = new LinkedHashMap<>(2, 1.0f);
    params.put("value", value);
    if (required != null) {
      params.put("required", required);
    }
    return params;
  }

  public ValueFeature(String name, Map<String,Object> params) {
    super(name, params);
  }

  @Override
  public FeatureWeight createFeatureWeight(IndexSearcher searcher, boolean needsScores, 
      SolrQueryRequest request, Query originalQuery, Map<String,String[]> efi)
      throws IOException {
    return new ValueFeatureWeight(searcher, request, originalQuery, efi);
  }

  public class ValueFeatureWeight extends FeatureWeight {

    final protected Float featureValue;

    public ValueFeatureWeight(IndexSearcher searcher, 
        SolrQueryRequest request, Query originalQuery, Map<String,String[]> efi) {
      super(ValueFeature.this, searcher, request, originalQuery, efi);
      if (configValueStr != null) {
        final String expandedValue = macroExpander.expand(configValueStr);
        if (expandedValue != null) {
          featureValue = Float.parseFloat(expandedValue);
        } else if (isRequired()) {
          throw new FeatureException(this.getClass().getSimpleName() + " requires efi parameter that was not passed in request.");
        } else {
          featureValue=null;
        }
      } else {
        featureValue = configValue;
      }
    }

    @Override
    public FeatureScorer scorer(LeafReaderContext context) throws IOException {
      if(featureValue!=null)
        return new ValueFeatureScorer(this, featureValue);
      else
        return null;
    }
    

    


  }

}
