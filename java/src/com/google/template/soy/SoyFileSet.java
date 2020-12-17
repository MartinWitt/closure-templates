/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteSink;
import com.google.common.io.ByteSource;
import com.google.common.io.CharSink;
import com.google.common.io.CharSource;
import com.google.protobuf.Descriptors.GenericDescriptor;
import com.google.template.soy.SoyFileSetParser.CompilationUnitAndKind;
import com.google.template.soy.SoyFileSetParser.ParseResult;
import com.google.template.soy.base.SourceFilePath;
import com.google.template.soy.base.internal.SoyFileKind;
import com.google.template.soy.base.internal.SoyFileSupplier;
import com.google.template.soy.base.internal.TriState;
import com.google.template.soy.conformance.ValidatedConformanceConfig;
import com.google.template.soy.css.CssRegistry;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyCompilationException;
import com.google.template.soy.error.SoyError;
import com.google.template.soy.error.SoyErrors;
import com.google.template.soy.error.SoyInternalCompilerException;
import com.google.template.soy.incrementaldomsrc.IncrementalDomSrcMain;
import com.google.template.soy.incrementaldomsrc.SoyIncrementalDomSrcOptions;
import com.google.template.soy.invocationbuilders.passes.GenInvocationBuildersVisitor;
import com.google.template.soy.jbcsrc.BytecodeCompiler;
import com.google.template.soy.jbcsrc.api.SoySauce;
import com.google.template.soy.jbcsrc.api.SoySauceImpl;
import com.google.template.soy.jbcsrc.shared.CompiledTemplates;
import com.google.template.soy.jssrc.SoyJsSrcOptions;
import com.google.template.soy.jssrc.internal.JsSrcMain;
import com.google.template.soy.logging.AnnotatedLoggingConfig;
import com.google.template.soy.logging.AnnotatedLoggingConfigGenerator;
import com.google.template.soy.logging.ValidatedLoggingConfig;
import com.google.template.soy.logging.VeMetadataGenerator;
import com.google.template.soy.msgs.SoyMsgBundle;
import com.google.template.soy.msgs.SoyMsgBundleHandler;
import com.google.template.soy.msgs.SoyMsgBundleHandler.OutputFileOptions;
import com.google.template.soy.msgs.internal.ExtractMsgsVisitor;
import com.google.template.soy.parseinfo.passes.GenerateParseInfoVisitor;
import com.google.template.soy.passes.CheckTemplateHeaderVarsPass;
import com.google.template.soy.passes.ClearSoyDocStringsVisitor;
import com.google.template.soy.passes.PassManager;
import com.google.template.soy.passes.PassManager.AstRewrites;
import com.google.template.soy.passes.PassManager.PassContinuationRule;
import com.google.template.soy.passes.PluginResolver;
import com.google.template.soy.passes.SoyConformancePass;
import com.google.template.soy.plugin.internal.PluginValidator;
import com.google.template.soy.plugin.restricted.SoySourceFunction;
import com.google.template.soy.pysrc.SoyPySrcOptions;
import com.google.template.soy.pysrc.internal.PySrcMain;
import com.google.template.soy.shared.SoyAstCache;
import com.google.template.soy.shared.SoyGeneralOptions;
import com.google.template.soy.shared.internal.InternalPlugins;
import com.google.template.soy.shared.internal.SoyScopedData;
import com.google.template.soy.shared.internal.SoySimpleScope;
import com.google.template.soy.shared.internal.gencode.GeneratedFile;
import com.google.template.soy.shared.restricted.SoyFunction;
import com.google.template.soy.shared.restricted.SoyFunctionSignature;
import com.google.template.soy.shared.restricted.SoyMethodSignature;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import com.google.template.soy.soytree.CompilationUnit;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.TemplateRegistry;
import com.google.template.soy.tofu.SoyTofu;
import com.google.template.soy.tofu.internal.BaseTofu;
import com.google.template.soy.types.SoyTypeRegistry;
import com.google.template.soy.types.SoyTypeRegistryBuilder;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Represents a complete set of Soy files for compilation as one bundle. The files may depend on
 * each other but should not have dependencies outside of the set.
 *
 * <p>Note: Soy file (or resource) contents must be encoded in UTF-8.
 *
 */
public final class SoyFileSet {
  private static final Logger logger = Logger.getLogger(SoyFileSet.class.getName());

  /**
   * Creates a builder with the standard set of Soy directives, functions, and types.
   *
   * <p>If you need additional directives, functions, or types, create the Builder instance and then
   * call {@link Builder#addSourceFunction(SoySourceFunction)}.
   *
   * @deprecated Use the command line compilers to generate code instead of this interface. SoySauce
   *     users can get an SoySauce instance via SoySauceBuilder.
   */
  @Deprecated
  public static Builder builder() {
    return new Builder(/* ignored= */ true);
  }

  /**
   * Builder for a {@code SoyFileSet}.
   *
   * <p>Instances of this can be obtained by calling {@link #builder()} or by installing {@link
   * SoyModule} and injecting it.
   */
  public static final class Builder {
    /** The SoyFileSuppliers collected so far in added order, as a set to prevent dupes. */
    private final ImmutableMap.Builder<SourceFilePath, SoyFileSupplier> filesBuilder =
        ImmutableMap.builder();

    private final ImmutableList.Builder<CompilationUnitAndKind> compilationUnitsBuilder =
        ImmutableList.builder();

    /** Optional AST cache. */
    private SoyAstCache cache = null;

    /** The general compiler options. */
    private SoyGeneralOptions lazyGeneralOptions = null;

    /** The SoyProtoTypeProvider builder that will be built for local type registry. */
    private final SoyTypeRegistryBuilder typeRegistryBuilder = new SoyTypeRegistryBuilder();

    @Nullable private Appendable warningSink;

    private ValidatedConformanceConfig conformanceConfig = ValidatedConformanceConfig.EMPTY;

    private ValidatedLoggingConfig loggingConfig = ValidatedLoggingConfig.EMPTY;

    private ImmutableList<File> pluginRuntimeJars = ImmutableList.of();

