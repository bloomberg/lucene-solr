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

import org.apache.solr.ltr.TestRerankBase;
import org.apache.solr.ltr.feature.impl.OriginalScoreFeature;
import org.apache.solr.ltr.feature.impl.ValueFeature;
import org.apache.solr.ltr.ranking.Feature;
import org.apache.solr.ltr.rest.ManagedFeatureStore;
import org.apache.solr.ltr.util.FeatureException;
import org.apache.solr.ltr.util.NamedParams;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestFeatureMetadata extends TestRerankBase {

  static ManagedFeatureStore store = null;

  @BeforeClass
  public static void setup() throws Exception {
    setuptest();
    store = getNewManagedFeatureStore();
  }

  @AfterClass
  public static void after() throws Exception {
    aftertest();
  }

  @Test
  public void getInstanceTest() throws FeatureException
  {

    store.addFeature("test", OriginalScoreFeature.class.getCanonicalName(),
        "testFstore", NamedParams.EMPTY);
    final Feature feature = store.getFeatureStore("testFstore").get("test");
    assertEquals("test", feature.getName());
    assertEquals(OriginalScoreFeature.class.getCanonicalName(), feature
        .getClass().getCanonicalName());
  }

  @Test(expected = FeatureException.class)
  public void getInvalidInstanceTest() throws FeatureException
  {
    store.addFeature("test", "org.apache.solr.ltr.feature.LOLFeature",
        "testFstore2", NamedParams.EMPTY);

  }
  
}
