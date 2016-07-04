package org.renjin.compiler;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.renjin.compiler.cfg.*;
import org.renjin.compiler.ir.TypeSet;
import org.renjin.compiler.ir.ValueBounds;
import org.renjin.compiler.ir.ssa.PhiFunction;
import org.renjin.compiler.ir.ssa.SsaVariable;
import org.renjin.compiler.ir.tac.RuntimeState;
import org.renjin.compiler.ir.tac.expressions.*;
import org.renjin.compiler.ir.tac.statements.Assignment;
import org.renjin.compiler.ir.tac.statements.IfStatement;
import org.renjin.compiler.ir.tac.statements.Statement;
import org.renjin.sexp.Function;
import org.renjin.sexp.Symbol;

import java.util.*;

/**
 * Propagates constant values <strong>and</strong> type properties through a CFG in 
 * order to further specialize the CFG.
 * 
 * <p>This class uses <em>Sparse conditional constant propagation</em> to propagate 
 * both constant values as well as type information like vector type, length, presence of NAs,
 * etc, throughout as much of the code as possible.
 * 
 * <p>The implementation is implemented based on the paper Wegman, Mark N. and Zadeck, F. Kenneth. 
 * "Constant Propagation with Conditional Branches." ACM Transactions on Programming Languages and Systems,
 * 13(2), April 1991, pages 181-210.</a>
 * 
 * @see <a href="https://www.cs.utexas.edu/users/lin/cs380c/wegman.pdf">Constant Propagation with Conditional Branches</a>
 * @see <a href="http://lampwww.epfl.ch/resources/lamp/teaching/advancedCompiler/2005/slides/05-UsingSSA_CP-1on1.pdf">
 *   Slides on Constant Propagation on SSA form</a>
 */
public class TypeSolver {
  
  // http://lampwww.epfl.ch/resources/lamp/teaching/advancedCompiler/2005/slides/05-UsingSSA_CP-1on1.pdf
  // https://www.cs.utexas.edu/users/lin/cs380c/wegman.pdf

  private final ControlFlowGraph cfg;
  private UseDefMap useDefMap;

  private final ArrayDeque<FlowEdge> flowWorkList = new ArrayDeque<>();
  private final ArrayDeque<SsaEdge> ssaWorkList = new ArrayDeque<>();

  private static final ValueBounds TOP = null;
  
  private final Map<Expression, ValueBounds> variableBounds = Maps.newHashMap();
  
  private final Map<IfStatement, ValueBounds> conditionalBounds = Maps.newHashMap();
  
  private final Set<FlowEdge> executable = Sets.newHashSet();
  
  public TypeSolver(ControlFlowGraph cfg, UseDefMap useDefMap) {
    this.cfg = cfg;
    this.useDefMap = useDefMap;
  }

  public boolean isDefined(LValue variable) {
    return useDefMap.isDefined(variable);
  }
  
  public boolean isUsed(Assignment assignment) {
    return isUsed(assignment.getLHS());
  }

  public boolean isUsed(LValue variable) {
    return useDefMap.isUsed(variable);
  }

  
  public Map<LValue, ValueBounds> getVariables() {
    Map<LValue, ValueBounds> map = new HashMap<>();
    for (LValue variable : useDefMap.getUsedVariables()) {
      map.put(variable, variableBounds.get(variable));
    }
    return map;
  }

