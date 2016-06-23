package org.apache.solr.ltr.ranking;

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

//import static org.junit.internal.matchers.StringContains.containsString;

import java.lang.invoke.MethodHandles;

import org.apache.lucene.util.LuceneTestCase.SuppressCodecs;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.common.SolrException;
import org.apache.solr.ltr.TestRerankBase;
import org.apache.solr.ltr.util.ModelException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressCodecs({"Lucene3x", "Lucene41", "Lucene40", "Appending"})
public class TestLambdaMARTModel extends TestRerankBase {

  @SuppressWarnings("unused")
  private static final Logger logger = LoggerFactory.getLogger(MethodHandles
      .lookup().lookupClass());

  @BeforeClass
  public static void before() throws Exception {
    setuptest("solrconfig-ltr.xml", "schema-ltr.xml");

    assertU(adoc("id", "1", "title", "w1", "description", "w1 w2", "popularity",
        "100"));
    assertU(adoc("id", "2", "title", "w2", "description", "w2", "popularity",
        "50"));
    assertU(adoc("id", "3", "title", "w3", "description", "w3 w1", "popularity",
        "20"));
    assertU(adoc("id", "4", "title", "w4", "description", "w4", "popularity",
        "4"));
    assertU(adoc("id", "5", "title", "w5", "description", "w5", "popularity",
        "5"));
    assertU(commit());

    loadFeatures("lambdamart_features.json"); // currently needed to force
    // loading models
    loadModels("lambdamart_model_originalScore_additive.json");
    loadModels("lambdamart_model_originalScore_multiplicative.json");
    loadModels("lambdamart_model_originalScore_noBoost.json");
    loadModels("lambdamart_model.json");
  }

  @AfterClass
  public static void after() throws Exception {
    aftertest();
  }

  @Test
  public void scoreCalculus_noOriginalScoreFeature_shouldOverwriteScoreWithLambdaMARTOutput() throws Exception {
    final SolrQuery query = new SolrQuery();
    query.setQuery("field(popularity)");
    query.add("rows", "3");
    query.setParam("defType", "func");
    query.add("fl", "*,score");

    // Regular scores
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/score==100.0");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='2'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/score==50.0");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='3'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/score==20.0");

