/** A small stdio language server for Java syntax diagnostics. */
module work.archaic.shrink {
  requires work.archaic.service.catalog;
  requires jakarta.json;
  uses work.archaic.service.compiler.v01.CompilerAdapter;
  uses work.archaic.service.logging.v02.Diagnostics;
  uses work.archaic.service.logging.v02.Log;
  uses jakarta.json.spi.JsonProvider;
  exports work.archaic.shrink.protocol to work.archaic.shrink.test;
  exports work.archaic.shrink.server to work.archaic.shrink.test;
}
