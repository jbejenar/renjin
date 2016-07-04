/*
 * R : A Computer Language for Statistical Data Analysis
 * Copyright (C) 1995, 1996  Robert Gentleman and Ross Ihaka
 * Copyright (C) 1997--2008  The R Development Core Team
 * Copyright (C) 2003, 2004  The R Foundation
 * Copyright (C) 2010 bedatadriven
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.renjin.primitives.special;

import org.renjin.compiler.CompiledLoopBody;
import org.renjin.compiler.NotCompilableException;
import org.renjin.compiler.TypeSolver;
import org.renjin.compiler.cfg.ControlFlowGraph;
import org.renjin.compiler.cfg.DominanceTree;
import org.renjin.compiler.cfg.UseDefMap;
import org.renjin.compiler.codegen.ByteCodeEmitter;
import org.renjin.compiler.ir.exception.InvalidSyntaxException;
import org.renjin.compiler.ir.ssa.SsaTransformer;
import org.renjin.compiler.ir.tac.IRBody;
import org.renjin.compiler.ir.tac.IRBodyBuilder;
import org.renjin.compiler.ir.tac.RuntimeState;
import org.renjin.eval.Context;
import org.renjin.eval.EvalException;
import org.renjin.primitives.Deparse;
import org.renjin.sexp.*;


public class ForFunction extends SpecialFunction {

  public static boolean COMPILE_LOOPS = Boolean.getBoolean("renjin.compile.loops");
  
  private static final int COMPILE_THRESHOLD = 200;
  private static final int WARMUP_ITERATIONS = 5;

  public ForFunction() {
    super("for");
  }

  @Override
  public SEXP apply(Context context, Environment rho, FunctionCall call, PairList _args_unused) {

    PairList args = call.getArguments();
    Symbol symbol = args.getElementAsSEXP(0);
    SEXP elementsExp = context.evaluate(args.getElementAsSEXP(1), rho);
    if(!(elementsExp instanceof Vector)) {
      throw new EvalException("invalid for() loop sequence");
    }
    Vector elements = (Vector) elementsExp;
    SEXP statement = args.getElementAsSEXP(2);

    // Interpret the loop
    boolean compilationFailed = false;
    for (int i = 0; i != elements.length(); ++i) {
      try {

        if(COMPILE_LOOPS && i >= WARMUP_ITERATIONS && elements.length() > COMPILE_THRESHOLD && 
            !compilationFailed) {
          
          if(tryCompileAndRun(context, rho, call, elements, i)) {
            break;
          } else {
            compilationFailed = true;
          }
        }

        rho.setVariable(symbol, elements.getElementAsSEXP(i));
        context.evaluate(statement, rho);
      } catch (BreakException e) {
        break;
      } catch (NextException e) {
        // next iteration
      }
    }

    context.setInvisibleFlag();
    return Null.INSTANCE;
  }

  private boolean tryCompileAndRun(Context context, Environment rho, FunctionCall call, Vector elements, int i) {

    CompiledLoopBody compiledBody = null;

    try {

      RuntimeState runtimeState = new RuntimeState(context, rho);
      IRBodyBuilder builder = new IRBodyBuilder(runtimeState);
      IRBody body = builder.buildLoopBody(call, elements);

      ControlFlowGraph cfg = new ControlFlowGraph(body);

      DominanceTree dTree = new DominanceTree(cfg);
      SsaTransformer ssaTransformer = new SsaTransformer(cfg, dTree);
      ssaTransformer.transform();

      UseDefMap useDefMap = new UseDefMap(cfg);
      TypeSolver types = new TypeSolver(cfg, useDefMap);
      types.execute();
      
      types.verifyFunctionAssumptions(runtimeState);

      ssaTransformer.removePhiFunctions(types);
      
      ByteCodeEmitter emitter = new ByteCodeEmitter(cfg, types);
      compiledBody = emitter.compileLoopBody().newInstance();

    } catch (NotCompilableException e) {
      context.warn("Could not compile loop with %d iterations because: " + format(context, e));
      return false;

    } catch (InvalidSyntaxException e) {
      throw new EvalException(e.getMessage());
      
    } catch (Exception e) {
      throw new EvalException("Exception compiling loop: " + e.getMessage(), e);
    }

    compiledBody.run(context, rho, elements, i);

    return true;
  }

  private String format(Context context, NotCompilableException e) {
    StringBuilder s = new StringBuilder();
    while(e != null) {
      if(s.length() > 0) {
        s.append(" > ");
      }
      if(e.getSexp() != null) {
        s.append(Deparse.deparseExp(context, e.getSexp()));
      }
      if(e.getMessage() != null) {
        s.append(": ").append(e.getMessage());
      }
      e = e.getCause();
    }
    return s.toString();
  }
}