    private Optional<CssRegistry> cssRegistry = Optional.empty();

    private boolean skipPluginValidation = false;

    private boolean optimize = true;

    private final ImmutableSet.Builder<SoyFunction> soyFunctions = ImmutableSet.builder();
    private final ImmutableSet.Builder<SoyPrintDirective> soyPrintDirectives =
        ImmutableSet.builder();
    private final ImmutableSet.Builder<SoySourceFunction> sourceFunctions = ImmutableSet.builder();
    private final ImmutableSet.Builder<SoySourceFunction> sourceMethods = ImmutableSet.builder();

    Builder(boolean ignored) {
      // we use an ignored parameter to prevent guice from creating implicit bindings for this
      // object.
    }

    /**
     * Sets all Soy general options.
     *
     * <p>This must be called before any other setters.
     */
    public Builder setGeneralOptions(SoyGeneralOptions generalOptions) {
      Preconditions.checkState(
          lazyGeneralOptions == null,
          "Call SoyFileSet#setGeneralOptions before any other setters.");
      Preconditions.checkNotNull(generalOptions, "Non-null argument expected.");
      lazyGeneralOptions = generalOptions.clone();
      return this;
    }

    /**
     * Returns and/or lazily-creates the SoyGeneralOptions for this builder.
     *
     * <p>Laziness is an important feature to ensure that setGeneralOptions can fail if options were
     * already set. Otherwise, it'd be easy to set some options on this builder and overwrite them
     * by calling setGeneralOptions.
     */
    private SoyGeneralOptions getGeneralOptions() {
      if (lazyGeneralOptions == null) {
        lazyGeneralOptions = new SoyGeneralOptions();
      }
      return lazyGeneralOptions;
    }

    /**
     * Builds the new {@code SoyFileSet}.
     *
     * @return The new {@code SoyFileSet}.
     */
    public SoyFileSet build() {
      SoyScopedData data = new SoySimpleScope();
      return new SoyFileSet(
          data,
          typeRegistryBuilder.build(),
          ImmutableList.<SoyFunction>builder()
              .addAll(InternalPlugins.internalLegacyFunctions())
              .addAll(soyFunctions.build())
              .build(),
          ImmutableList.<SoyPrintDirective>builder()
              .addAll(InternalPlugins.internalDirectives(data))
              .addAll(soyPrintDirectives.build())
              .build(),
          ImmutableList.<SoySourceFunction>builder()
              .addAll(InternalPlugins.internalFunctions())
              .addAll(sourceFunctions.build())
              .build(),
          ImmutableList.<SoySourceFunction>builder()
              .addAll(InternalPlugins.internalMethods())
              .addAll(sourceMethods.build())
              .build(),
          filesBuilder.build(),
          compilationUnitsBuilder.build(),
          getGeneralOptions(),
          cache,
          conformanceConfig,
          loggingConfig,
          warningSink,
          pluginRuntimeJars,
          skipPluginValidation,
          optimize,
          cssRegistry);
    }

    /** Adds one {@link SoySourceFunction} to the functions used by this SoyFileSet. */
    public Builder addSourceFunction(SoySourceFunction function) {
      boolean method = false;
      if (function.getClass().isAnnotationPresent(SoyMethodSignature.class)) {
        sourceMethods.add(function);
        method = true;
      }
      if (!method || function.getClass().isAnnotationPresent(SoyFunctionSignature.class)) {
        sourceFunctions.add(function);
      }
      return this;
    }

    /** Adds many {@link SoySourceFunction}s to the functions used by this SoyFileSet. */
    public Builder addSourceFunctions(Iterable<? extends SoySourceFunction> function) {
      for (SoySourceFunction f : function) {
        addSourceFunction(f);
      }
      return this;
    }

    public Builder addSourceMethod(SoySourceFunction function) {
      Preconditions.checkArgument(
          function.getClass().isAnnotationPresent(SoyMethodSignature.class));
      sourceMethods.add(function);
      return this;
    }

    /** Adds one {@link SoyFunction} to the functions used by this SoyFileSet. */
    public Builder addSoyFunction(SoyFunction function) {
      soyFunctions.add(function);
      return this;
    }

    /** Adds many {@link SoyFunction}s to the functions used by this SoyFileSet. */
    public Builder addSoyFunctions(Iterable<? extends SoyFunction> function) {
      soyFunctions.addAll(function);
      return this;
    }

    /** Adds one {@link SoyPrintDirective} to the print directives used by this SoyFileSet. */
    public Builder addSoyPrintDirective(SoyPrintDirective function) {
      soyPrintDirectives.add(function);
      return this;
    }

    /** Adds many {@link SoyPrintDirective}s to the print directives used by this SoyFileSet. */
    public Builder addSoyPrintDirectives(Iterable<? extends SoyPrintDirective> function) {
      soyPrintDirectives.addAll(function);
      return this;
    }

    /**
     * Adds an input Soy file, given a {@code CharSource} for the file content, as well as the
     * desired file path for messages.
     */
    public Builder add(SoyFileSupplier soyFileSupplier) {
      return addFile(soyFileSupplier);
    }

    /**
     * Adds an input Soy file, given a {@code CharSource} for the file content, as well as the
     * desired file path for messages.
     *
     * @param contentSource Source for the Soy file content.
     * @param filePath The path to the Soy file (used for messages only).
     * @return This builder.
     */
    public Builder add(CharSource contentSource, String filePath) {
      return addFile(
          SoyFileSupplier.Factory.create(contentSource, SourceFilePath.create(filePath)));
    }

    /**
     * Adds an input Soy file, given a resource {@code URL}, as well as the desired file path for
     * messages.
     *
     * @param inputFileUrl The Soy file.
     * @param filePath The path to the Soy file (used for messages only).
     * @return This builder.
     */
    public Builder add(URL inputFileUrl, String filePath) {
      return addFile(SoyFileSupplier.Factory.create(inputFileUrl, SourceFilePath.create(filePath)));
    }

