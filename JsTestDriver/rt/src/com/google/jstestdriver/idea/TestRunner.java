/*
 * Copyright 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.jstestdriver.idea;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.multibindings.Multibinder;
import com.google.jstestdriver.*;
import com.google.jstestdriver.config.Configuration;
import com.google.jstestdriver.config.ParsedConfiguration;
import com.google.jstestdriver.embedded.JsTestDriverBuilder;
import com.google.jstestdriver.hooks.PluginInitializer;
import com.google.jstestdriver.hooks.ResourcePreProcessor;
import com.google.jstestdriver.hooks.TestListener;
import com.google.jstestdriver.idea.coverage.CoverageReport;
import com.google.jstestdriver.idea.coverage.CoverageSerializationUtils;
import com.google.jstestdriver.idea.coverage.CoverageSession;
import com.google.jstestdriver.idea.execution.tree.OutputManager;
import com.google.jstestdriver.idea.server.JstdServerFetchResult;
import com.google.jstestdriver.idea.server.JstdServerUtilsRt;
import com.google.jstestdriver.idea.util.EscapeUtils;
import com.google.jstestdriver.idea.util.JstdConfigParsingUtils;
import com.google.jstestdriver.output.TestResultHolder;
import com.google.jstestdriver.runner.RunnerMode;
import com.google.jstestdriver.util.ManifestLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joda.time.DateTime;

import java.io.*;
import java.util.*;

/**
 * Run JSTD in its own process, and stream messages via a socket to a server that lives in the IDEA process,
 * which will update the UI with our results.
 *
 * @author alexeagle@google.com (Alex Eagle)
 */
public class TestRunner {

  private static final String COVERAGE_MODULE_NAME = "com.google.jstestdriver.coverage.CoverageModule";

  public enum ParameterKey {
    SERVER_URL,
    CONFIG_FILE,
    TEST_CASE,
    TEST_METHOD,
    COVERAGE_OUTPUT_FILE,
    COVERAGE_EXCLUDED_PATHS
  }

  private final Settings mySettings;
  private final OutputManager myOutputManager;
  private final CoverageSession myCoverageSession;

  public TestRunner(@NotNull Settings settings, @NotNull OutputManager outputManager) {
    mySettings = settings;
    myOutputManager = outputManager;
    File ideCoverageFile = mySettings.getIdeCoverageFile();
    if (ideCoverageFile != null) {
      myCoverageSession = new CoverageSession(ideCoverageFile);
    } else {
      myCoverageSession = null;
    }
  }

  public void executeAll() throws InterruptedException {
    for (File config : mySettings.getConfigFiles()) {
      executeConfig(config);
    }
    if (myCoverageSession != null) {
      myCoverageSession.finish();
    }
  }

  public void executeConfig(@NotNull File config) throws InterruptedException {
    try {
      unsafeExecuteConfig(config);
    } catch (Exception e) {
      myOutputManager.printThrowable(e);
    }
  }

  private void unsafeExecuteConfig(@NotNull File config) {
    final String testCaseName;
    if (!mySettings.getTestCaseName().isEmpty()) {
      if (!mySettings.getTestMethodName().isEmpty()) {
        testCaseName = mySettings.getTestCaseName() + "." + mySettings.getTestMethodName();
      } else {
        testCaseName = mySettings.getTestCaseName();
      }
    } else {
      testCaseName = "all";
    }
    executeTestCase(config, testCaseName);
  }

  private void executeTestCase(@NotNull File config, @NotNull String testCaseName) {
    PrintStream nullSystemOut = new PrintStream(new NullOutputStream());
    try {
      System.setOut(nullSystemOut);
      runTests(config, new String[]{"--reset", "--dryRunFor", testCaseName}, true);
      runTests(config, new String[]{"--tests", testCaseName}, false);
    } finally {
      nullSystemOut.close();
      System.setOut(myOutputManager.getSystemOutStream());
    }
  }

