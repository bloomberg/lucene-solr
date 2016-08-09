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

import java.util.Map;

import org.apache.solr.search.StrParser;
import org.apache.solr.search.SyntaxError;

// TODO This should be replaced with the MacroExpander inside Solr 5.2
public class MacroExpander {
  public static final String MACRO_START = "${";

  private final Map<String,String> orig;
  private final String macroStart = MACRO_START;
  private final char escape = '\\';

  public MacroExpander(Map<String,String> orig) {
    this.orig = orig;
   }

  public static String expand(String val, Map<String,String> params) {
    final MacroExpander mc = new MacroExpander(params);
    return mc.expand(val);
  }

  public String expand(String val) {
    // quickest short circuit
    int idx = val.indexOf(macroStart.charAt(0));
    if (idx < 0) {
      return val;
    }

    int start = 0; // start of the unprocessed part of the string
    StringBuilder sb = null;
    for (;;) {
      idx = val.indexOf(macroStart, idx);
      final int matchedStart = idx;

      // check if escaped
      if (idx > 0) {
        // check if escaped...
        // TODO: what if you *want* to actually have a backslash... perhaps
        // that's when we allow changing
        // of the escape character?

        final char ch = val.charAt(idx - 1);
        if (ch == escape) {
          idx += macroStart.length();
          continue;
        }
      } else if (idx < 0) {
        if (sb == null) {
          return val;
        }
        sb.append(val.substring(start));
        return sb.toString();
      }

      // found unescaped "${"
      idx += macroStart.length();

      final int rbrace = val.indexOf('}', idx);
      if (rbrace == -1) {
        // no matching close brace...
        continue;
      }

      if (sb == null) {
        sb = new StringBuilder(val.length() * 2);
      }

      if (matchedStart > 0) {
        sb.append(val.substring(start, matchedStart));
      }

      // update "start" to be at the end of ${...}
      start = rbrace + 1;

      // String inbetween = val.substring(idx, rbrace);
      final StrParser parser = new StrParser(val, idx, rbrace);
      try {
        final String paramName = parser.getId();
        String defVal = null;
        final boolean hasDefault = parser.opt(':');
        if (hasDefault) {
          defVal = val.substring(parser.pos, rbrace);
        }

        // in the event that expansions become context dependent... consult original?
        final String replacement = orig.get(paramName);
        if (replacement != null) {
          sb.append(replacement);
        }
        else if (defVal != null) {
          sb.append(defVal);
        }
        else {
          return null;
        }

      } catch (final SyntaxError syntaxError) {
        return null;
      }

    }
  }
}
