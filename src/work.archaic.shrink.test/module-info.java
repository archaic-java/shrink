module work.archaic.shrink.test {
  requires work.archaic.shrink;
  requires work.archaic.service.catalog;
  requires jakarta.json;
  uses work.archaic.service.compiler.v01.CompilerAdapter;
  uses work.archaic.service.logging.v02.Diagnostics;
  uses work.archaic.service.logging.v02.Log;
  exports work.archaic.shrink.test to work.archaic.minau;
}