    /**
     * Adds an input Soy file, given a resource {@code URL}.
     *
     * <p>Important: This function assumes that the desired file path is returned by {@code
     * inputFileUrl.toString()}. If this is not the case, please use {@link #add(URL, String)}
     * instead.
     *
     * @see #add(URL, String)
     * @param inputFileUrl The Soy file.
     * @return This builder.
     * @deprecated This method is incompatible with imports since the filename is unlikely to be
     *     correct. Please call {@link #add(URL, String)} instead, or better yet, migrate off of
     *     SoyFileSet.
     */
    @Deprecated
    public Builder add(URL inputFileUrl) {
      return add(inputFileUrl, inputFileUrl.toString());
    }

    /**
     * Adds an input Soy file, given the file content provided as a string, as well as the desired
     * file path for messages.
     *
     * @param content The Soy file content.
     * @param filePath The path to the Soy file (used for messages only).
     * @return This builder.
     */
    public Builder add(CharSequence content, String filePath) {
      return addFile(SoyFileSupplier.Factory.create(content, SourceFilePath.create(filePath)));
    }

    /**
     * Adds an input Soy file, given a {@code File}.
     *
     * @param inputFile The Soy file.
     * @return This builder.
     */
    public Builder add(File inputFile) {
      return addFile(SoyFileSupplier.Factory.create(inputFile));
    }

    /**
     * Configures to use an AST cache to speed up development time.
     *
     * <p>This is undesirable in production mode since it uses strictly more memory, and this only
     * helps if the same templates are going to be recompiled frequently.
     *
     * @param cache The cache to use, which can have a lifecycle independent of the SoyFileSet. Null
     *     indicates not to use a cache.
     * @return This builder.
     */
    public Builder setSoyAstCache(SoyAstCache cache) {
      this.cache = cache;
      return this;
    }

    /**
     * Sets whether to allow external calls (calls to undefined templates).
     *
     * @param allowExternalCalls Whether to allow external calls (calls to undefined templates).
     * @return This builder.
     */
    public Builder setAllowExternalCalls(boolean allowExternalCalls) {
      getGeneralOptions().setAllowExternalCalls(allowExternalCalls);
      return this;
    }

    /**
     * Sets experimental features. These features are unreleased and are not generally available.
     *
     * @param experimentalFeatures
     * @return This builder.
     */
    public Builder setExperimentalFeatures(List<String> experimentalFeatures) {
      getGeneralOptions().setExperimentalFeatures(experimentalFeatures);
      return this;
    }

    /**
     * Disables optimizer. The optimizer tries to simplify the Soy AST by evaluating constant
     * expressions. It generally improves performance and should only be disabled in integration
     * tests.
     *
     * <p>This is public only because we need to set it in {@code SoyFileSetHelper}, that are
     * necessary for integration tests. Normal users should not use this.
     *
     * @return This builder.
     */
    public Builder disableOptimizer() {
      optimize = false;
      return this;
    }

    /**
     * Sets the map from compile-time global name to value.
     *
     * <p>The values can be any of the Soy primitive types: null, boolean, integer, float (Java
     * double), or string.
     *
     * @param compileTimeGlobalsMap Map from compile-time global name to value. The values can be
     *     any of the Soy primitive types: null, boolean, integer, float (Java double), or string.
     * @return This builder.
     * @throws IllegalArgumentException If one of the values is not a valid Soy primitive type.
     */
    public Builder setCompileTimeGlobals(Map<String, ?> compileTimeGlobalsMap) {
      getGeneralOptions().setCompileTimeGlobals(compileTimeGlobalsMap);
      return this;
    }

    /**
     * Sets the file containing compile-time globals.
     *
     * <p>Each line of the file should have the format
     *
     * <pre>
     *     &lt;global_name&gt; = &lt;primitive_data&gt;
     * </pre>
     *
     * where primitive_data is a valid Soy expression literal for a primitive type (null, boolean,
     * integer, float, or string). Empty lines and lines beginning with "//" are ignored. The file
     * should be encoded in UTF-8.
     *
     * <p>If you need to generate a file in this format from Java, consider using the utility {@code
     * SoyUtils.generateCompileTimeGlobalsFile()}.
     *
     * @param compileTimeGlobalsFile The file containing compile-time globals.
     * @return This builder.
     * @throws IOException If there is an error reading the compile-time globals file.
     */
    public Builder setCompileTimeGlobals(File compileTimeGlobalsFile) throws IOException {
      getGeneralOptions().setCompileTimeGlobals(compileTimeGlobalsFile);
      return this;
    }

    /**
     * Sets the resource file containing compile-time globals.
     *
     * <p>Each line of the file should have the format
     *
     * <pre>
     *     &lt;global_name&gt; = &lt;primitive_data&gt;
     * </pre>
     *
     * where primitive_data is a valid Soy expression literal for a primitive type (null, boolean,
     * integer, float, or string). Empty lines and lines beginning with "//" are ignored. The file
     * should be encoded in UTF-8.
     *
     * <p>If you need to generate a file in this format from Java, consider using the utility {@code
     * SoyUtils.generateCompileTimeGlobalsFile()}.
     *
     * @param compileTimeGlobalsResource The resource containing compile-time globals.
     * @return This builder.
     * @throws IOException If there is an error reading the compile-time globals file.
     */
    public Builder setCompileTimeGlobals(URL compileTimeGlobalsResource) throws IOException {
      getGeneralOptions().setCompileTimeGlobals(compileTimeGlobalsResource);
      return this;
    }

    /**
     * Registers a collection of protocol buffer descriptors. This makes all the types defined in
     * the provided descriptors available to use in soy.
     */
    public Builder addProtoDescriptors(GenericDescriptor... descriptors) {
      return addProtoDescriptors(Arrays.asList(descriptors));
    }

    /**
     * Registers a collection of protocol buffer descriptors. This makes all the types defined in
     * the provided descriptors available to use in soy.
     */
    public Builder addProtoDescriptors(Iterable<? extends GenericDescriptor> descriptors) {
      typeRegistryBuilder.addDescriptors(descriptors);
      return this;
    }