  public void execute() {

    executable.clear();
    conditionalBounds.clear();
    flowWorkList.clear();
    ssaWorkList.clear();
    
    // (1) Initialize the flowWorkList to contain the edges exiting the start node of the program. 
    //     The SSA Work list is initially empty
    for (FlowEdge flowEdge : cfg.getEntry().getOutgoing()) {
      if(flowEdge.getSuccessor() != cfg.getExit()) {
        flowWorkList.add(flowEdge);
      }
    }

    // (2) Halt execution when both worklists become empty. Execution
    //     may proceed by processing items from either worklist

    while(! (flowWorkList.isEmpty() && ssaWorkList.isEmpty())) {


      while(!flowWorkList.isEmpty()) {

        // (3) If the item is a program flow edg from the flowWorkList, then 
        //     examine the Executable falg of that edge. If the ExecutableFlag is true
        //     do nothing; otherwise:

        FlowEdge edge = flowWorkList.pop();
        if(!executable.contains(edge)) {
          BasicBlock node = edge.getSuccessor();
          
          // (a) mark the executable node as true
          executable.add(edge);

          // (b) Perform Visit-phi for all of the phi functions at the destination node

          for (Assignment phiAssignment : node.phiAssignments()) {
            visitPhi(phiAssignment);
          }

          // (c) If only one of the ExecutableFlags associated with the incoming
          //     program flow graph edges is true (i.e. this the first time this
          //     node has been evaluated), then perform VisitExpression for all expressions
          //     in this node.

          if(countIncomingExecutableEdges(node) == 1) {
            for (Statement statement : node.getStatements()) {
              if(statement.getRHS() != NullExpression.INSTANCE &&
                  !(statement.getRHS() instanceof PhiFunction)) {
                visitExpression(node, statement);
              }
            }
          }

          // (d) If then node only contains one outgoing flow edge, add that edge to the
          //     flowWorkList

          if(node.getOutgoing().size() == 1) {
            flowWorkList.addAll(node.getOutgoing());
          }
        }
      }


      while(!ssaWorkList.isEmpty()) {

        SsaEdge edge = ssaWorkList.pop();

        // (4) If the item is an SSA edge from the SSAWorkList and the destination of that 
        //     edge is a phi-function, perform visit-phi

        // (5) If the item is an SSA edge from the SSA Work list and the destination of that
        //     edge is an expression, then examine ExecutableFlags for the program flow edges
        //     reaching that node. If any of them are true, perform VisitExpression. 
        //     Otherwise do nothing.
        
        if(edge.isPhiFunction()) {
          visitPhi((Assignment) edge.getDestinationStatement());
        
        } else if(countIncomingExecutableEdges( edge.getDestinationNode() ) > 0) {
          visitExpression(edge.getDestinationNode(), edge.getDestinationStatement());
        }
      }
    }
  }

  private void visitPhi(Assignment assignment) {
    
    PhiFunction phiFunction = (PhiFunction) assignment.getRHS();
    
    // The LatticeCells for each operand of the phi-function are defined
    // on the basis of the ExecutableFlag for the corresponding program flow edge.

    // executable: The LatticeCell has the same value as the LatticeCell at the definition
    // end of the of the SSA edge
    
    // non-executable: The LatticeCell has the value TOP

    List<ValueBounds> boundSet = new ArrayList<>();
    for (int i = 0; i < phiFunction.getIncomingEdges().size(); i++) {
      FlowEdge incomingEdge = phiFunction.getIncomingEdges().get(i);
      if(executable.contains(incomingEdge)) {
        Variable ssaVariable = phiFunction.getArgument(i);
        ValueBounds value = variableBounds.get(ssaVariable);
        if(value != TOP) {
          boundSet.add(value);
        }
      }
    }
    
    if(!boundSet.isEmpty()) {

      ValueBounds newBounds = ValueBounds.union(boundSet);
      ValueBounds oldBounds = variableBounds.put(assignment.getLHS(), newBounds);
      assignment.getLHS().update(newBounds);
    }
  }
  
  public void dumpBounds() {
    for (Expression expression : variableBounds.keySet()) {
      if(expression instanceof LValue) {
        System.out.println(expression + " => " + variableBounds.get(expression));
      }
    }
  }
  