  @SuppressWarnings("deprecation")
  private void runTests(@NotNull final File configFile, @NotNull String[] extraArgs, boolean dryRun) {
    JsTestDriverBuilder builder = new JsTestDriverBuilder();

    final ParsedConfiguration parsedConfiguration = JstdConfigParsingUtils.parseConfiguration(configFile);
    builder.setDefaultConfiguration(parsedConfiguration);
    builder.withPluginInitializer(new PluginInitializer() {
      @Override
      public Module initializeModule(Flags flags, Configuration config) {
        return new AbstractModule() {
          @Override
          public void configure() {
            Multibinder<TestListener> testListeners = Multibinder.newSetBinder(binder(), TestListener.class);
            testListeners.addBinding().to(TestResultHolder.class);
            testListeners.addBinding().toInstance(new IdeaTestListener(
              myOutputManager,
              configFile,
              parsedConfiguration.getBasePaths()
            ));
          }
        };
      }
    });

    builder.setRunnerMode(RunnerMode.QUIET);
    builder.setServer(mySettings.getServerUrl());

    List<String> flagArgs = Lists.newArrayList("--captureConsole", "--server", mySettings.getServerUrl());
    flagArgs.addAll(Arrays.asList(extraArgs));
    List<String> coverageExcludedFiles = null;
    File emptyOutputDir = null;
    boolean runCoverage = false;
    if (myCoverageSession != null && !dryRun) {
      emptyOutputDir = createTempDir();
      if (emptyOutputDir != null) {
        flagArgs.add("--testOutput");
        flagArgs.add(emptyOutputDir.getAbsolutePath());
        List<String> testPaths = getTestFilePaths(parsedConfiguration);
        coverageExcludedFiles = Lists.newArrayList(testPaths);
        coverageExcludedFiles.addAll(mySettings.getFilesExcludedFromCoverage());
        PluginInitializer coverageInitializer = getCoverageInitializer(coverageExcludedFiles);
        if (coverageInitializer != null) {
          wipeCoveragePlugin(parsedConfiguration);
          builder.withPluginInitializer(coverageInitializer);
          builder.withPluginInitializer(new DependenciesTouchFix());
          runCoverage = true;
        }
      }
    }

    builder.setFlags(toStringArray(flagArgs));
    JsTestDriver jstd = builder.build();
    jstd.runConfiguration();
    if (runCoverage) {
      File[] coverageReportFiles = emptyOutputDir.listFiles(new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
          return name.endsWith("-coverage.dat");
        }
      });
      if (coverageReportFiles != null && coverageReportFiles.length == 1) {
        try {
          CoverageReport coverageReport = CoverageSerializationUtils.readLCOV(coverageReportFiles[0]);
          for (String excludedPath : coverageExcludedFiles) {
            coverageReport.clearReportByFilePath(excludedPath);
          }
          myCoverageSession.mergeReport(coverageReport);
        }
        catch (Exception e) {
          myOutputManager.printThrowable(e);
        }
      }
    }
  }

  private static void wipeCoveragePlugin(@NotNull ParsedConfiguration configuration) {
    ManifestLoader manifestLoader = new ManifestLoader();
    Iterator<Plugin> iterator = configuration.getPlugins().iterator();
    while (iterator.hasNext()) {
      Plugin plugin = iterator.next();
      if (isCoveragePlugin(plugin, manifestLoader)) {
        iterator.remove();
      }
    }
  }

  private static boolean isCoveragePlugin(Plugin plugin, ManifestLoader loader) {
    try {
      String moduleName = plugin.getModuleName(loader);
      if (COVERAGE_MODULE_NAME.equals(moduleName)) {
        return true;
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  @NotNull
  private static List<String> getTestFilePaths(@NotNull ParsedConfiguration parsedConfiguration) {
    Configuration resolvedConfiguration = JstdConfigParsingUtils.resolveConfiguration(parsedConfiguration);
    List<FileInfo> testFileInfos = resolvedConfiguration.getTests();
    List<String> paths = Lists.newArrayListWithExpectedSize(testFileInfos.size());
    for (FileInfo fileInfo : testFileInfos) {
      paths.add(fileInfo.getFilePath());
    }
    return paths;
  }

  @Nullable
  private static PluginInitializer getCoverageInitializer(List<String> filesExcludedFromCoverage) {
    File[] coverageJarFiles = new File(".").listFiles(new FilenameFilter() {
      @Override
      public boolean accept(File dir, String name) {
        return name.startsWith("coverage") && name.endsWith(".jar");
      }
    });
    if (coverageJarFiles != null && coverageJarFiles.length == 1) {
      Plugin plugin = new Plugin(
        "coverage",
        coverageJarFiles[0].getAbsolutePath(),
        "com.google.jstestdriver.coverage.CoverageModule",
        filesExcludedFromCoverage
      );
      PluginLoader pluginLoader = new PluginLoader();
      final List<Module> modules = pluginLoader.load(Collections.singletonList(plugin));
      if (modules.size() == 1) {
        return new PluginInitializer() {
          @Override
          public Module initializeModule(Flags flags, Configuration config) {
            return modules.get(0);
          }
        };
      }
    }
    return null;
  }

  @SuppressWarnings("SSBasedInspection")
  @Nullable
  private static File createTempDir() {
    try {
      File file = File.createTempFile("jstestdriver-coverage-", "-tmp");
      if (!file.delete()) {
        return null;
      }
      if (!file.mkdir()) {
        return null;
      }
      file.deleteOnExit();
      return file;
    } catch (IOException e) {
      return null;
    }
  }

  public static void main(String[] args) throws Exception {
    Map<ParameterKey, String> paramMap = parseParams(args);
    Settings settings = Settings.build(paramMap);
    OutputManager outputManager = new OutputManager();
    if (!validateServer(settings, outputManager)) {
      return;
    }
    try {
      new TestRunner(settings, outputManager).executeAll();
    } catch (Exception ex) {
      outputManager.printThrowable("JsTestDriver crashed!", ex);
    }
  }

  private static boolean validateServer(@NotNull Settings settings, @NotNull OutputManager outputManager) throws IOException {
    String serverUrl = settings.getServerUrl();
    JstdServerFetchResult fetchResult = JstdServerUtilsRt.syncFetchServerInfo(serverUrl);
    String message = null;
    if (fetchResult.isError()) {
      message = "Could not connect to a JsTestDriver server running at " + serverUrl + "\n" +
                "Check that the server is running.";
    } else if (fetchResult.getServerInfo().getCapturedBrowsers().isEmpty()) {
      message = "No captured browsers found.\n" +
                "To capture browser open '" + serverUrl + "' in browser.";
    }
    if (message != null) {
      outputManager.printErrorMessage(message);
      return false;
    }
    return true;
  }

  private static Map<ParameterKey, String> parseParams(String[] args) {
    Map<ParameterKey, String> params = Maps.newHashMap();
    for (String arg : args) {
      List<String> elements = EscapeUtils.split(arg, '=');
      if (arg.startsWith("--") && elements.size() == 2) {
        String key = elements.get(0).substring(2);
        String value = elements.get(1);
        ParameterKey parameterKey = ParameterKey.valueOf(key.toUpperCase());
        params.put(parameterKey, value);
      }
    }
    return params;
  }

  private static class Settings {
    private final String myServerUrl;
    private final List<File> myConfigFiles;
    private final String myTestCaseName;
    private final String myTestMethodName;
    private final File myIdeCoverageFile;
    private final ImmutableList<String> myFilesExcludedFromCoverage;

    private Settings(
      @NotNull String serverUrl,
      @NotNull List<File> configFiles,
      @NotNull String testCaseName,
      @NotNull String testMethodName,
      @Nullable File ideCoverageFile,
      @NotNull List<String> filesExcludedFromCoverage)
    {
      myServerUrl = serverUrl;
      myConfigFiles = configFiles;
      myTestCaseName = testCaseName;
      myTestMethodName = testMethodName;
      myIdeCoverageFile = ideCoverageFile;
      myFilesExcludedFromCoverage = ImmutableList.copyOf(filesExcludedFromCoverage);
    }

    @NotNull
    public String getServerUrl() {
      return myServerUrl;
    }

    @NotNull
    public List<File> getConfigFiles() {
      return myConfigFiles;
    }

    @NotNull
    public String getTestCaseName() {
      return myTestCaseName;
    }

    @NotNull
    public String getTestMethodName() {
      return myTestMethodName;
    }

    @Nullable
    public File getIdeCoverageFile() {
      return myIdeCoverageFile;
    }

    @NotNull
    public ImmutableList<String> getFilesExcludedFromCoverage() {
      return myFilesExcludedFromCoverage;
    }

    @NotNull
    private static Settings build(@NotNull Map<ParameterKey, String> parameters) {
      String serverUrl = parameters.get(ParameterKey.SERVER_URL);
      if (serverUrl == null) {
        throw new RuntimeException("server_url parameter must be specified");
      }
      String configFilesStr = notNullize(parameters.get(ParameterKey.CONFIG_FILE));
      List<String> paths = EscapeUtils.split(configFilesStr, ',');
      List<File> configFiles = Lists.newArrayList();
      for (String path : paths) {
        File file = new File(path);
        if (file.isFile()) {
          configFiles.add(file);
        }
      }
      if (configFiles.isEmpty()) {
        throw new RuntimeException("No valid config files found");
      }
      String testCaseName = notNullize(parameters.get(ParameterKey.TEST_CASE));
      String testMethodName = notNullize(parameters.get(ParameterKey.TEST_METHOD));
      String coverageFilePath = notNullize(parameters.get(ParameterKey.COVERAGE_OUTPUT_FILE));
      File ideCoverageFile = null;
      List<String> excludedPaths = Collections.emptyList();
      if (!coverageFilePath.isEmpty()) {
        ideCoverageFile = new File(coverageFilePath);
        String joinedPaths = notNullize(parameters.get(ParameterKey.COVERAGE_EXCLUDED_PATHS));
        excludedPaths = EscapeUtils.split(joinedPaths, ',');
      }
      return new Settings(serverUrl, configFiles, testCaseName, testMethodName, ideCoverageFile, excludedPaths);
    }
  }

  private static String notNullize(@Nullable String str) {
    return str == null ? "" : str;
  }

  @SuppressWarnings("SSBasedInspection")
  @NotNull
  private static String[] toStringArray(@NotNull List<String> list) {
    return list.toArray(new String[list.size()]);
  }

  private static class DependenciesTouchFix implements PluginInitializer {
    @Override
    public Module initializeModule(Flags flags, Configuration config) {
      return new AbstractModule() {
        @Override
        protected void configure() {
          Multibinder.newSetBinder(binder(), ResourcePreProcessor.class)
            .addBinding().toInstance(new ResourcePreProcessor() {
            @Override
            public List<FileInfo> processDependencies(List<FileInfo> files) {
              List<FileInfo> out = Lists.newArrayList();
              for (FileInfo file : files) {
                FileInfo touchedFileInfo = file.fromResolvedPath(
                  file.getFilePath(),
                  file.getDisplayPath(),
                  new DateTime().toInstant().getMillis()
                );
                out.add(touchedFileInfo);
              }
              return out;
            }

            @Override
            public List<FileInfo> processTests(List<FileInfo> files) {
              return files;
            }

            @Override
            public List<FileInfo> processPlugins(List<FileInfo> files) {
              return files;
            }
          });
        }
      };
    }
  }

  private static final class NullOutputStream extends OutputStream {

    /** Discards the specified byte. */
    @Override public void write(int b) {
    }

    /** Discards the specified byte array. */
    @Override public void write(byte[] b, int off, int len) {
    }
  }

}