// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.LoadingResult;
import com.google.devtools.build.lib.pkgcache.PackageManager;
import com.google.devtools.build.lib.pkgcache.TestFilter;
import com.google.devtools.build.lib.skyframe.serialization.NotSerializableRuntimeException;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec.VisibleForSerialization;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A value referring to a computed set of resolved targets. This is used for the results of target
 * pattern parsing.
 */
@Immutable
@ThreadSafe
@VisibleForTesting
@AutoCodec
public final class TargetPatternPhaseValue implements SkyValue {

  private final ImmutableSet<Label> targetLabels;
  @Nullable private final ImmutableSet<Label> testsToRunLabels;
  private final boolean hasError;
  private final boolean hasPostExpansionError;

  // This field is only for the purposes of generating the LoadingPhaseCompleteEvent.
  // TODO(ulfjack): Support EventBus event posting in Skyframe, and remove this code again.
  private final ImmutableSet<Label> removedTargetLabels;
  private final String workspaceName;

  TargetPatternPhaseValue(
      ImmutableSet<Label> targetLabels,
      ImmutableSet<Label> testsToRunLabels,
      boolean hasError,
      boolean hasPostExpansionError,
      ImmutableSet<Label> removedTargetLabels,
      String workspaceName) {
    this.targetLabels = targetLabels;
    this.testsToRunLabels = testsToRunLabels;
    this.hasError = hasError;
    this.hasPostExpansionError = hasPostExpansionError;
    this.removedTargetLabels = removedTargetLabels;
    this.workspaceName = workspaceName;
  }

  public ImmutableSet<Target> getTargets(
      ExtendedEventHandler eventHandler, PackageManager packageManager) {
    return targetLabels
        .stream()
        .map(
            (label) -> {
              try {
                return packageManager
                    .getPackage(eventHandler, label.getPackageIdentifier())
                    .getTarget(label.getName());
              } catch (NoSuchPackageException | NoSuchTargetException | InterruptedException e) {
                throw new RuntimeException("Failed to get package from TargetPatternPhaseValue", e);
              }
            })
        .collect(ImmutableSet.toImmutableSet());
  }

  public ImmutableSet<Label> getTargetLabels() {
    return targetLabels;
  }

  @Nullable
  public ImmutableSet<Label> getTestsToRunLabels() {
    return testsToRunLabels;
  }

  public boolean hasError() {
    return hasError;
  }

  public boolean hasPostExpansionError() {
    return hasPostExpansionError;
  }

  /**
   * Returns a set of targets that were present on the command line but got expanded during the
   * loading phase (currently these are only test suites; this set is always empty when <code>
   * --expand_test_suites=false</code>.
   */
  public ImmutableSet<Target> getRemovedTargets(
      ExtendedEventHandler eventHandler, PackageManager packageManager) {
    return removedTargetLabels
        .stream()
        .map(
            (label) -> {
              try {
                return packageManager
                    .getPackage(eventHandler, label.getPackageIdentifier())
                    .getTarget(label.getName());
              } catch (NoSuchPackageException | NoSuchTargetException | InterruptedException e) {
                throw new RuntimeException(e);
              }
            })
        .collect(ImmutableSet.toImmutableSet());
  }

  public String getWorkspaceName() {
    return workspaceName;
  }

  public LoadingResult toLoadingResult(
      ExtendedEventHandler eventHandler, PackageManager packageManager) {
    ImmutableSet<Target> targets =
        getTargetLabels()
            .stream()
            .map(
                (label) -> {
                  try {
                    return packageManager
                        .getPackage(eventHandler, label.getPackageIdentifier())
                        .getTarget(label.getName());
                  } catch (NoSuchPackageException
                      | NoSuchTargetException
                      | InterruptedException e) {
                    throw new RuntimeException(e);
                  }
                })
            .collect(ImmutableSet.toImmutableSet());
    ImmutableSet<Target> testsToRun = null;
    if (testsToRunLabels != null) {
      testsToRun =
          getTestsToRunLabels()
              .stream()
              .map(
                  (label) -> {
                    try {
                      return packageManager
                          .getPackage(eventHandler, label.getPackageIdentifier())
                          .getTarget(label.getName());
                    } catch (NoSuchPackageException
                        | NoSuchTargetException
                        | InterruptedException e) {
                      throw new RuntimeException(e);
                    }
                  })
              .collect(ImmutableSet.toImmutableSet());
    }

    return new LoadingResult(
        hasError(), hasPostExpansionError(), targets, testsToRun, getWorkspaceName());
  }

  @SuppressWarnings("unused")
  private void writeObject(ObjectOutputStream out) {
    throw new NotSerializableRuntimeException();
  }

