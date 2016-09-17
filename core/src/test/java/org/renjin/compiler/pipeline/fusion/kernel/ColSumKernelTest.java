package org.renjin.compiler.pipeline.fusion.kernel;

import org.junit.Test;
import org.renjin.compiler.pipeline.DeferredGraph;
import org.renjin.compiler.pipeline.node.FusedNode;
import org.renjin.primitives.R$primitive$$times$deferred_dd;
import org.renjin.primitives.R$primitive$$times$deferred_ii;
import org.renjin.primitives.matrix.DeferredColSums;
import org.renjin.primitives.sequence.IntSequence;
import org.renjin.sexp.*;

import java.nio.IntBuffer;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;

public class ColSumKernelTest {

  @Test
  public void simpleTest() throws Exception {


    //        [,1] [,2] [,3]
    //  [1,]    1    5    9
    //  [2,]    2    6   10
    //  [3,]    3    7   11
    //  [4,]    4    8   12
    DeferredColSums colSums = new DeferredColSums(new IntSequence(1, 1, 12), 3, false, AttributeMap.EMPTY);

    double[] resultArray = compute(colSums);

    System.out.println(Arrays.toString(resultArray));

    assertArrayEquals(new double[]{10d, 26d, 42d}, resultArray, 0.1);
  }

  @Test
  public void missingValues() throws Exception {


    //        [,1] [,2] [,3]
    //  [1,]    1    5   NA
    //  [2,]    2    6   10

    IntArrayVector matrix = new IntArrayVector(1, 2, 5, 6, IntVector.NA, 10);

    DeferredColSums colSums = new DeferredColSums(matrix, 3, false, AttributeMap.EMPTY);

    double[] resultArray = compute(colSums);

    System.out.println(Arrays.toString(resultArray));

    assertArrayEquals(new double[]{3d, 11d, 10d}, resultArray, 0.1);
  }


  @Test
  public void singleColumn() throws Exception {

    IntBufferVector x = new IntBufferVector(IntBuffer.wrap(new int[] { 1, 2, 3 }), 3);
    IntBufferVector y = new IntBufferVector(IntBuffer.wrap(new int[] { 4, 5, 6 }), 3);
    AtomicVector times = new R$primitive$$times$deferred_ii(x, y, AttributeMap.EMPTY);

    DeferredColSums colSums = new DeferredColSums(times, 1, false, AttributeMap.EMPTY);

    double[] resultArray = compute(colSums);

    System.out.println(Arrays.toString(resultArray));

    assertArrayEquals(new double[]{32d }, resultArray, 0.1);
  }


  @Test
  public void intOperandsToDoubleBinaryFunction() throws Exception {

    IntBufferVector x = new IntBufferVector(IntBuffer.wrap(new int[] { IntVector.NA, 2, 3 }), 3);
    IntBufferVector y = new IntBufferVector(IntBuffer.wrap(new int[] { 4, 5, IntVector.NA }), 3);

    //DoubleArrayVector y = new DoubleArrayVector(4d, 5d, 6d);
    AtomicVector times = new R$primitive$$times$deferred_dd(x, y, AttributeMap.EMPTY);

    DeferredColSums colSums = new DeferredColSums(times, 1, false, AttributeMap.EMPTY);

    double[] resultArray = compute(colSums);

    System.out.println(Arrays.toString(resultArray));

    assertArrayEquals(new double[] { 10 }, resultArray, 0.1);
  }

  @Test
  public void intDoubleOperandsToDoubleBinaryFunction() throws Exception {

    IntBufferVector x = new IntBufferVector(IntBuffer.wrap(new int[] { IntVector.NA, 2, 3 }), 3);
    DoubleArrayVector y = new DoubleArrayVector(4d, 5d, 0d);
    
    AtomicVector times = new R$primitive$$times$deferred_dd(x, y, AttributeMap.EMPTY);

    DeferredColSums colSums = new DeferredColSums(times, 1, false, AttributeMap.EMPTY);

    double[] resultArray = compute(colSums);
    
    System.out.println(Arrays.toString(resultArray));

    assertArrayEquals(new double[]{10 }, resultArray, 0.1);
  }



  private double[] compute(DeferredColSums colSums) {
    DeferredGraph graph = new DeferredGraph(colSums);
    graph.optimize();
    graph.fuse();
    FusedNode root = (FusedNode) graph.getRoot();
    root.call();

    return root.getVector().toDoubleArray();
  }
}