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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FloatDocValuesField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery.Builder;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.Weight;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.LuceneTestCase.SuppressCodecs;
import org.apache.solr.ltr.feature.LTRScoringAlgorithm;
import org.apache.solr.ltr.feature.impl.ValueFeature;
import org.apache.solr.ltr.feature.norm.Normalizer;
import org.apache.solr.ltr.util.FeatureException;
import org.apache.solr.ltr.util.ModelException;
import org.junit.Test;

@SuppressCodecs("Lucene3x")
public class TestModelQuery extends LuceneTestCase {

  private IndexSearcher getSearcher(IndexReader r) {
    final IndexSearcher searcher = newSearcher(r, false, false);
    return searcher;
  }

  private static List<Feature> makeFeatures(int[] featureIds) {
    final List<Feature> features = new ArrayList<>();
    for (final int i : featureIds) {
      final ValueFeature f = new ValueFeature();
      Map<String,Object> params = new HashMap<String,Object>();
      params.put("value", i);
      try {
        f.init("f" + i, params, i);
      } catch (final FeatureException e) {
        e.printStackTrace();
      }
      features.add(f);
    }
    return features;
  }

  private static List<Feature> makeNormalizedFeatures(int[] featureIds) {
    final List<Feature> features = new ArrayList<>();
    for (final int i : featureIds) {
      final ValueFeature f = new ValueFeature();
      f.name = "f" + i;
      f.setValue(i);
      f.id = i;
      final Normalizer n = new Normalizer() {

        @Override
        public float normalize(float value) {
          return 42.42f;
        }

        @Override
        protected LinkedHashMap<String,Object> paramsToMap() {
          return null;
        }
      };
      features.add(new FilterFeature(f, n));
    }
    return features;
  }

  private static Map<String,Object> makeFeatureWeights(List<Feature> features) {
    final Map<String,Object> nameParams = new HashMap<String,Object>();
    final HashMap<String,Double> modelWeights = new HashMap<String,Double>();
    for (final Feature feat : features) {
      modelWeights.put(feat.getName(), 0.1);
    }
    if (modelWeights.isEmpty()) {
      modelWeights.put("", 0.0);
    }
    nameParams.put("weights", modelWeights);
    return nameParams;
  }

  private ModelQuery.ModelWeight performQuery(TopDocs hits,
      IndexSearcher searcher, int docid, ModelQuery model) throws IOException,
      ModelException {
    final List<LeafReaderContext> leafContexts = searcher.getTopReaderContext()
        .leaves();
    final int n = ReaderUtil.subIndex(hits.scoreDocs[0].doc, leafContexts);
    final LeafReaderContext context = leafContexts.get(n);
    final int deBasedDoc = hits.scoreDocs[0].doc - context.docBase;

    final Weight weight = searcher.createNormalizedWeight(model, true);
    final Scorer scorer = weight.scorer(context);

    // rerank using the field final-score
    scorer.iterator().advance(deBasedDoc);
    scorer.score();

    // assertEquals(42.0f, score, 0.0001);
    // assertTrue(weight instanceof AssertingWeight);
    // (AssertingIndexSearcher)
    assertTrue(weight instanceof ModelQuery.ModelWeight);
    final ModelQuery.ModelWeight modelWeight = (ModelQuery.ModelWeight) weight;
    return modelWeight;

  }