    /** Registers a conformance config proto. */
    Builder setConformanceConfig(ValidatedConformanceConfig config) {
      checkNotNull(config);
      this.conformanceConfig = config;
      return this;
    }

    Builder addCompilationUnit(
        SoyFileKind fileKind, SourceFilePath filePath, CompilationUnit compilationUnit) {
      compilationUnitsBuilder.add(
          CompilationUnitAndKind.create(fileKind, filePath, compilationUnit));
      return this;
    }

    private Builder addFile(SoyFileSupplier supplier) {
      filesBuilder.put(supplier.getFilePath(), supplier);
      return this;
    }

    /**
     * Configures a place to write warnings for successful compilations.
     *
     * <p>For compilation failures warnings are reported along with the errors, by throwing an
     * exception. The default is to report warnings to the logger for SoyFileSet.
     */
    Builder setWarningSink(Appendable warningSink) {
      this.warningSink = checkNotNull(warningSink);
      return this;
    }

    /**
     * Sets the logging config to use.
     *
     * @throws IllegalArgumentException if the config proto is invalid. For example, if there are
     *     multiple elements with the same {@code name} or {@code id}, or if the name not a valid
     *     identifier.
     */
    public Builder setLoggingConfig(AnnotatedLoggingConfig config) {
      return setValidatedLoggingConfig(ValidatedLoggingConfig.create(config));
    }

    /** Sets the validated logging config to use. */
    Builder setValidatedLoggingConfig(ValidatedLoggingConfig parseLoggingConfigs) {
      this.loggingConfig = checkNotNull(parseLoggingConfigs);
      return this;
    }

    /**
     * Sets the location of the jars containing plugin runtime code, for use validating plugin
     * MethodRefs.
     */
    Builder setPluginRuntimeJars(List<File> pluginRuntimeJars) {
      this.pluginRuntimeJars = ImmutableList.copyOf(pluginRuntimeJars);
      return this;
    }

    public Builder setCssRegistry(CssRegistry cssRegistry) {
      this.cssRegistry = Optional.of(cssRegistry);
      return this;
    }

    /**
     * Sets whether or not to skip plugin validation. Defaults to false. This should usually not be
     * set unless you're doing something real funky.
     */
    public Builder setSkipPluginValidation(boolean skipPluginValidation) {
      this.skipPluginValidation = skipPluginValidation;
      return this;
    }
  }

  private final SoyScopedData scopedData;

  private final SoyTypeRegistry typeRegistry;
  private final ImmutableMap<SourceFilePath, SoyFileSupplier> soyFileSuppliers;
  private final ImmutableList<CompilationUnitAndKind> compilationUnits;

  /** Optional soy tree cache for faster recompile times. */
  @Nullable private final SoyAstCache cache;

  private final SoyGeneralOptions generalOptions;

  private final ValidatedConformanceConfig conformanceConfig;
  private final ValidatedLoggingConfig loggingConfig;
  private final ImmutableList<File> pluginRuntimeJars;
  private final Optional<CssRegistry> cssRegistry;

  private final ImmutableList<SoyFunction> soyFunctions;
  private final ImmutableList<SoyPrintDirective> printDirectives;
  private final ImmutableList<SoySourceFunction> soySourceFunctions;
  private final ImmutableList<SoySourceFunction> soyMethods;

  private final boolean skipPluginValidation;

  private final boolean optimize;

  /** For reporting errors during parsing. */
  private ErrorReporter errorReporter;

  @Nullable private final Appendable warningSink;

  SoyFileSet(
      SoyScopedData apiCallScopeProvider,
      SoyTypeRegistry typeRegistry,
      ImmutableList<SoyFunction> soyFunctions,
      ImmutableList<SoyPrintDirective> printDirectives,
      ImmutableList<SoySourceFunction> soySourceFunctions,
      ImmutableList<SoySourceFunction> soyMethods,
      ImmutableMap<SourceFilePath, SoyFileSupplier> soyFileSuppliers,
      ImmutableList<CompilationUnitAndKind> compilationUnits,
      SoyGeneralOptions generalOptions,
      @Nullable SoyAstCache cache,
      ValidatedConformanceConfig conformanceConfig,
      ValidatedLoggingConfig loggingConfig,
      @Nullable Appendable warningSink,
      ImmutableList<File> pluginRuntimeJars,
      boolean skipPluginValidation,
      boolean optimize,
      Optional<CssRegistry> cssRegistry) {
    this.scopedData = apiCallScopeProvider;
    this.typeRegistry = typeRegistry;
    this.soyFileSuppliers = soyFileSuppliers;
    this.compilationUnits = compilationUnits;
    this.cache = cache;
    this.generalOptions = generalOptions.clone();
    this.soyFunctions = InternalPlugins.filterDuplicateFunctions(soyFunctions);
    this.printDirectives = InternalPlugins.filterDuplicateDirectives(printDirectives);
    this.soySourceFunctions = soySourceFunctions;
    this.soyMethods = soyMethods;
    this.conformanceConfig = checkNotNull(conformanceConfig);
    this.loggingConfig = checkNotNull(loggingConfig);
    this.warningSink = warningSink;
    this.pluginRuntimeJars = pluginRuntimeJars;
    this.skipPluginValidation = skipPluginValidation;
    this.optimize = optimize;
    this.cssRegistry = cssRegistry;
  }

  /** Returns the list of suppliers for the input Soy files. For testing use only! */
  @VisibleForTesting
  ImmutableMap<SourceFilePath, SoyFileSupplier> getSoyFileSuppliersForTesting() {
    return soyFileSuppliers;
  }

  ImmutableList<SourceFilePath> getSourceFilePaths() {
    return soyFileSuppliers.keySet().asList();
  }

  @VisibleForTesting
  SoyTypeRegistry getTypeRegistryForTesting() {
    return typeRegistry;
  }