  private void visitExpression(BasicBlock block, Statement statement) {
    
    
    // Evaluate the expression obtaining the values of the operands from the LatticeCells
    // where they are defined and using the expression rules in Section 2.2


    Expression expression = statement.getRHS();
    ValueBounds newBounds = expression.updateTypeBounds(variableBounds);

    // If this changes the value of the LatticeCell of the output expression, do the following:

    // (1) If the expression is part of an assignment node, add to the SSA worklist all
    //     SSA edges starting at the definition for that node.

    if(statement instanceof Assignment) {
      Assignment assignment = (Assignment) statement;
      ValueBounds oldBounds = variableBounds.get(assignment.getLHS());
      
      if(!Objects.equals(oldBounds, newBounds)) {
        assignment.getLHS().update(newBounds);
        variableBounds.put(assignment.getLHS(), newBounds);
        
        Collection<SsaEdge> outgoingEdges = useDefMap.getSsaEdges(assignment.getLHS());

        ssaWorkList.addAll(outgoingEdges);
      }
    }

    // (2) If the expression controls a conditional branch, some outgoing flow graph
    //     edges must be added to the Flow Work List. If the LatticeCell has  value BOT,
    //     all exit edges must be added to the FlowWorkList. If the value is CONSTANT, 
    //     only the flow graph edge executed as the result of the branch is added
    //     to the FlowWorkList.
    
    if(statement instanceof IfStatement) {
      IfStatement conditional = (IfStatement) statement;
      ValueBounds oldBounds = conditionalBounds.get(conditional);

      if(!Objects.equals(oldBounds, newBounds)) {

        conditionalBounds.put(conditional, newBounds);

        flowWorkList.addAll(block.getOutgoing());
      }
    }
  }

  public int countIncomingExecutableEdges(BasicBlock block) {
    int count = 0;
    for (FlowEdge flowEdge : block.getIncoming()) {
      if(executable.contains(flowEdge)) {
        count++;
      }
    }
    return count;
  }

  /**
   * We have built the ControlFlowGraph using assumptions about to which functions
   * the symbols were bound. We now have to verify that none of the assignments in this CFG
   * violate these assumptions.
   */
  public void verifyFunctionAssumptions(RuntimeState runtimeState) {

    Map<Symbol, Function> resolvedFunctions = runtimeState.getResolvedFunctions();

    for (Map.Entry<Expression, ValueBounds> entry : variableBounds.entrySet()) {
      if(entry.getKey() instanceof SsaVariable) {
        SsaVariable lhs = (SsaVariable) entry.getKey();
        if(lhs.getInner() instanceof EnvironmentVariable) {
          EnvironmentVariable variable = (EnvironmentVariable) lhs.getInner();
          if(resolvedFunctions.containsKey(variable.getName())) {

            // We've found an assignment in the form
            //    sum <- something()

            // Where "sum" is any symbol that was used in a function call in the original
            // R expression that we are compiling.

            Function resolvedFunction = resolvedFunctions.get(variable.getName());
            checkPotentialFunctionAssignment(variable, entry.getValue(), resolvedFunction);
          }
        }
      }
    }
  }

  private void checkPotentialFunctionAssignment(EnvironmentVariable variable, 
                                                ValueBounds bounds, Function resolvedFunction) {
    // If we are assigning to a variable that was involved in function
    // resolution, for example :
    //   sum <- mean
    //   x <- sum(1:10)
    //
    // Then we need to either be certain that it is not being assigned 
    // a function (because bindings to non-functions are ignored during 
    // function lookup), or that it's assigned a constant value equal to 
    // what we resolved.

    
    // If we are certain that this variable is NOT being assigned a function,
    // then we have nothing to worry about. When looking up a function, bindings
    // to NON-FUNCTIONS are just ignored in R.
    
    if( (bounds.getTypeSet() & TypeSet.FUNCTION) == 0) {
      return;
    }
    
    // Otherwise, check to see if the value being assigned matches the value to which
    // the function was resolved at compile time. In this case, the assignment does not 
    // change anything.
    if(bounds.isConstant()) {
      if(bounds.getConstantValue() == resolvedFunction) {
        return;
      }
    }
    
    // Otherwise, this will change the execution of the R expression in way that we did not consider
    // during the compilation, so we need to bail.
    throw new NotCompilableException(variable.getName(), "change to function definition");
  }
}
