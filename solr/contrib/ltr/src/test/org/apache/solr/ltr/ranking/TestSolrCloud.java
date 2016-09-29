/* * Licensed to the Apache Software Foundation (ASF) under one or more
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

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.util.SortedMap;
import org.apache.commons.io.FileUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.embedded.JettyConfig;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.AbstractDistribZkTestBase;
import org.apache.solr.cloud.MiniSolrCloudCluster;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.ltr.TestRerankBase;
import org.apache.solr.ltr.feature.SolrFeature;
import org.apache.solr.ltr.feature.ValueFeature;
import org.apache.solr.ltr.util.CommonLTRParams;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.AfterClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestSolrCloud  extends TestRerankBase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private MiniSolrCloudCluster solrCluster;
  String solrconfig = "solrconfig-ltr.xml";
  String schema = "schema-ltr.xml";
    
  SortedMap<ServletHolder,String> extraServlets = null; 

  @Override
  public void setUp() throws Exception {
    super.setUp();
    extraServlets = setupTestInit(solrconfig, schema, true);
    System.setProperty("enable.update.log", "true");

    int numberOfShards = 1; //random().nextInt(4)+1;
    int numberOfReplicas = 1; //random().nextInt(2)+1;
    // Maybe this should be a randomized variable?
    int maxShardsPerNode = 2;

    int numberOfNodes = 1;

    setupSolrCluster(numberOfShards, numberOfReplicas, numberOfNodes, maxShardsPerNode);


  }


  @Override
  public void tearDown() throws Exception {
    restTestHarness.close();
    restTestHarness = null;
    jetty.stop();
    jetty = null;
    solrCluster.shutdown();
    super.tearDown();
  }

  @Test
  public void testSimpleQuery() throws Exception {
    // will randomly pick a configuration with [1..5] shards and [1..3] replicas

    // Test regular query, it will sort the documents by inverse
    // popularity (the less popular, docid == 1, will be in the first
    // position
    SolrQuery query = new SolrQuery("{!func}sub(8,field(popularity))");

    query.setRequestHandler("/query");
    query.setFields("*,score");
    query.setParam("rows", "8");
        
//    QueryResponse queryResponse =
//        solrCluster.getSolrClient().query(COLLECTION,query);
//    assertEquals(8, queryResponse.getResults().getNumFound());
//    assertEquals("1", queryResponse.getResults().get(0).get("id").toString());
//    assertEquals("2", queryResponse.getResults().get(1).get("id").toString());
//    assertEquals("3", queryResponse.getResults().get(2).get("id").toString());
//    assertEquals("4", queryResponse.getResults().get(3).get("id").toString());
//
//    // Test re-rank and feature vectors returned
//    query.setFields("*,score,features:[fv]");
//    query.add("rq", "{!ltr model=powpularityS-model reRankDocs=8}");
//    queryResponse =
//        solrCluster.getSolrClient().query(COLLECTION,query);
//    assertEquals(8, queryResponse.getResults().getNumFound());
//    assertEquals("4", queryResponse.getResults().get(0).get("id").toString());
//    assertEquals("powpularityS:16.0;c3:2.0",
//        queryResponse.getResults().get(0).get("features").toString());
//    assertEquals("3", queryResponse.getResults().get(1).get("id").toString());
//    assertEquals("powpularityS:9.0;c3:2.0",
//        queryResponse.getResults().get(1).get("features").toString());
//    assertEquals("2", queryResponse.getResults().get(2).get("id").toString());
//    assertEquals("powpularityS:4.0;c3:2.0",
//        queryResponse.getResults().get(2).get("features").toString());
//    assertEquals("1", queryResponse.getResults().get(3).get("id").toString());
//    assertEquals("powpularityS:1.0;c3:2.0",
//        queryResponse.getResults().get(3).get("features").toString());
  }

  private void setupSolrCluster(int numShards, int numReplicas, int numServers, int maxShardsPerNode) throws Exception {
    JettyConfig jc = buildJettyConfig("/solr");
    jc = JettyConfig.builder(jc).withServlets(extraServlets).build();
    solrCluster = new MiniSolrCloudCluster(numServers, tmpSolrHome.toPath(), jc);
    File configDir = tmpSolrHome.toPath().resolve("collection1/conf").toFile();
    solrCluster.uploadConfigDir(configDir, "conf1");

    solrCluster.getSolrClient().setDefaultCollection(COLLECTION);

    createCollection(COLLECTION, "conf1", numShards, numReplicas, maxShardsPerNode);
    indexDocuments(COLLECTION);

    createJettyAndHarness(tmpSolrHome.getAbsolutePath(), solrconfig, schema,
            "/solr", true, extraServlets);
    loadModelsAndFeatures();
  }


  private void createCollection(String name, String config, int numShards, int numReplicas, int maxShardsPerNode)
      throws Exception {
    CollectionAdminResponse response;
    CollectionAdminRequest.Create create = 
        CollectionAdminRequest.createCollection(name, config, numShards, numReplicas);
    create.setMaxShardsPerNode(maxShardsPerNode);
    response = create.process(solrCluster.getSolrClient());

    if (response.getStatus() != 0 || response.getErrorMessages() != null) {
      fail("Could not create collection. Response" + response.toString());
    }
    ZkStateReader zkStateReader = solrCluster.getSolrClient().getZkStateReader();
    AbstractDistribZkTestBase.waitForRecoveriesToFinish(name, zkStateReader, false, true, 100);
  }

  private void indexDocuments(String collection)
       throws Exception {

    SolrInputDocument doc = new SolrInputDocument();
    doc.setField("id", "1");
    doc.setField("title", "a1");
    doc.setField("description", "bloom");
    doc.setField("popularity", 1);
    solrCluster.getSolrClient().add(collection, doc);

    doc = new SolrInputDocument();
    doc.setField("id", "2");
    doc.setField("title", "a1");
    doc.setField("description", "bloom");
    doc.setField("popularity", 2);
    solrCluster.getSolrClient().add(collection, doc);

    doc = new SolrInputDocument();
    doc.setField("id", "3");
    doc.setField("title", "a1");
    doc.setField("description", "bloom");
    doc.setField("popularity", 3);
    solrCluster.getSolrClient().add(collection, doc);

    doc = new SolrInputDocument();
    doc.setField("id", "4");
    doc.setField("title", "a1");
    doc.setField("description", "bloom");
    doc.setField("popularity", 4);
    solrCluster.getSolrClient().add(collection, doc);

    doc = new SolrInputDocument();
    doc.setField("id", "5");
    doc.setField("title", "a1");
    doc.setField("description", "bloom");
    doc.setField("popularity", 5);
    solrCluster.getSolrClient().add(collection, doc);

    doc = new SolrInputDocument();
    doc.setField("id", "6");
    doc.setField("title", "a1");
    doc.setField("description", "bloom");
    doc.setField("popularity", 6);
    solrCluster.getSolrClient().add(collection, doc);

    doc = new SolrInputDocument();
    doc.setField("id", "7");
    doc.setField("title", "a1");
    doc.setField("description", "bloom");
    doc.setField("popularity", 7);
    solrCluster.getSolrClient().add(collection, doc);

    doc = new SolrInputDocument();
    doc.setField("id", "8");
    doc.setField("title", "a1");
    doc.setField("description", "bloom");
    doc.setField("popularity", 8);
    solrCluster.getSolrClient().add(collection, doc);
    
    solrCluster.getSolrClient().commit(collection);
  }


  private void loadModelsAndFeatures() throws Exception {
    String featureJson1 = getFeatureInJson(
            "powpularityS", SolrFeature.class.getCanonicalName(),
            "test","{\"q\":\"{!func}pow(popularity,2)\"}");
    assertJPut(CommonLTRParams.FEATURE_STORE_END_POINT, featureJson1,
            "/responseHeader/status==0");
    String featureJson2 = getFeatureInJson(
            "c3", ValueFeature.class.getCanonicalName(),
            "test", "{\"value\":2}");
    assertJPut(CommonLTRParams.FEATURE_STORE_END_POINT, featureJson2,
            "/responseHeader/status==0");
    String modelJson = getModelInJson(
            "powpularityS-model", RankSVMModel.class.getCanonicalName(),
            new String[] {"powpularityS","c3"}, "test",
            "{\"weights\":{\"powpularityS\":1.0,\"c3\":1.0}}");
    assertJPut(CommonLTRParams.MODEL_STORE_END_POINT, modelJson,
            "/responseHeader/status==0");
    reloadCollection(COLLECTION);
  }
  
  private void reloadCollection(String collection) throws Exception {
    CollectionAdminRequest.Reload reloadRequest = CollectionAdminRequest.reloadCollection(collection);
    CollectionAdminResponse response = reloadRequest.process(solrCluster.getSolrClient());
    assertEquals(0, response.getStatus());
    assertTrue(response.isSuccess());
  }
  
  @AfterClass
  public static void after() throws Exception {
    FileUtils.deleteDirectory(tmpSolrHome);
    System.clearProperty("managed.schema.mutable");
  }

}
