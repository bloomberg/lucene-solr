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
import org.apache.lucene.util.LuceneTestCase.SuppressCodecs;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.ltr.TestRerankBase;
import org.apache.solr.ltr.feature.Feature;
import org.apache.solr.ltr.feature.ValueFeature;
import org.apache.solr.ltr.model.ModelException;
import org.apache.solr.ltr.model.RankSVMModel;
import org.apache.solr.ltr.norm.IdentityNormalizer;
import org.apache.solr.ltr.norm.Normalizer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

@SuppressCodecs({"Lucene3x", "Lucene41", "Lucene40", "Appending"})
public class TestSelectiveWeightCreation extends TestRerankBase {
  final private static SolrResourceLoader solrResourceLoader = new SolrResourceLoader();
  private IndexSearcher getSearcher(IndexReader r) {
    final IndexSearcher searcher = newSearcher(r, false, false);
    return searcher;
  }

  private static List<Feature> makeFeatures(int[] featureIds) {
    final List<Feature> features = new ArrayList<>();
    for (final int i : featureIds) {
      Map<String,Object> params = new HashMap<String,Object>();
      params.put("value", i);
      final Feature f = Feature.getInstance(solrResourceLoader,
          ValueFeature.class.getCanonicalName(),
          "f" + i, params);
      f.setIndex(i);
      features.add(f);
    }
    return features;
  }