  @SuppressWarnings("unused")
  private void readObject(ObjectInputStream in) {
    throw new NotSerializableRuntimeException();
  }

  @SuppressWarnings("unused")
  private void readObjectNoData() {
    throw new IllegalStateException();
  }

  /** Create a target pattern phase value key. */
  @ThreadSafe
  public static SkyKey key(
      ImmutableList<String> targetPatterns,
      String offset,
      boolean compileOneDependency,
      boolean buildTestsOnly,
      boolean determineTests,
      ImmutableList<String> buildTargetFilter,
      boolean buildManualTests,
      boolean expandTestSuites,
      @Nullable TestFilter testFilter) {
    return new TargetPatternPhaseKey(
        targetPatterns,
        offset,
        compileOneDependency,
        buildTestsOnly,
        determineTests,
        buildTargetFilter,
        buildManualTests,
        expandTestSuites,
        testFilter);
  }

  /** The configuration needed to run the target pattern evaluation phase. */
  @ThreadSafe
  @VisibleForSerialization
  @AutoCodec
  public static final class TargetPatternPhaseKey implements SkyKey, Serializable {
    private final ImmutableList<String> targetPatterns;
    private final String offset;
    private final boolean compileOneDependency;
    private final boolean buildTestsOnly;
    private final boolean determineTests;
    private final ImmutableList<String> buildTargetFilter;
    private final boolean buildManualTests;
    private final boolean expandTestSuites;
    @Nullable private final TestFilter testFilter;

    TargetPatternPhaseKey(
        ImmutableList<String> targetPatterns,
        String offset,
        boolean compileOneDependency,
        boolean buildTestsOnly,
        boolean determineTests,
        ImmutableList<String> buildTargetFilter,
        boolean buildManualTests,
        boolean expandTestSuites,
        @Nullable TestFilter testFilter) {
      this.targetPatterns = Preconditions.checkNotNull(targetPatterns);
      this.offset = Preconditions.checkNotNull(offset);
      this.compileOneDependency = compileOneDependency;
      this.buildTestsOnly = buildTestsOnly;
      this.determineTests = determineTests;
      this.buildTargetFilter = Preconditions.checkNotNull(buildTargetFilter);
      this.buildManualTests = buildManualTests;
      this.expandTestSuites = expandTestSuites;
      this.testFilter = testFilter;
      if (buildTestsOnly || determineTests) {
        Preconditions.checkNotNull(testFilter);
      }
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.TARGET_PATTERN_PHASE;
    }

    public ImmutableList<String> getTargetPatterns() {
      return targetPatterns;
    }

    public String getOffset() {
      return offset;
    }

    public boolean getCompileOneDependency() {
      return compileOneDependency;
    }

    public boolean getBuildTestsOnly() {
      return buildTestsOnly;
    }

    public boolean getDetermineTests() {
      return determineTests;
    }

    public ImmutableList<String> getBuildTargetFilter() {
      return buildTargetFilter;
    }

    public boolean getBuildManualTests() {
      return buildManualTests;
    }

    public TestFilter getTestFilter() {
      return testFilter;
    }

    public boolean isExpandTestSuites() {
      return expandTestSuites;
    }

    @Override
    public String toString() {
      StringBuilder result = new StringBuilder();
      result.append(targetPatterns);
      if (!offset.isEmpty()) {
        result.append(" OFFSET=").append(offset);
      }
      result.append(compileOneDependency ? " COMPILE_ONE_DEPENDENCY" : "");
      result.append(buildTestsOnly ? " BUILD_TESTS_ONLY" : "");
      result.append(determineTests ? " DETERMINE_TESTS" : "");
      result.append(expandTestSuites ? " EXPAND_TEST_SUITES" : "");
      result.append(testFilter != null ? " " + testFilter : "");
      return result.toString();
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          targetPatterns,
          offset,
          compileOneDependency,
          buildTestsOnly,
          determineTests,
          buildManualTests,
          expandTestSuites,
          testFilter);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof TargetPatternPhaseKey)) {
        return false;
      }
      TargetPatternPhaseKey other = (TargetPatternPhaseKey) obj;
      return other.targetPatterns.equals(this.targetPatterns)
          && other.offset.equals(this.offset)
          && other.compileOneDependency == compileOneDependency
          && other.buildTestsOnly == buildTestsOnly
          && other.determineTests == determineTests
          && other.buildTargetFilter.equals(buildTargetFilter)
          && other.buildManualTests == buildManualTests
          && other.expandTestSuites == expandTestSuites
          && Objects.equals(other.testFilter, testFilter);
    }
  }
}
