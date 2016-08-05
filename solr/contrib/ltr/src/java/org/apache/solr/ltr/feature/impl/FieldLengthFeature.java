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
package org.apache.solr.ltr.feature.impl;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.SmallFloat;
import org.apache.solr.ltr.ranking.Feature;
import org.apache.solr.ltr.util.CommonLTRParams;
import org.apache.solr.ltr.util.FeatureException;
import org.apache.solr.ltr.util.NamedParams;
import org.apache.solr.request.SolrQueryRequest;

public class FieldLengthFeature extends Feature {

  private String field;

  public String getField() {
    return field;
  }

  public void setField(String field) {
    this.field = field;
  }

  @Override
  protected LinkedHashMap<String,Object> paramsToMap() {
    final LinkedHashMap<String,Object> params = new LinkedHashMap<>(1, 1.0f);
    params.put("field", field);
    return params;
  }

  /** Cache of decoded bytes. */

  private static final float[] NORM_TABLE = new float[256];

  static {
    for (int i = 0; i < 256; i++) {
      NORM_TABLE[i] = SmallFloat.byte315ToFloat((byte) i);

    }

  }

  /**
   * Decodes the norm value, assuming it is a single byte.
   *
   */

  private final float decodeNorm(long norm) {
    return NORM_TABLE[(int) (norm & 0xFF)]; // & 0xFF maps negative bytes to
    // positive above 127
  }

  public FieldLengthFeature() {

  }

  @Override
  public void init(String name, NamedParams params, int id)
      throws FeatureException {
    super.init(name, params, id);
    if (!params.containsKey(CommonLTRParams.FEATURE_FIELD_PARAM)) {
      throw new FeatureException("missing param field");
    }
    field = (String) params.get(CommonLTRParams.FEATURE_FIELD_PARAM);
  }

  @Override
  public FeatureWeight createWeight(IndexSearcher searcher, boolean needsScores, SolrQueryRequest request, Query originalQuery, Map<String,String> efi)
      throws IOException {
    return new FieldLengthFeatureWeight(searcher, request, originalQuery, efi);
  }


  public class FieldLengthFeatureWeight extends FeatureWeight {

    public FieldLengthFeatureWeight(IndexSearcher searcher, 
        SolrQueryRequest request, Query originalQuery, Map<String,String> efi) {
      super(FieldLengthFeature.this, searcher, request, originalQuery, efi);
    }

    @Override
    public FeatureScorer scorer(LeafReaderContext context) throws IOException {
      return new FieldLengthFeatureScorer(this, context);

    }

    public class FieldLengthFeatureScorer extends FeatureScorer {

      LeafReaderContext context = null;
      NumericDocValues norms = null;
      DocIdSetIterator itr;

      public FieldLengthFeatureScorer(FeatureWeight weight,
          LeafReaderContext context) throws IOException {
        super(weight);
        this.context = context;
        itr = new MatchAllIterator();
        norms = context.reader().getNormValues(field);

        // In the constructor, docId is -1, so using 0 as default lookup
        final IndexableField idxF = searcher.doc(0).getField(field);
        if (idxF.fieldType().omitNorms()) {
          throw new IOException(
              "FieldLengthFeatures can't be used if omitNorms is enabled (field="
                  + field + ")");
        }

      }

      @Override
      public float score() throws IOException {

        final long l = norms.get(itr.docID());
        final float norm = decodeNorm(l);
        final float numTerms = (float) Math.pow(1f / norm, 2);

        return numTerms;
      }

      @Override
      public int docID() {
        return itr.docID();
      }

      @Override
      public DocIdSetIterator iterator() {
        return itr;
      }

    }
  }

}
