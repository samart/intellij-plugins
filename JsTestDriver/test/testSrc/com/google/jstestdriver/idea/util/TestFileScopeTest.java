package com.google.jstestdriver.idea.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * @author Sergey Simonchik
 */
public class TestFileScopeTest {

  @Test
  public void testSerialization() throws Exception {
    String testCaseName = "Difference";
    String testMethodName = "test One value on left and other value on right, gives empty object";
    Map<String, Set<String>> methodByCaseMap = Collections.singletonMap(testCaseName,
                                                                        Collections.singleton(testMethodName));
    TestFileScope testFileScope = TestFileScope.customScope(methodByCaseMap);
    String serialized = testFileScope.serialize();
    TestFileScope another = TestFileScope.deserialize(serialized);
    Assert.assertEquals(testFileScope, another);
  }

}