  /** Template pattern for any public or package visible entry point method that returns a value. */
  private <T> T entryPoint(Supplier<T> variant) {
    resetErrorReporter();
    T rv;
    try {
      rv = variant.get();
    } catch (SoyCompilationException | SoyInternalCompilerException e) {
      throw e;
    } catch (RuntimeException e) {
      if (errorReporter.hasErrorsOrWarnings()) {
        throw new SoyInternalCompilerException(
            Iterables.concat(errorReporter.getErrors(), errorReporter.getWarnings()), e);
      } else {
        throw e;
      }
    }
    throwIfErrorsPresent();
    reportWarnings();
    return rv;
  }

  /** Template pattern for any public or package visible entry point method that is void. */
  private void entryPointVoid(Runnable variant) {
    entryPoint(
        () -> {
          variant.run();
          return null;
        });
  }

  /**
   * Generates Java classes containing template invocation builders for setting param values. There
   * will be one Java file per Soy file.
   *
   * @param javaPackage The Java package for the generated classes.
   * @return A list of generated files to write (of the form "<*>FooSoyTemplates.java").
   * @throws SoyCompilationException If compilation fails.
   */
  ImmutableList<GeneratedFile> generateInvocationBuilders(String javaPackage) {
    return entryPoint(
        () -> {
          ParseResult result = parseForGenJava();
          throwIfErrorsPresent();
          TemplateRegistry registry = result.registry();
          SoyFileSetNode soyTree = result.fileSet();

          // Generate template invocation builders for the soy tree.
          return new GenInvocationBuildersVisitor(errorReporter, javaPackage, registry)
              .exec(soyTree);
        });
  }

  /**
   * Generates Java classes containing parse info (param names, template names, meta info). There
   * will be one Java class per Soy file.
   *
   * @param javaPackage The Java package for the generated classes.
   * @param javaClassNameSource Source of the generated class names. Must be one of "filename",
   *     "namespace", or "generic".
   * @return A list of generated files to write (of the form "<*>SoyInfo.java").
   * @throws SoyCompilationException If compilation fails.
   */
  ImmutableList<GeneratedFile> generateParseInfo(String javaPackage, String javaClassNameSource) {
    return entryPoint(
        () -> {
          ParseResult result = parseForGenJava();
          throwIfErrorsPresent();

          SoyFileSetNode soyTree = result.fileSet();
          TemplateRegistry registry = result.registry();

          // Do renaming of package-relative class names.
          return new GenerateParseInfoVisitor(javaPackage, javaClassNameSource, registry)
              .exec(soyTree);
        });
  }

  /** Validates any user SoySourceFunction plugins. */
  void validateUserPlugins() {
    entryPointVoid(
        () -> {
          // First resolve all the plugins to ensure they're well-formed (no bad names, etc.).
          new PluginResolver(
              PluginResolver.Mode.REQUIRE_DEFINITIONS,
              printDirectives,
              soyFunctions,
              soySourceFunctions,
              soyMethods,
              errorReporter);
          // if constructing the resolver found an error, bail out now.
          throwIfErrorsPresent();
          // Then validate the user-specified source functions by filtering out the builtin ones
          // TODO(lukes): maybe we should store internal functions and plugins in separate lists to
          // avoid this filtering...
          ImmutableSet<Class<?>> internalFunctionNames =
              InternalPlugins.internalFunctions().stream()
                  .map(Object::getClass)
                  .collect(toImmutableSet());
          new PluginValidator(errorReporter, typeRegistry, pluginRuntimeJars)
              .validate(
                  soySourceFunctions.stream()
                      .filter(fn -> !internalFunctionNames.contains(fn.getClass()))
                      .collect(toImmutableList()));
          throwIfErrorsPresent();
        });
  }

  /** A simple tool to enforce conformance and only conformance. */
  void checkConformance() {
    entryPointVoid(
        () -> {
          // to check conformance we only need to run as much as it takes to execute the
          // SoyConformance pass.
          parse(
              passManagerBuilder()
                  .allowUnknownJsGlobals()
                  .allowV1Expression()
                  .desugarHtmlAndStateNodes(false)
                  .optimize(false)
                  .addHtmlAttributesForDebugging(false)
                  // TODO(lukes): kill the pass continuation mechanism
                  .addPassContinuationRule(
                      SoyConformancePass.class, PassContinuationRule.STOP_AFTER_PASS));
        });
  }

