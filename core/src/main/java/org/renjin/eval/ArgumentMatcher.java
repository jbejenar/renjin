/**
 * Renjin : JVM-based interpreter for the R language for the statistical analysis
 * Copyright © 2010-2016 BeDataDriven Groep B.V. and contributors
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, a copy is available at
 * https://www.gnu.org/licenses/gpl-2.0.txt
 */
package org.renjin.eval;

import org.renjin.sexp.Closure;
import org.renjin.sexp.PairList;
import org.renjin.sexp.SEXP;
import org.renjin.sexp.Symbol;

import java.util.Arrays;

/**
 * Matches arguments
 */
public class ArgumentMatcher {

  private final String[] formalNames;
  private int formalElipses;

  public ArgumentMatcher(String[] formalNames) {
    this.formalNames = formalNames;
    this.formalElipses = findElipses(this.formalNames);
  }

  public ArgumentMatcher(Closure closure) {
    this(closure.getFormals());
  }

  public ArgumentMatcher(PairList formals) {
    this(toNameArray(formals));
  }

  private static String[] toNameArray(PairList formals) {
    String[] array = new String[formals.length()];
    int i = 0;
    for (PairList.Node node : formals.nodes()) {
      SEXP tag = node.getRawTag();
      if(tag instanceof Symbol) {
        array[i++] = ((Symbol) tag).getPrintName();
      }
    }
    return array;
  }

  public MatchedArguments match(PairList actuals) {
    return match(toNameArray(actuals));
  }

  /**
   * Argument matching is done by a three-pass process:
   * <ol>
   * <li><strong>Exact matching on tags.</strong> For each named supplied argument the list of formal arguments
   *  is searched for an item whose name matches exactly. It is an error to have the same formal
   * argument match several actuals or vice versa.</li>
   *
   * <li><strong>Partial matching on tags.</strong> Each remaining named supplied argument is compared to the
   * remaining formal arguments using partial matching. If the name of the supplied argument
   * matches exactly with the first part of a formal argument then the two arguments are considered
   * to be matched. It is an error to have multiple partial matches.
   *  Notice that if f <- function(fumble, fooey) fbody, then f(f = 1, fo = 2) is illegal,
   * even though the 2nd actual argument only matches fooey. f(f = 1, fooey = 2) is legal
   * though since the second argument matches exactly and is removed from consideration for
   * partial matching. If the formal arguments contain ‘...’ then partial matching is only applied to
   * arguments that precede it.
   *
   * <li><strong>Positional matching.</strong> Any unmatched formal arguments are bound to unnamed supplied arguments,
   * in order. If there is a ‘...’ argument, it will take up the remaining arguments, tagged or not.
   * If any arguments remain unmatched an error is declared.
   *
   */
  public MatchedArguments match(String[] actualNames) {

    int[] formalToActual = new int[formalNames.length];
    Arrays.fill(formalToActual, -1);

    boolean[] matchedFormals = new boolean[formalNames.length];
    boolean[] matchedActuals = new boolean[actualNames.length];

    // do exact matching
    for (int formalIndex = 0; formalIndex < formalNames.length; formalIndex++) {
      String formalName = formalNames[formalIndex];
      if(formalIndex != formalElipses) {
        int exactMatchIndex = findExactMatch(formalName, actualNames);
        if (exactMatchIndex != -1) {
          formalToActual[formalIndex] = exactMatchIndex;
          matchedActuals[exactMatchIndex] = true;
          matchedFormals[formalIndex] = true;
        }
      }
    }
    // Partial matching
    for (int actualIndex = 0; actualIndex < actualNames.length; actualIndex++) {
      if(!matchedActuals[actualIndex]) {
        String actualName = actualNames[actualIndex];
        if(actualName != null && !actualName.equals("...")) {
          int partialMatch = findPartialMatch(actualName, formalNames, matchedFormals);
          if(partialMatch != -1) {
            formalToActual[partialMatch] = actualIndex;
            matchedActuals[actualIndex] = true;
            matchedFormals[partialMatch] = true;
          }
        }
      }
    }

    // match any unnamed args positionally to remaining formals

    int nextActual = 0;
    for (int formalIndex = 0; formalIndex < formalNames.length; formalIndex++) {
      if(formalIndex == formalElipses) {
        break;
      }
      if(!matchedFormals[formalIndex]) {
        nextActual = findNextUnnamed(actualNames, nextActual);
        if(nextActual == -1) {
          break;
        }
        formalToActual[formalIndex] = nextActual;
        matchedActuals[nextActual] = true;
        nextActual++;
      }
    }

    // match any remaining unmatched actuals to extra arguments, if present
    if(formalElipses == -1) {
      if (hasUnmatched(matchedActuals)) {
        throw new EvalException("Unused arguments");
      }
    }

    return new MatchedArguments(formalNames, formalToActual, matchedActuals);
  }

  private boolean hasUnmatched(boolean[] matchedActuals) {
    for (int i = 0; i < matchedActuals.length; i++) {
      if(!matchedActuals[i]) {
        return true;
      }
    }
    return false;
  }

  private static int findElipses(String[] formalNames) {
    for (int i = 0; i < formalNames.length; i++) {
      if(formalNames[i].equals("...")) {
        return i;
      }
    }
    return -1;
  }

  private static int findNextUnnamed(String actualNames[], int start) {
    int i = start;
    while(i < actualNames.length) {
      if(actualNames[i] == null) {
        return i;
      }
      i++;
    }
    return -1;
  }


  private static int findNextUnmatched(boolean[] actualMatched, int start) {
    int i = start;
    while(i < actualMatched.length) {
      if(!actualMatched[i]) {
        return i;
      }
      i++;
    }
    return -1;
  }

  /**
   * Find an argument name that *exactly* matches the given formal name.
   *
   * @return -1 if there is no match, or the index of the matching actual if there is exactly one match
   * @throws EvalException if there is more than one exact match.
   */
  private static int findExactMatch(String formalName, String[] argumentNames) {

    int match = -1;

    for (int i = 0; i < argumentNames.length; i++) {
      if(argumentNames[i] != null) {
        if(argumentNames[i].equals(formalName)) {
          if(match != -1) {
            throw new EvalException(String.format("Multiple named values provided for argument '%s'", formalName));
          }
          match = i;
        }
      }
    }
    return match;
  }


  /**
   * Finds an *unmatched* formal name that *partially* matches the given actual argument name.
   * @return -1 if there is no match, or the index of the matching *formal* if there is exactly one match.
   * @throws EvalException if there is more than one partial match
   */
  private static int findPartialMatch(String actualName, String[] formalNames, boolean[] matchedFormals) {
    int match = -1;
    for (int i = 0; i < formalNames.length; i++) {
      if(!matchedFormals[i]) {
        String formalName = formalNames[i];
        if(formalName.equals("...")) {
          break;
        }
        if(formalName.startsWith(actualName)) {
          if(match != -1) {
            throw new EvalException(String.format("Provided argument '%s' matches multiple named formal arguments",
                actualName));
          }
          match = i;
        }
      }
    }
    return match;
  }

  public boolean isFormalElipses(int i) {
    return formalElipses == i;
  }
}
