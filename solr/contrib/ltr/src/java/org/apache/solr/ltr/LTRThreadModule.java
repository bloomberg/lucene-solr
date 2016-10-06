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
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.util.DefaultSolrThreadFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;


public class LTRThreadModule {
  ThreadMXBean tmxb = ManagementFactory.getThreadMXBean();
  final private Semaphore ltrSemaphore;
  final private int maxThreads;
  final private int maxQueryThreads;
  public static final int DEFAULT_MAX_THREADS = 0; // do not do threading if 'maxThreads' is not specified in the config file
  public static final int DEFAULT_MAX_QUERYTHREADS = 0; // do not do threading if 'maxQueryThreads' is not specified in the config file

   private final Executor createWeightScoreExecutor = new ExecutorUtil.MDCAwareThreadPoolExecutor(
          0,
          Integer.MAX_VALUE,
          10, TimeUnit.SECONDS, // terminate idle threads after 10 sec
          new SynchronousQueue<Runnable>(),  // directly hand off tasks
          new DefaultSolrThreadFactory("ltrExecutor")
    );
   
   public LTRThreadModule(int maxThreads, int maxQueryThreads){
      this.maxThreads = maxThreads;
      this.maxQueryThreads = maxQueryThreads;
      if (maxThreads < 0){
        throw new NumberFormatException("maxThreads cannot be less than 0");
      }
      if (maxQueryThreads < 0){
        throw new NumberFormatException("maxQueryThreads cannot be less than 0");
      }
      if (maxThreads < maxQueryThreads){
        throw new NumberFormatException("maxQueryThreads cannot be greater than maxThreads");
      }
      if  (this.maxThreads > 1 ){
        ltrSemaphore = new Semaphore(maxThreads);
      } else {
        ltrSemaphore = null;
      }
   }
   
   
   public int getMaxThreads(){
      return maxThreads;
   }
   
   public int getMaxQueryThreads(){
     return maxQueryThreads;
   }
   
   public Semaphore getLTRSemaphore(){
     return ltrSemaphore;
   }
   
   public Executor getCreateWeightScoreExecutor(){
     return createWeightScoreExecutor;
   }
}