  AnnotatedLoggingConfig generateAnnotatedLoggingConfig(
      CharSource rawLoggingConfig,
      String javaPackage,
      String jsPackage,
      String className,
      String javaResourceFilename) {
    return entryPoint(
        () -> {
          try {
            return new AnnotatedLoggingConfigGenerator(
                    rawLoggingConfig,
                    javaPackage,
                    jsPackage,
                    className,
                    javaResourceFilename,
                    typeRegistry,
                    errorReporter)
                .generate();
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  void generateAndWriteVeMetadata(
      VeMetadataGenerator.Mode mode,
      ByteSource loggingConfigBytes,
      String generator,
      CharSink output,
      Optional<ByteSink> resourceOutput)
      throws IOException {
    new VeMetadataGenerator(
            mode, loggingConfigBytes, generator, typeRegistry, output, resourceOutput)
        .generateAndWrite();
  }

  /**
   * Extracts all messages from this Soy file set into a SoyMsgBundle (which can then be turned into
   * an extracted messages file with the help of a SoyMsgBundleHandler).
   *
   * @return A SoyMsgBundle containing all the extracted messages (locale "en").
   * @throws SoyCompilationException If compilation fails.
   * @deprecated Use the command line APIs to extract messages instead
   */
  @Deprecated
  public SoyMsgBundle extractMsgs() {
    return entryPoint(this::doExtractMsgs);
  }

  /**
   * Extracts all messages from this Soy file set and writes the messages to an output sink.
   *
   * @param msgBundleHandler Handler to write the messages.
   * @param options Options to configure how to write the extracted messages.
   * @param output Where to write the extracted messages.
   * @throws IOException If there are errors writing to the output.
   */
  void extractAndWriteMsgs(
      SoyMsgBundleHandler msgBundleHandler, OutputFileOptions options, ByteSink output)
      throws IOException {
    entryPointVoid(
        () -> {
          SoyMsgBundle bundle = doExtractMsgs();
          try {
            msgBundleHandler.writeExtractedMsgs(bundle, options, output, errorReporter);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  /** Performs the parsing and extraction logic. */
  private SoyMsgBundle doExtractMsgs() {
    // extractMsgs disables a bunch of passes since it is typically not configured with things
    // like global definitions, type definitions, plugins, etc.
    SoyFileSetNode soyTree =
        parse(
                passManagerBuilder()
                    .allowUnknownGlobals()
                    .allowV1Expression()
                    .allowUnknownJsGlobals()
                    // necessary because we are using an invalid type registry, also we don't really
                    // need to run the optimizer anyway.
                    .optimize(false)
                    .desugarHtmlAndStateNodes(false)
                    .setTypeRegistry(SoyTypeRegistry.DEFAULT_UNKNOWN)
                    // TODO(lukes): consider changing this to pass a null resolver instead of the
                    // ALLOW_UNDEFINED mode
                    .setPluginResolver(
                        new PluginResolver(
                            PluginResolver.Mode.ALLOW_UNDEFINED,
                            printDirectives,
                            soyFunctions,
                            soySourceFunctions,
                            soyMethods,
                            errorReporter))
                    .disableAllTypeChecking(),
                // override the type registry so that the parser doesn't report errors when it
                // can't resolve strict types
                SoyTypeRegistry.DEFAULT_UNKNOWN)
            .fileSet();
    throwIfErrorsPresent();
    SoyMsgBundle bundle = new ExtractMsgsVisitor(errorReporter).exec(soyTree);
    throwIfErrorsPresent();
    return bundle;
  }

  /**
   * Compiles this Soy file set into a Java object (type {@code SoyTofu}) capable of rendering the
   * compiled templates.
   *
   * @return The resulting {@code SoyTofu} object.
   * @throws SoyCompilationException If compilation fails.
   * @deprecated Use SoySauce instead. All users should be able to switch from
   *     SoyFileSet.compileToTofu() to SoyFileSet.compileTemplates(). To use the support for
   *     precompilation see SoySauceBuilder.
   */
  @Deprecated
  public SoyTofu compileToTofu() {
    return compileToTofu(ImmutableMap.of());
  }
  /**
   * Compiles this Soy file set into a Java object (type {@code SoyTofu}) capable of rendering the
   * compiled templates.
   *
   * @return The resulting {@code SoyTofu} object.
   * @throws SoyCompilationException If compilation fails.
   * @deprecated Use SoySauce instead. All users should be able to switch from
   *     SoyFileSet.compileToTofu() to SoyFileSet.compileTemplates(). To use the support for
   *     precompilation see SoySauceBuilder.
   */
  @Deprecated
  public SoyTofu compileToTofu(Map<String, Supplier<Object>> pluginInstances) {
    return entryPoint(
        () -> {
          ServerCompilationPrimitives primitives = compileForServerRendering();
          throwIfErrorsPresent();
          return doCompileToTofu(primitives, pluginInstances);
        });
  }

  /** Helper method to compile SoyTofu from {@link ServerCompilationPrimitives} */
  private SoyTofu doCompileToTofu(
      ServerCompilationPrimitives primitives, Map<String, Supplier<Object>> pluginInstances) {
    return new BaseTofu(scopedData.enterable(), primitives.soyTree, pluginInstances);
  }

  /**
   * Compiles this Soy file set into a set of java classes implementing the {@link SoySauce}
   * interface.
   *
   * <p>This is useful for implementing 'edit refresh' workflows. Most production usecases should
   * use the command line interface to 'ahead of time' compile templates to jar files and then use
   * {@code SoySauceBuilder} to get access to a {@link SoySauce} object without invoking the
   * compiler. This will allow applications to avoid invoking the soy compiler at runtime which can
   * be relatively slow.
   *
   * @return A set of compiled templates
   * @throws SoyCompilationException If compilation fails.
   */
  public SoySauce compileTemplates() {
    return compileTemplates(ImmutableMap.of());
  }
  /**
   * Compiles this Soy file set into a set of java classes implementing the {@link SoySauce}
   * interface.
   *
   * <p>This is useful for implementing 'edit refresh' workflows. Most production usecases should
   * use the command line interface to 'ahead of time' compile templates to jar files and then use
   * {@code SoySauceBuilder} to get access to a {@link SoySauce} object without invoking the
   * compiler. This will allow applications to avoid invoking the soy compiler at runtime which can
   * be relatively slow.
   *
   * @return A set of compiled templates
   * @throws SoyCompilationException If compilation fails.
   */
  public SoySauce compileTemplates(Map<String, Supplier<Object>> pluginInstances) {
    return entryPoint(
        () -> {
          disallowExternalCalls();
          ServerCompilationPrimitives primitives = compileForServerRendering();
          throwIfErrorsPresent();
          return doCompileSoySauce(primitives, pluginInstances);
        });
  }

  /**
   * Compiles this Soy file set into a set of java classes implementing the {@link
   * com.google.template.soy.jbcsrc.shared.CompiledTemplate} interface and writes them out to the
   * given ByteSink as a JAR file.
   *
   * @throws SoyCompilationException If compilation fails.
   */
  void compileToJar(ByteSink jarTarget, Optional<ByteSink> srcJarTarget) {
    entryPointVoid(
        () -> {
          disallowExternalCalls();
          ServerCompilationPrimitives primitives = compileForServerRendering();
          try {
            BytecodeCompiler.compileToJar(
                primitives.registry, primitives.soyTree, errorReporter, typeRegistry, jarTarget);
            if (srcJarTarget.isPresent()) {
              BytecodeCompiler.writeSrcJar(
                  primitives.soyTree, soyFileSuppliers, srcJarTarget.get());
            }
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  /** Helper method to compile SoySauce from {@link ServerCompilationPrimitives} */
  private SoySauce doCompileSoySauce(
      ServerCompilationPrimitives primitives, Map<String, Supplier<Object>> pluginInstances) {
    Optional<CompiledTemplates> templates =
        BytecodeCompiler.compile(
            primitives.registry, primitives.soyTree, errorReporter, soyFileSuppliers, typeRegistry);

    throwIfErrorsPresent();

    return new SoySauceImpl(
        templates.get(),
        scopedData.enterable(),
        soyFunctions,
        printDirectives,
        ImmutableMap.copyOf(pluginInstances));
  }

  /**
   * A tuple of the outputs of shared compiler passes that are needed to produce SoyTofu or
   * SoySauce.
   */
  private static final class ServerCompilationPrimitives {
    final SoyFileSetNode soyTree;
    final TemplateRegistry registry;

    ServerCompilationPrimitives(TemplateRegistry registry, SoyFileSetNode soyTree) {
      this.registry = registry;
      this.soyTree = soyTree;
    }
  }

  /** Runs common compiler logic shared by tofu and jbcsrc backends. */
  private ServerCompilationPrimitives compileForServerRendering() {
    ParseResult result = parse();
    throwIfErrorsPresent();

    SoyFileSetNode soyTree = result.fileSet();
    TemplateRegistry registry = result.registry();
    // Clear the SoyDoc strings because they use unnecessary memory, unless we have a cache, in
    // which case it is pointless.
    if (cache == null) {
      new ClearSoyDocStringsVisitor().exec(soyTree);
    }

    throwIfErrorsPresent();
    return new ServerCompilationPrimitives(registry, soyTree);
  }

  private void disallowExternalCalls() {
    TriState allowExternalCalls = generalOptions.allowExternalCalls();
    if (allowExternalCalls == TriState.UNSET) {
      generalOptions.setAllowExternalCalls(false);
    } else if (allowExternalCalls == TriState.ENABLED) {
      throw new IllegalStateException(
          "SoyGeneralOptions.setAllowExternalCalls(true) is not supported with this method");
    }
    // otherwise, it was already explicitly set to false which is what we want.
  }

  /**
   * Compiles this Soy file set into JS source code files and returns these JS files as a list of
   * strings, one per file.
   *
   * @param jsSrcOptions The compilation options for the JS Src output target.
   * @param msgBundle The bundle of translated messages, or null to use the messages from the Soy
   *     source.
   * @return A list of strings where each string represents the JS source code that belongs in one
   *     JS file. The generated JS files correspond one-to-one to the original Soy source files.
   * @throws SoyCompilationException If compilation fails.
   * @deprecated Do not call. Use the command line API.
   */
  @SuppressWarnings("deprecation")
  @Deprecated
  public List<String> compileToJsSrc(
      SoyJsSrcOptions jsSrcOptions, @Nullable SoyMsgBundle msgBundle) {
    return compileToJsSrcInternal(jsSrcOptions, msgBundle);
  }

  List<String> compileToJsSrcInternal(
      SoyJsSrcOptions jsSrcOptions, @Nullable SoyMsgBundle msgBundle) {
    return entryPoint(
        () -> {
          PassManager.Builder builder =
              passManagerBuilder()
                  .allowV1Expression()
                  .allowUnknownJsGlobals()
                  .desugarHtmlAndStateNodes(false);
          ParseResult result = parse(builder);
          throwIfErrorsPresent();
          TemplateRegistry registry = result.registry();
          SoyFileSetNode fileSet = result.fileSet();
          return new JsSrcMain(scopedData.enterable(), typeRegistry)
              .genJsSrc(fileSet, registry, jsSrcOptions, msgBundle, errorReporter);
        });
  }

  /**
   * Compiles this Soy file set into iDOM source code files and returns these JS files as a list of
   * strings, one per file.
   *
   * @param jsSrcOptions The compilation options for the JS Src output target.
   * @return A list of strings where each string represents the JS source code that belongs in one
   *     JS file. The generated JS files correspond one-to-one to the original Soy source files.
   * @throws SoyCompilationException If compilation fails.
   */
  List<String> compileToIncrementalDomSrcInternal(SoyIncrementalDomSrcOptions jsSrcOptions) {
    return entryPoint(
        () -> {
          // For incremental dom backend, we don't desugar HTML nodes since it requires HTML
          // context.
          ParseResult result = parse(passManagerBuilder().desugarHtmlAndStateNodes(false));
          throwIfErrorsPresent();
          return new IncrementalDomSrcMain(scopedData.enterable(), typeRegistry)
              .genJsSrc(result.fileSet(), result.registry(), jsSrcOptions, errorReporter);
        });
  }

  /**
   * Compiles this Soy file set into Python source code files and writes these Python files to disk.
   *
   * @param outputPathFormat The format string defining how to build the output file path
   *     corresponding to an input file path.
   * @param pySrcOptions The compilation options for the Python Src output target.
   * @throws SoyCompilationException If compilation fails.
   * @throws RuntimeException If there is an error in opening/reading a message file or
   *     opening/writing an output JS file.
   */
  List<String> compileToPySrcFiles(SoyPySrcOptions pySrcOptions) {
    return entryPoint(
        () -> {
          try {
            ParseResult result = parse();
            throwIfErrorsPresent();
            return new PySrcMain(scopedData.enterable())
                .genPyFiles(result.fileSet(), pySrcOptions, errorReporter);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @AutoValue
  abstract static class HeaderResult {
    abstract SoyFileSetNode fileSet();

    abstract TemplateRegistry templateRegistry();

    abstract CssRegistry cssRegistry();
  }

  /**
   * Performs the minimal amount of work needed to calculate TemplateMetadata objects for header
   * compilation.
   */
  HeaderResult compileMinimallyForHeaders() {
    return entryPoint(
        () -> {
          disallowExternalCalls();
          ParseResult parseResult =
              parse(
                  passManagerBuilder()
                      // Because we allow this for JS generated templates, we allow this for
                      // headers.
                      .allowUnknownJsGlobals()
                      // Only run passes that not cross template checking.
                      .addPassContinuationRule(
                          CheckTemplateHeaderVarsPass.class, PassContinuationRule.STOP_BEFORE_PASS)
                      .allowV1Expression(),
                  typeRegistry);
          // throw before accessing registry() to make sure it is definitely available.
          throwIfErrorsPresent();
          return new AutoValue_SoyFileSet_HeaderResult(
              parseResult.fileSet(), parseResult.registry(), cssRegistry.get());
        });
  }

  /** Returns the result of {@link #compileForAnalysis}. */
  @AutoValue
  public abstract static class AnalysisResult {
    AnalysisResult() {}

    /**
     * The template registry, will be empty if errors occurred early and it couldn't be constructed.
     */
    public abstract Optional<TemplateRegistry> registry();

    /** The full parsed AST. */
    public abstract SoyFileSetNode fileSet();

    /** Compiler warnings. This will include errors if {@code treatErrorsAsWarnings} was set. */
    public abstract ImmutableList<SoyError> warnings();
  }

  /** Performs enough work to retrieve all possible warnings in a compile. */
  public AnalysisResult compileForAnalysis(boolean treatErrorsAsWarnings, AstRewrites astRewrites) {
    return entryPoint(
        () -> {
          disallowExternalCalls();
          ParseResult result =
              parse(
                  passManagerBuilder()
                      // the optimizer mutates the AST heavily which inhibits certain source
                      // analysis
                      // rules.
                      .optimize(false)
                      .astRewrites(astRewrites)
                      // skip adding extra attributes
                      .addHtmlAttributesForDebugging(false)
                      // skip the autoescaper
                      .insertEscapingDirectives(false)
                      .desugarHtmlAndStateNodes(false)
                      // TODO(lukes): This is needed for kythe apparently
                      .allowUnknownGlobals()
                      .allowUnknownJsGlobals()
                      .allowV1Expression(),
                  typeRegistry);
          ImmutableList<SoyError> warnings;
          if (treatErrorsAsWarnings) {
            // we are essentially ignoring errors
            resetErrorReporter();
            warnings =
                ImmutableList.<SoyError>builder()
                    .addAll(errorReporter.getErrors())
                    .addAll(errorReporter.getWarnings())
                    .build();
          } else {
            warnings = errorReporter.getWarnings();
          }
          return new AutoValue_SoyFileSet_AnalysisResult(
              result.hasRegistry() ? Optional.of(result.registry()) : Optional.empty(),
              result.fileSet(),
              warnings);
        });
  }

  /**
   * Parses the file set with the options we need for writing generated java *SoyInfo and invocation
   * builders.
   */
  private ParseResult parseForGenJava() {
    // N.B. we do not run the optimizer here for 2 reasons:
    // 1. it would just waste time, since we are not running code generation the optimization
    //    work doesn't help anything
    // 2. it potentially removes metadata from the tree by precalculating expressions. For
    //     example, trivial print nodes are evaluated, which can remove globals from the tree,
    //     but the gencode needs to find these so that their proto types can be used to bootstrap
    // development mode compilation.
    return parse(
        passManagerBuilder()
            .optimize(false)
            // Don't desugar, this is a bit of a waste of time and it destroys type
            // information about @state parameters
            .desugarHtmlAndStateNodes(false));
  }

  // Parse the current file set.
  @VisibleForTesting
  ParseResult parse() {
    return parse(passManagerBuilder());
  }

  private ParseResult parse(PassManager.Builder builder) {
    return parse(builder, typeRegistry);
  }

  private ParseResult parse(PassManager.Builder builder, SoyTypeRegistry typeRegistry) {
    return SoyFileSetParser.newBuilder()
        .setCache(cache)
        .setSoyFileSuppliers(soyFileSuppliers)
        .setCompilationUnits(compilationUnits)
        .setCssRegistry(cssRegistry)
        .setTypeRegistry(typeRegistry)
        .setPassManager(builder.setTypeRegistry(typeRegistry).build())
        .setErrorReporter(errorReporter)
        .build()
        .parse();
  }

  private PassManager.Builder passManagerBuilder() {
    return new PassManager.Builder()
        .setGeneralOptions(generalOptions)
        .optimize(optimize)
        .setSoyPrintDirectives(printDirectives)
        .setCssRegistry(cssRegistry)
        .setErrorReporter(errorReporter)
        .setConformanceConfig(conformanceConfig)
        .setLoggingConfig(loggingConfig)
        .setPluginResolver(
            new PluginResolver(
                skipPluginValidation
                    ? PluginResolver.Mode.ALLOW_UNDEFINED
                    : PluginResolver.Mode.REQUIRE_DEFINITIONS,
                printDirectives,
                soyFunctions,
                soySourceFunctions,
                soyMethods,
                errorReporter));
  }

  /**
   * This method resets the error reporter field in preparation to a new compiler invocation.
   *
   * <p>This method should be called at the beginning of every entry point into SoyFileSet.
   */
  @VisibleForTesting
  void resetErrorReporter() {
    errorReporter = ErrorReporter.create(soyFileSuppliers);
  }

  private void throwIfErrorsPresent() {
    if (errorReporter.hasErrors()) {
      // if we are reporting errors we should report warnings at the same time.
      Iterable<SoyError> errors =
          Iterables.concat(errorReporter.getErrors(), errorReporter.getWarnings());
      // clear the field to ensure that error reporters can't leak between compilations
      errorReporter = null;
      throw new SoyCompilationException(errors);
    }
  }

  /**
   * Reports warnings ot the user configured warning sink. Should be called at the end of successful
   * compiles.
   */
  private void reportWarnings() {
    ImmutableList<SoyError> warnings = errorReporter.getWarnings();
    if (warnings.isEmpty()) {
      return;
    }
    // this is a custom feature used by the integration test suite.
    if (generalOptions.getExperimentalFeatures().contains("testonly_throw_on_warnings")) {
      errorReporter = null;
      throw new SoyCompilationException(warnings);
    }
    String formatted = SoyErrors.formatErrors(warnings);
    if (warningSink != null) {
      try {
        warningSink.append(formatted);
      } catch (IOException ioe) {
        System.err.println("error while printing warnings");
        ioe.printStackTrace();
      }
    } else {
      logger.warning(formatted);
    }
  }
}
