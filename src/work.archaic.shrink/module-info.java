/** A small stdio language server for Java syntax diagnostics. */
module work.archaic.shrink {
  requires work.archaic.shrink.compiler;
  requires work.archaic.service.catalog;
  requires jakarta.json;
  requires java.logging;
  uses jakarta.json.spi.JsonProvider;
  exports work.archaic.shrink.protocol to work.archaic.shrink.test;
  exports work.archaic.shrink.server to work.archaic.shrink.test;
}
