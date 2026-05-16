package io.github.fncontroloption.zig;

import com.intellij.testFramework.ParsingTestCase;

public class ZigParsingTest extends ParsingTestCase {

  public ZigParsingTest() {
    super("", "zig", new ZigParserDefinition());
  }

  public void testParsingTestData() {
    doTest(true);
  }

  /**
   * @return path to test data file directory relative to root of this module.
   */
  @Override
  protected String getTestDataPath() {
    return "src/test/zig";
  }

  @Override
  protected boolean includeRanges() {
    return true;
  }

}
