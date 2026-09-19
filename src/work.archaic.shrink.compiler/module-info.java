/** Reusable compiler adapter for JDK 25 and newer, using the running JDK's parser. */
module work.archaic.shrink.compiler {
  requires java.compiler;
  requires jdk.compiler;
  requires transitive work.archaic.service.catalog;
  exports work.archaic.shrink.compiler;
}
