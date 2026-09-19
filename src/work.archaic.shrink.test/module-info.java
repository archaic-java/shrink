module work.archaic.shrink.test {
  requires work.archaic.shrink;
  requires work.archaic.shrink.compiler;
  requires work.archaic.service.catalog;
  requires jakarta.json;
  exports work.archaic.shrink.test;
  opens work.archaic.shrink.test to work.archaic.minau;
}
