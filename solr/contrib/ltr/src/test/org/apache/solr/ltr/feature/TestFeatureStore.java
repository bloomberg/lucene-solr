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

import java.util.HashMap;
import java.util.Map;

import org.apache.solr.ltr.TestRerankBase;
import org.apache.solr.ltr.feature.impl.OriginalScoreFeature;
import org.apache.solr.ltr.feature.impl.ValueFeature;
import org.apache.solr.ltr.ranking.Feature;
import org.apache.solr.ltr.rest.ManagedFeatureStore;
import org.apache.solr.ltr.util.FeatureException;
import org.apache.solr.ltr.util.LTRUtils;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestFeatureStore extends TestRerankBase {

  static ManagedFeatureStore fstore = null;

  @BeforeClass
  public static void setup() throws Exception {
    setuptest();
    fstore = getNewManagedFeatureStore();
  }

  @Test
  public void testDefaultFeatureStoreName()
  {
    assertEquals("_DEFAULT_", FeatureStore.DEFAULT_FEATURE_STORE_NAME);
    final FeatureStore expectedFeatureStore = fstore.getFeatureStore(FeatureStore.DEFAULT_FEATURE_STORE_NAME);
    final FeatureStore actualFeatureStore = fstore.getFeatureStore(null);
    assertEquals("getFeatureStore(null) should return the default feature store", expectedFeatureStore, actualFeatureStore);
  }

  @Test
  public void testFeatureStoreAdd() throws FeatureException 
  {
    final FeatureStore fs = fstore.getFeatureStore("fstore-testFeature");
    for (int i = 0; i < 5; i++) {
      fstore.addFeature("c" + i, OriginalScoreFeature.class.getCanonicalName(),
          "fstore-testFeature", LTRUtils.EMPTY_MAP);

      assertTrue(fs.containsFeature("c" + i));

    }
    assertEquals(5, fs.size());

  }

  @Test
  public void testFeatureStoreGet() throws FeatureException
  {
    final FeatureStore fs = fstore.getFeatureStore("fstore-testFeature2");
    for (int i = 0; i < 5; i++) {

      Map<String,Object> params = new HashMap<String,Object>();
      params.put("value", i);
      fstore.addFeature("c" + i, ValueFeature.class.getCanonicalName(),
          "fstore-testFeature2", params);

    }

    for (int i = 0; i < 5; i++) {
      final Feature f = fs.get("c" + i);
      assertEquals("c" + i, f.getName());
      assertTrue(f instanceof ValueFeature);
      final ValueFeature vf = (ValueFeature)f;
      assertEquals(i, vf.getValue());
    }
  }

  @Test
  public void testMissingFeatureReturnsNull() {
    final FeatureStore fs = fstore.getFeatureStore("fstore-testFeature3");
    for (int i = 0; i < 5; i++) {
      Map<String,Object> params = new HashMap<String,Object>();
      params.put("value", i);
      fstore.addFeature("testc" + (float) i,
          ValueFeature.class.getCanonicalName(), "fstore-testFeature3",
          params);

    }
    assertNull(fs.get("missing_feature_name"));
  }

}
