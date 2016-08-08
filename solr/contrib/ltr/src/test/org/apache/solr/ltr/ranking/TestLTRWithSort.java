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

import org.apache.lucene.util.LuceneTestCase.SuppressCodecs;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.ltr.TestRerankBase;
import org.apache.solr.ltr.feature.impl.SolrFeature;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

@SuppressCodecs({"Lucene3x", "Lucene41", "Lucene40", "Appending"})
public class TestLTRWithSort extends TestRerankBase {

  @BeforeClass
  public static void before() throws Exception {
    setuptest("solrconfig-ltr.xml", "schema-ltr.xml");

    assertU(adoc("id", "1", "title", "w1", "description", "E", "popularity",
        "1"));
    assertU(adoc("id", "2", "title", "w2 2asd asdd didid", "description",
        "B", "popularity", "2"));
    assertU(adoc("id", "3", "title", "w3", "description", "B", "popularity",
        "3"));
    assertU(adoc("id", "4", "title", "w4", "description", "B", "popularity",
        "4"));
    assertU(adoc("id", "5", "title", "w5", "description", "E", "popularity",
        "5"));
    assertU(adoc("id", "6", "title", "w1 w2", "description", "B",
        "popularity", "6"));
    assertU(adoc("id", "7", "title", "w1 w2 w3 w4 w5", "description",
        "C", "popularity", "7"));
    assertU(adoc("id", "8", "title", "w1 w1 w1 w2 w2 w8", "description",
        "D", "popularity", "8"));
    assertU(commit());
  }
  
  @Test
  public void testRankingSolrSort() throws Exception {
    // before();
    loadFeature("powpularityS", SolrFeature.class.getCanonicalName(),
        "{\"q\":\"{!func}pow(popularity,2)\"}");

    loadModel("powpularityS-model", RankSVMModel.class.getCanonicalName(),
        new String[] {"powpularityS"}, "{\"weights\":{\"powpularityS\":1.0}}");

    final SolrQuery query = new SolrQuery();
    query.setQuery("*:*");
    query.add("fl", "*, score");
    query.add("rows", "4");
    query.add("sort", "description desc");

    assertJQ("/query" + query.toQueryString(), "/response/numFound/==8");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='1'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='5'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='8'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[3]/id=='7'");
    // Normal term match

    query.add("rq", "{!ltr model=powpularityS-model reRankDocs=4}");
    query.set("debugQuery", "on");

    assertJQ("/query" + query.toQueryString(), "/response/numFound/==8");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='8'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/score==64.0");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='7'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/score==49.0");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='5'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/score==25.0");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[3]/id=='1'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[3]/score==1.0");

    // aftertest();

  }

  @AfterClass
  public static void after() throws Exception {
    aftertest();
  }

}