package org.renjin.compiler.pipeline.fusion;

import jdk.nashorn.internal.codegen.types.Type;
import org.renjin.compiler.JitClassLoader;
import org.renjin.compiler.pipeline.ComputeMethod;
import org.renjin.compiler.pipeline.VectorPipeliner;
import org.renjin.compiler.pipeline.fusion.kernel.CompiledKernel;
import org.renjin.compiler.pipeline.fusion.kernel.LoopKernel;
import org.renjin.compiler.pipeline.fusion.node.LoopNode;
import org.renjin.repackaged.asm.ClassVisitor;
import org.renjin.repackaged.asm.ClassWriter;
import org.renjin.repackaged.asm.MethodVisitor;
import org.renjin.repackaged.asm.tree.MethodNode;
import org.renjin.repackaged.asm.util.Textifier;
import org.renjin.repackaged.asm.util.TraceMethodVisitor;
import org.renjin.repackaged.guava.io.Files;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.renjin.repackaged.asm.Opcodes.*;

/**
 * Compiles a graph of deferred calculations into a single method.
 *
 * <p>This class is used to efficiently compute complex vector operations like
 *
 * <pre>
 * mean(acos(x*x)))
 * </pre>
 *
 * Instead of computing each of the operations in sequence we emit a new
 * class that looks something like this:
 *
 * <pre>
 *   double[] x_arr = x.toDoubleArrayUnsafe();
 *   double sum;
 *   for(int i=0;i!=x.length;++i) {
 *    x_i = x[i];
 *    sum += Math.acos(x_i*x_i);
 *   }
 *   return sum / x.length()
 * </pre>
 *
 * <p>This is identical to the mean function defined in {@link org.renjin.primitives.Summary#mean(org.renjin.sexp.Vector)}
 * but we replace the virtual invocations to DoubleArrayVector.getElementAsDouble() or
 * R$primitive$acos$deferred_d.getElementAsDouble() with direct array references or static calls that the
 * JVM can be expected to quickly inline. (I would also think
 * the jvm should be capable of inlining virtual invocations in loops of 25m + iterations, but it doesn't seem
 * to happen in practice.
 *
 * <p>Because we totally inline getElementAsDouble,
 * we need a new Jitted class for each combination of operators and vector classes.</p>
 */
public class LoopKernelCompiler {
  
  public static final boolean DEBUG = System.getProperty("renjin.vp.jit.debug") != null;

  private static final String KERNEL_INTERFACE = Type.getInternalName(CompiledKernel.class);
  
  private String className;
  private ClassVisitor cv;

  public LoopKernelCompiler() {
    className = "Jit" + System.identityHashCode(this);
  }

  public CompiledKernel compile(LoopKernel kernel, LoopNode[] operands)  {
    long startTime = System.nanoTime();
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    cv = cw;
    cv.visit(V1_6, ACC_PUBLIC + ACC_SUPER, className, null, "java/lang/Object", new String[]{ KERNEL_INTERFACE });

    writeConstructor();
    writeComputeDebug(kernel, operands);

    cv.visitEnd();

    byte[] classBytes = cw.toByteArray();
    long compileTime = System.nanoTime() - startTime;

    Class<CompiledKernel> jitClass = JitClassLoader.defineClass(CompiledKernel.class, className, classBytes);

    long loadTime = System.nanoTime() - startTime - compileTime;

    if(VectorPipeliner.DEBUG) {
     // System.out.println(className + ": " + kernel.jitKey());
      System.out.println(className + ": compile: " + (compileTime/1e6) + "ms");
      System.out.println(className + ": load: " + (loadTime / 1e6) + "ms");
      if(DEBUG) {
        try {
          File classFile = File.createTempFile("Specialization", ".class");
          Files.write(classBytes, classFile);
          System.out.println("Wrote class file to " + classFile);
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }

    try {
      return jitClass.newInstance();
    } catch (Exception e) {
      throw new RuntimeException("Could not invoke jitted computation", e);
    }
  }

  private void writeConstructor() {
    MethodVisitor mv = cv.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    mv.visitInsn(RETURN);
    mv.visitMaxs(1, 1);
    mv.visitEnd();
  }

  private void writeCompute(LoopKernel kernel, LoopNode[] operands) {
    String typeDescriptor = "([Lorg/renjin/sexp/Vector;)[D";

    MethodVisitor mv = cv.visitMethod(ACC_PUBLIC, "compute", typeDescriptor, null, null);
//
//    mv = new CheckMethodAdapter(ACC_PUBLIC, "compute", typeDescriptor, mv, new HashMap());
//    mv.visitCode();

    ComputeMethod methodContext = new ComputeMethod(mv);

    kernel.compute(methodContext, operands);

    mv.visitMaxs(1, methodContext.getMaxLocals());
    mv.visitEnd();
  }

  private void writeComputeDebug(LoopKernel kernel, LoopNode[] operands) {

    MethodNode mv = new MethodNode(ACC_PUBLIC, "compute", "([Lorg/renjin/sexp/Vector;)[D", null, null);
    mv.visitCode();

    ComputeMethod methodContext = new ComputeMethod(mv);

    kernel.compute(methodContext, operands);

    mv.visitMaxs(1, methodContext.getMaxLocals());
    mv.visitEnd();

    try {
      mv.accept(cv);
      
      System.out.println(toString(mv));
      
    } catch (Exception e) {
      throw new RuntimeException("Toxic bytecode generated: " + toString(mv), e);
    }
  }


  private String toString(MethodNode methodNode) {
    try {
      Textifier p = new Textifier();
      methodNode.accept(new TraceMethodVisitor(p));
      StringWriter sw = new StringWriter();
      try (PrintWriter pw = new PrintWriter(sw)) {
        p.print(pw);
      }
      return sw.toString();
    } catch (Exception e) {
      return "<Exception generating bytecode: " + e.getClass().getName() + ": " + e.getMessage() + ">";
    }
  }

}