  @Test
  public void testModelQueryEquality() throws ModelException {
    final List<Feature> features = makeFeatures(new int[] {0, 1, 2});
    final List<Feature> allFeatures = makeFeatures(
        new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
    final Map<String,Object> modelParams = makeFeatureWeights(features);

    final LTRScoringAlgorithm algorithm1 = new RankSVMModel(
        "testModelName",
        features, "testStoreName", allFeatures, modelParams);

    final ModelQuery m1 = new ModelQuery(algorithm1);
    final ModelQuery m2 = new ModelQuery(algorithm1);

    assertEquals(m1, m2);
    assertEquals(m1.hashCode(), m2.hashCode());

    final HashMap<String,String> externalFeatureInfo = new HashMap<>();
    externalFeatureInfo.put("queryIntent", "company");
    m2.setExternalFeatureInfo(externalFeatureInfo);

    assertFalse(m1.equals(m2));
    assertFalse(m1.hashCode() == m2.hashCode());

    final HashMap<String,String> externalFeatureInfo2 = new HashMap<>();
    externalFeatureInfo2.put("queryIntent", "company");
    m1.setExternalFeatureInfo(externalFeatureInfo2);

    assertEquals(m1, m2);
    assertEquals(m1.hashCode(), m2.hashCode());

    final LTRScoringAlgorithm algorithm2 = new RankSVMModel(
        "testModelName2",
        features, "testStoreName", allFeatures, modelParams);
    final ModelQuery m3 = new ModelQuery(algorithm2);

    assertFalse(m1.equals(m3));
    assertFalse(m1.hashCode() == m3.hashCode());

    final LTRScoringAlgorithm algorithm3 = new RankSVMModel(
        "testModelName",
        features, "testStoreName3", allFeatures, modelParams);
    final ModelQuery m4 = new ModelQuery(algorithm3);

    assertFalse(m1.equals(m4));
    assertFalse(m1.hashCode() == m4.hashCode());
  }

  @Test
  public void testModelQuery() throws IOException, ModelException {
    final Directory dir = newDirectory();
    final RandomIndexWriter w = new RandomIndexWriter(random(), dir);

    Document doc = new Document();
    doc.add(newStringField("id", "0", Field.Store.YES));
    doc.add(newTextField("field", "wizard the the the the the oz",
        Field.Store.NO));
    doc.add(new FloatDocValuesField("final-score", 1.0f));

    w.addDocument(doc);
    doc = new Document();
    doc.add(newStringField("id", "1", Field.Store.YES));
    // 1 extra token, but wizard and oz are close;
    doc.add(newTextField("field", "wizard oz the the the the the the",
        Field.Store.NO));
    doc.add(new FloatDocValuesField("final-score", 2.0f));
    w.addDocument(doc);

    final IndexReader r = w.getReader();
    w.close();

    // Do ordinary BooleanQuery:
    final Builder bqBuilder = new Builder();
    bqBuilder.add(new TermQuery(new Term("field", "wizard")), Occur.SHOULD);
    bqBuilder.add(new TermQuery(new Term("field", "oz")), Occur.SHOULD);
    final IndexSearcher searcher = getSearcher(r);
    // first run the standard query
    final TopDocs hits = searcher.search(bqBuilder.build(), 10);
    assertEquals(2, hits.totalHits);
    assertEquals("0", searcher.doc(hits.scoreDocs[0].doc).get("id"));
    assertEquals("1", searcher.doc(hits.scoreDocs[1].doc).get("id"));

    List<Feature> features = makeFeatures(new int[] {0, 1, 2});
    final List<Feature> allFeatures = makeFeatures(new int[] {0, 1, 2, 3, 4, 5,
        6, 7, 8, 9});
    RankSVMModel meta = new RankSVMModel("test",
        features, "test", allFeatures,
        makeFeatureWeights(features));

    ModelQuery.ModelWeight modelWeight = performQuery(hits, searcher,
        hits.scoreDocs[0].doc, new ModelQuery(meta));
    assertEquals(3, modelWeight.modelFeatureValuesNormalized.length);
    assertEquals(10, modelWeight.allFeatureValues.length);

    for (int i = 0; i < 3; i++) {
      assertEquals(i, modelWeight.modelFeatureValuesNormalized[i], 0.0001);
    }
    for (int i = 0; i < 10; i++) {
      assertEquals(i, modelWeight.allFeatureValues[i], 0.0001);
    }

    for (int i = 0; i < 10; i++) {
      assertEquals("f" + i, modelWeight.allFeatureNames[i]);

    }

    final int[] mixPositions = new int[] {8, 2, 4, 9, 0};
    features = makeFeatures(mixPositions);
    meta = new RankSVMModel("test",
        features, "test", allFeatures, makeFeatureWeights(features));

    modelWeight = performQuery(hits, searcher, hits.scoreDocs[0].doc,
        new ModelQuery(meta));
    assertEquals(mixPositions.length,
        modelWeight.modelFeatureValuesNormalized.length);

    for (int i = 0; i < mixPositions.length; i++) {
      assertEquals(mixPositions[i],
          modelWeight.modelFeatureValuesNormalized[i], 0.0001);
    }
    for (int i = 0; i < 10; i++) {
      assertEquals(i, modelWeight.allFeatureValues[i], 0.0001);
    }

    final int[] noPositions = new int[] {};
    features = makeFeatures(noPositions);
    meta = new RankSVMModel("test",
        features, "test", allFeatures, makeFeatureWeights(features));

    modelWeight = performQuery(hits, searcher, hits.scoreDocs[0].doc,
        new ModelQuery(meta));
    assertEquals(0, modelWeight.modelFeatureValuesNormalized.length);

    // test normalizers
    features = makeNormalizedFeatures(mixPositions);
    final RankSVMModel normMeta = new RankSVMModel("test",
        features, "test", allFeatures,
        makeFeatureWeights(features));

    modelWeight = performQuery(hits, searcher, hits.scoreDocs[0].doc,
        new ModelQuery(normMeta));
    assertEquals(mixPositions.length,
        modelWeight.modelFeatureValuesNormalized.length);
    for (int i = 0; i < mixPositions.length; i++) {
      assertEquals(42.42f, modelWeight.modelFeatureValuesNormalized[i], 0.0001);
    }
    for (int i = 0; i < 10; i++) {
      assertEquals(i, modelWeight.allFeatureValues[i], 0.0001);
    }
    r.close();
    dir.close();

  }

}
