/** Reusable JDK 25 implementation of the catalog's synchronous compiler contract. */
module work.archaic.shrink.compiler {
  requires java.compiler;
  requires jdk.compiler;
  requires transitive work.archaic.service.catalog;
  exports work.archaic.shrink.compiler;
}