  private static Map<String,Object> makeFeatureWeights(List<Feature> features) {
    final Map<String,Object> nameParams = new HashMap<String,Object>();
    final HashMap<String,Double> modelWeights = new HashMap<String,Double>();
    for (final Feature feat : features) {
      modelWeights.put(feat.getName(), 0.1);
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
    assertTrue(weight instanceof ModelQuery.ModelWeight);
    final ModelQuery.ModelWeight modelWeight = (ModelQuery.ModelWeight) weight;
    return modelWeight;

  }
  
  
  @BeforeClass
  public static void before() throws Exception {
    setuptest("solrconfig-ltr.xml", "schema-ltr.xml");
    
    assertU(adoc("id", "1", "title", "w1 w3", "description", "w1", "popularity",
        "1"));
    assertU(adoc("id", "2", "title", "w2", "description", "w2", "popularity",
        "2"));
    assertU(adoc("id", "3", "title", "w3", "description", "w3", "popularity",
        "3"));
    assertU(adoc("id", "4", "title", "w4 w3", "description", "w4", "popularity",
        "4"));
    assertU(adoc("id", "5", "title", "w5", "description", "w5", "popularity",
        "5"));
    assertU(commit());
    
    loadFeatures("external_features.json");
    loadModels("external_model.json");
    loadModels("external_model_store.json");
  }

  @AfterClass
  public static void after() throws Exception {
    aftertest();
  }
 
  @Test
  public void testModelQueryWeightCreation() throws IOException, ModelException {
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
    final List<Normalizer> norms = new ArrayList<>();
    for (int k=0; k < features.size(); ++k){
        norms.add(IdentityNormalizer.INSTANCE);
    }

    // when features are NOT requested in the response, only the modelFeature weights should be created
    RankSVMModel meta1 = RankSVMModel.create("test",
        features, norms, "test", allFeatures,
        makeFeatureWeights(features));
    ModelQuery.ModelWeight modelWeight = performQuery(hits, searcher,
        hits.scoreDocs[0].doc, new ModelQuery(meta1, false)); // features not requested in response
    
    assertEquals(features.size(), modelWeight.modelFeatureValuesNormalized.length);
    int validFeatures = 0;
    for (int i=0; i < modelWeight.featuresInfo.length; ++i){
      if (modelWeight.featuresInfo[i] != null && modelWeight.featuresInfo[i].isUsed()){
        validFeatures += 1;
      }
    }
    assertEquals(validFeatures, features.size());
    
    // when features are requested in the response, weights should be created for all features
    RankSVMModel meta2 = RankSVMModel.create("test",
        features, norms, "test", allFeatures,
        makeFeatureWeights(features));
    modelWeight = performQuery(hits, searcher,
        hits.scoreDocs[0].doc, new ModelQuery(meta2, true)); // features requested in response

    assertEquals(features.size(), modelWeight.modelFeatureValuesNormalized.length);
    assertEquals(allFeatures.size(), modelWeight.extractedFeatureWeights.length);
    
    validFeatures = 0;
    for (int i=0; i < modelWeight.featuresInfo.length; ++i){
      if (modelWeight.featuresInfo[i] != null && modelWeight.featuresInfo[i].isUsed()){
        validFeatures += 1;
      }
    }
    assertEquals(validFeatures, allFeatures.size());
    
    assertU(delI("0"));assertU(delI("1"));
    r.close();
    dir.close();
  }
  
 
  @Test
  public void testSelectiveWeightsRequestFeaturesFromDifferentStore() throws Exception {
    
    final SolrQuery query = new SolrQuery();
    query.setQuery("*:*");
    query.add("fl", "*,score");
    query.add("rows", "4");
  
    query.add("rq", "{!ltr reRankDocs=4 model=externalmodel efi.user_query=w3}");
    query.add("fl", "fv:[fv]");
    System.out.println(restTestHarness.query("/query" + query.toQueryString()));
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='1'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='3'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='4'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/fv=='matchedTitle:1.0;titlePhraseMatch:0.40254828'"); // extract all features in default store
    
    query.remove("fl");
    query.remove("rq");
    query.add("fl", "*,score");
    query.add("rq", "{!ltr reRankDocs=4 model=externalmodel efi.user_query=w3}");
    query.add("fl", "fv:[fv store=fstore4 efi.myPop=3]");
    System.out.println(restTestHarness.query("/query" + query.toQueryString()));
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='1'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/score==0.999");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/fv=='popularity:3.0;originalScore:1.0'"); // extract all features from fstore4
    
   
    query.remove("fl");
    query.remove("rq");
    query.add("fl", "*,score");
    query.add("rq", "{!ltr reRankDocs=4 model=externalmodelstore efi.user_query=w3 efi.myconf=0.8}");
    query.add("fl", "fv:[fv store=fstore4 efi.myPop=3]");
    System.out.println(restTestHarness.query("/query" + query.toQueryString()));
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='1'"); // score using fstore2 used by externalmodelstore
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/score==0.7992");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/fv=='popularity:3.0;originalScore:1.0'"); // extract all features from fstore4
  }
  
  @Test
  public void testModelQueryParallelWeightCreationResultOrder() throws Exception {
    // check to make sure that the ordewr of results will be the same when using parallel weight creation
    final SolrQuery query = new SolrQuery();
    query.setQuery("*:*");
    query.add("fl", "*,score");
    query.add("rows", "4");
  
    query.add("rq", "{!ltr reRankDocs=4 model=externalmodel efi.user_query=w3}");
    System.out.println(restTestHarness.query("/query" + query.toQueryString()));
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='1'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='3'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='4'");
    
    
    LTRThreadModule.setThreads(10, 10);
    LTRThreadModule.initSemaphore();
    System.out.println(restTestHarness.query("/query" + query.toQueryString()));
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='1'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='3'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='4'");
    LTRThreadModule.setThreads(0, 0);
    LTRThreadModule.ltrSemaphore = null;
  }
  
  @Test
  public void testModelQueryParallelWeightCreation() throws IOException, ModelException {
    final Directory dir = newDirectory();
    final RandomIndexWriter w = new RandomIndexWriter(random(), dir);

    Document doc = new Document();
    doc.add(newStringField("id", "0", Field.Store.YES));
    doc.add(newTextField("field", "wizard of the the the the the oz",
        Field.Store.NO));
    doc.add(new FloatDocValuesField("final-score", 1.0f));
    w.addDocument(doc);
    
    doc = new Document();
    doc.add(newStringField("id", "1", Field.Store.YES));
    doc.add(newTextField("field", "wizard of the the hat the the the oz",
        Field.Store.NO));
    doc.add(new FloatDocValuesField("final-score", 2.0f));
    w.addDocument(doc);
    
    doc = new Document();
    doc.add(newStringField("id", "2", Field.Store.YES));
    // 1 extra token, but wizard and oz are close;
    doc.add(newTextField("field", "wizard oz hat the the the the the the hat",
        Field.Store.NO));
    doc.add(new FloatDocValuesField("final-score", 3.0f));
    w.addDocument(doc);

    final IndexReader r = w.getReader();
    w.close();

    // Do ordinary BooleanQuery:
    final Builder bqBuilder = new Builder();
    bqBuilder.add(new TermQuery(new Term("field", "wizard")), Occur.SHOULD);
    bqBuilder.add(new TermQuery(new Term("field", "hat")), Occur.SHOULD);
    bqBuilder.add(new TermQuery(new Term("field", "oz")), Occur.SHOULD);
    final IndexSearcher searcher = getSearcher(r);
    // first run the standard query
    TopDocs hits = searcher.search(bqBuilder.build(), 10);
    assertEquals(3, hits.totalHits);
    assertEquals("2", searcher.doc(hits.scoreDocs[0].doc).get("id"));

    List<Feature> features = makeFeatures(new int[] {0, 2, 3});
    final List<Feature> allFeatures = makeFeatures(new int[] {0, 1, 2, 3, 4, 5,
        6, 7, 8, 9});
    final List<Normalizer> norms = new ArrayList<>();
    for (int k=0; k < features.size(); ++k){
        norms.add(IdentityNormalizer.INSTANCE);
    }
    
    // setting the value of number of threads to -ve should throw an exception 
    String msg1 = null;
    try{
      LTRThreadModule.setThreads(1, -1);
    }catch(NumberFormatException nfe){
        msg1 = nfe.getMessage();
    }
    assertTrue(msg1.equals("LTRMaxQueryThreads cannot be less than 0"));
    
   // set LTRMaxThreads to 1 and LTRMaxQueryThreads to 2 and verify that an exception is thrown
    String msg2 = null;
    try{
       LTRThreadModule.setThreads(1, 2);
    }catch(NumberFormatException nfe){
        msg2 = nfe.getMessage();
    }
    assertTrue(msg2.equals("LTRMaxQueryThreads cannot be greater than LTRMaxThreads"));
    // When maxThreads is set to 1, no threading should be used but the weight creation should run serially
    LTRThreadModule.setThreads(1, 1);
    LTRThreadModule.initSemaphore();
    RankSVMModel meta1 = RankSVMModel.create("test",
        features, norms, "test", allFeatures,
        makeFeatureWeights(features));
    ModelQuery.ModelWeight modelWeight = performQuery(hits, searcher,
        hits.scoreDocs[0].doc, new ModelQuery(meta1, false)); // features not requested in response  
    assertEquals(features.size(), modelWeight.modelFeatureValuesNormalized.length);
    LTRThreadModule.setThreads(0, 0);
    LTRThreadModule.ltrSemaphore = null;
    
    r.close();
    dir.close();
  }
  
}

