package io.github.fncontroloption.zj;

import com.intellij.testFramework.ParsingTestCase;

public class ZigParsingTest extends ParsingTestCase {

  public ZigParsingTest() {
    super("", "zig", new ZigParserDefinition());
  }

  public void testAllKt() {
    doTest(true);
  }

  /*
  public void testAll() {
    doTest(true);
  }

  public void testErrors() {
    doTest(true);
  }
  */

  public void testErrors2() {
    doTest(true);
  }

  public void testParsingTestData() {
    doTest(true);
  }

  /*
  public void testMultilineStringLiteral() {
    doTest(true);
  }

  public void testContainer() {
    doTest(true);
  }

  public void testGroupedExpression() {
    doTest(true);
  }
  */

  /**
   * @return path to test data file directory relative to root of this module.
   */
  @Override
  protected String getTestDataPath() {
    return "src/test/parsingTestData";
  }

  @Override
  protected boolean includeRanges() {
    return true;
  }

}
