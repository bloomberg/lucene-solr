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
package org.apache.solr.ltr.util;

import java.util.HashMap;
import java.util.Map;

public class LTRUtils {

  static public final Map<String,Object> EMPTY_MAP = new HashMap<String,Object>();
  
  @Deprecated
  public static float convertToFloat(Object o) {
    float f = 0;
    if (o instanceof Double) {
      final double d = (Double) o;
      f = (float) d;
      return f;
    }
    if (o instanceof Integer) {
      final int d = (Integer) o;
      f = d;
      return f;
    }
    if (o instanceof Long) {
      final long l = (Long) o;
      f = l;
      return f;
    }
    if (o instanceof Float) {
      final Float ff = (Float) o;
      f = ff;
      return f;
    }
    if (o instanceof String) {
      final Float ff = Float.parseFloat((String)o);
      f = ff;
      return f;
    }

    throw new NumberFormatException(o.getClass().getName()
        + " cannot be converted to float");
  }
  
  public static int getInt(Object thObj, int defValue, String paramName) throws NumberFormatException{
    if (thObj != null) {
      try{
         return Integer.parseInt(thObj.toString());
      }catch(NumberFormatException nfe){
         String errorStr = nfe.toString() + ":" + paramName + " not an integer";
         throw new NumberFormatException(errorStr);
      }
    }
    return defValue; 
 }
  
}
