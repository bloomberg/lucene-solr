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
package org.apache.solr.ltr;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.ltr.feature.Feature.FeatureWeight;
import org.apache.solr.util.DefaultSolrThreadFactory;
import org.apache.solr.util.SolrPluginUtils;
import org.apache.solr.util.plugin.NamedListInitializedPlugin;

final public class LTRThreadModule implements NamedListInitializedPlugin {

  private static String CONFIG_PREFIX = "threadModule.";

  public static NamedList extractThreadModuleParams(NamedList args) {

    // gather the thread module args from amongst the general args
    final NamedList extractedArgs = new NamedList();
    for (Iterator<Map.Entry<String,Object>> it = args.iterator();
        it.hasNext(); ) {
      final Map.Entry<String,Object> entry = it.next();
      final String key = entry.getKey();
      if (key.startsWith(CONFIG_PREFIX)) {
        extractedArgs.add(key.substring(CONFIG_PREFIX.length()), entry.getValue());
      }
    }

    // remove consumed keys only once iteration is complete
    // since NamedList iterator does not support 'remove'
    for (Object key : extractedArgs.asShallowMap().keySet()) {
      args.remove(CONFIG_PREFIX+key);
    }

    return extractedArgs;
  }

  // settings
  private int maxThreads = 1;
  private int maxQueryThreads = 1;
  private int maxPoolSize = Integer.MAX_VALUE;
  private long keepAliveTimeSeconds = 10;
  private String threadNamePrefix = "ltrExecutor";

  ThreadMXBean tmxb = ManagementFactory.getThreadMXBean();

  // implementation
  private Semaphore ltrSemaphore;
  private Executor createWeightScoreExecutor;

  private final void initCreateWeightScoreExecutor() {
  }

  public LTRThreadModule() {
  }

  // For test use only.
  LTRThreadModule(int maxThreads, int maxQueryThreads) {
    this.maxThreads = maxThreads;
    this.maxQueryThreads = maxQueryThreads;
    init(null);
  }

  @Override
  public void init(NamedList args) {
    if (args != null) {
      SolrPluginUtils.invokeSetters(this, args);
    }
    validate();
    if  (this.maxThreads > 1 ){
      ltrSemaphore = new Semaphore(maxThreads);
    } else {
      ltrSemaphore = null;
    }
    createWeightScoreExecutor = new ExecutorUtil.MDCAwareThreadPoolExecutor(
        0,
        maxPoolSize,
        keepAliveTimeSeconds, TimeUnit.SECONDS, // terminate idle threads after 10 sec
        new SynchronousQueue<Runnable>(),  // directly hand off tasks
        new DefaultSolrThreadFactory(threadNamePrefix)
        );
  }

  public void validate() {
    if (maxThreads <= 0){
      throw new IllegalArgumentException("maxThreads cannot be less than 1");
    }
    if (maxQueryThreads <= 0){
      throw new IllegalArgumentException("maxQueryThreads cannot be less than 1");
    }
    if (maxThreads < maxQueryThreads){
      throw new IllegalArgumentException("maxQueryThreads cannot be greater than maxThreads");
    }
  }

  public void setMaxThreads(int maxThreads) {
    this.maxThreads = maxThreads;
  }

  public void setMaxQueryThreads(int maxQueryThreads) {
    this.maxQueryThreads = maxQueryThreads;
  }

  public void setMaxPoolSize(int maxPoolSize) {
    this.maxPoolSize = maxPoolSize;
  }

  public void setThreadNamePrefix(String threadNamePrefix) {
    this.threadNamePrefix = threadNamePrefix;
  }

  public Semaphore createQuerySemaphore() {
    return (maxQueryThreads > 1 ? new Semaphore(maxQueryThreads) : null);
  }

  public void acquireLTRSemaphore() throws InterruptedException {
    ltrSemaphore.acquire();
  }

  public void releaseLTRSemaphore() throws InterruptedException {
    ltrSemaphore.release();
  }

  public void execute(RunnableFuture<FeatureWeight> runnableFuture) {
    createWeightScoreExecutor.execute(runnableFuture);
  }

}