    // Matched user query since it was passed in
    query.remove("rq");
    query.add("rq",
        "{!ltr reRankDocs=3 model=lambdamartmodel efi.user_query=w3}");

    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='3'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/score==30.0");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='1'");
    assertJQ("/query" + query.toQueryString(),
        "/response/docs/[1]/score==-120.0");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='2'");
    assertJQ("/query" + query.toQueryString(),
        "/response/docs/[2]/score==-120.0");
  }

  @Test
  public void scoreCalculus_noBoostOriginalScoreFeature_shoulDefaultMultiplyToLambdaMARTScore() throws Exception {
    final SolrQuery query = new SolrQuery();
    query.setQuery("field(popularity)");
    query.add("rows", "3");
    query.setParam("defType", "func");
    query.add("fl", "*,score");

    // Regular scores
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='1'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/score==100.0");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='2'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/score==50.0");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='3'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/score==20.0");

    // Matched user query since it was passed in
    query.remove("rq");
    query.add("rq",
        "{!ltr reRankDocs=3 model=lambdaMARTModelOriginalScoreNoBoost efi.user_query=w3}");

    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='3'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/score==30.0");

    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='1'");
    assertJQ("/query" + query.toQueryString(),
        "/response/docs/[1]/score==-24.0");

    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='2'");
    assertJQ("/query" + query.toQueryString(),
        "/response/docs/[2]/score==-48.0");
  }

  @Test
  public void scoreCalculus_additiveOriginalScoreBoost_shouldAddToLambdaMARTScore() throws Exception {
    final SolrQuery query = new SolrQuery();
    query.setQuery("field(popularity)");
    query.add("rows", "3");
    query.setParam("defType", "func");
    query.add("fl", "*,score");

    // Regular scores
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='1'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/score==100.0");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='2'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/score==50.0");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='3'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/score==20.0");

    // Matched user query since it was passed in
    query.remove("rq");
    query.add("rq",
        "{!ltr reRankDocs=3 model=lambdaMARTModelOriginalScoreAdditive efi.user_query=w3}");

    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='3'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/score==32.0");

    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='1'");
    assertJQ("/query" + query.toQueryString(),
        "/response/docs/[1]/score==-110.0");

    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='2'");
    assertJQ("/query" + query.toQueryString(),
        "/response/docs/[2]/score==-115.0");
  }

  @Test
  public void scoreCalculus_multiplicativeOriginalScoreBoost_shouldMultiplyToLambdaMARTScore() throws Exception {
    final SolrQuery query = new SolrQuery();
    query.setQuery("field(popularity)");
    query.add("rows", "3");
    query.setParam("defType", "func");
    query.add("fl", "*,score");

    // Regular scores
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='1'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/score==100.0");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='2'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/score==50.0");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='3'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/score==20.0");

    // Matched user query since it was passed in
    query.remove("rq");
    query.add("rq",
        "{!ltr reRankDocs=3 model=lambdaMARTModelOriginalScoreMultiplicative efi.user_query=w3}");

    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='3'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/score==300.0");

    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='1'");
    assertJQ("/query" + query.toQueryString(),
        "/response/docs/[1]/score==-2.4");

    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='2'");
    assertJQ("/query" + query.toQueryString(),
        "/response/docs/[2]/score==-4.8");
  }

  @Test(expected = ModelException.class)
  public void parsingLambdaMARTModel_noOriginalScoreFeatureInTheModel_shouldThrowException() throws Exception {
    createModelFromFiles("lambdamart_model_originalScore_noOriginalScoreFeatureDefined.json",
        "lambdamart_features.json");
  }

  @Test(expected = ModelException.class)
  public void parsingLambdaMARTModel_noWeightForOriginalScore_shouldThrowException() throws Exception {
    createModelFromFiles("lambdamart_model_originalScore_noWeight.json",
        "lambdamart_features.json");
  }

  @Test(expected = ModelException.class)
  public void lambdaMartTestNoParams() throws Exception {
    createModelFromFiles("lambdamart_model_no_params.json",
        "lambdamart_features.json");

  }

  @Test(expected = ModelException.class)
  public void lambdaMartTestNoTrees() throws Exception {
    createModelFromFiles("lambdamart_model_no_trees.json",
        "lambdamart_features.json");
  }

  @Test(expected = ModelException.class)
  public void lambdaMartTestNoWeight() throws Exception {
    createModelFromFiles("lambdamart_model_no_weight.json",
        "lambdamart_features.json");
  }

  @Test(expected = ModelException.class)
  public void lambdaMartTestNoTree() throws Exception {
    createModelFromFiles("lambdamart_model_no_tree.json",
        "lambdamart_features.json");
  }

  @Test(expected = SolrException.class)
  public void lambdaMartTestNoFeatures() throws Exception {
    createModelFromFiles("lambdamart_model_no_features.json",
        "lambdamart_features.json");
  }

  @Test(expected = ModelException.class)
  public void lambdaMartTestNoRight() throws Exception {
    createModelFromFiles("lambdamart_model_no_right.json",
        "lambdamart_features.json");

  }

  @Test(expected = ModelException.class)
  public void lambdaMartTestNoLeft() throws Exception {
    createModelFromFiles("lambdamart_model_no_left.json",
        "lambdamart_features.json");
  }

  @Test(expected = ModelException.class)
  public void lambdaMartTestNoThreshold() throws Exception {
    createModelFromFiles("lambdamart_model_no_threshold.json",
        "lambdamart_features.json");

  }

  @Test(expected = ModelException.class)
  public void lambdaMartTestNoFeature() throws Exception {
    createModelFromFiles("lambdamart_model_no_feature.json",
        "lambdamart_features.json");
  }
}
