/** Reusable compiler adapter for JDK 25 and newer, using the running JDK's parser. */
module work.archaic.shrink.compiler {
  requires java.compiler;
  requires jdk.compiler;
  requires work.archaic.service.catalog;
  provides work.archaic.service.compiler.v01.CompilerAdapter
      with work.archaic.shrink.compiler.JavacCompiler;
}
