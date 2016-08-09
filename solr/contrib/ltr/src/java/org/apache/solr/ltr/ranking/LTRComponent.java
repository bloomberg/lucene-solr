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

import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.SearchComponent;
import org.apache.solr.ltr.rest.ManagedFeatureStore;
import org.apache.solr.ltr.rest.ManagedModelStore;
import org.apache.solr.ltr.util.CommonLTRParams;
import org.apache.solr.rest.ManagedResource;
import org.apache.solr.rest.ManagedResourceObserver;
import org.apache.solr.util.plugin.SolrCoreAware;

/**
 * The FeatureVectorComponent is intended to be used for offline training of
 * your model in order to fetch the feature vectors of the top matching
 * documents.
 */
public class LTRComponent extends SearchComponent implements SolrCoreAware,
    ManagedResourceObserver {

  @SuppressWarnings("rawtypes")
  @Override
  public void init(NamedList args) {}

  @Override
  public void prepare(ResponseBuilder rb) throws IOException {}

  @Override
  public void process(ResponseBuilder rb) throws IOException {}

  @Override
  public String getDescription() {
    return "Manages models and features in Solr";
  }

  @Override
  public void onManagedResourceInitialized(NamedList<?> args,
      ManagedResource res) throws SolrException {
    // FIXME do we need this?
  }

  @Override
  public void inform(SolrCore core) {
    final ManagedFeatureStore fr = (ManagedFeatureStore) core.getRestManager().addManagedResource(
        CommonLTRParams.FEATURE_STORE_END_POINT, ManagedFeatureStore.class);
    final ManagedModelStore mr = (ManagedModelStore) core.getRestManager().addManagedResource(
        CommonLTRParams.MODEL_STORE_END_POINT, ManagedModelStore.class);

    mr.init(fr);
    // now we can safely load the models
    mr.loadStoredModels();
  }
}
