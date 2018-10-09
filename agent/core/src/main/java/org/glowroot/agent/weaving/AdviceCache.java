/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.weaving;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.PluginDescriptor;
import org.glowroot.agent.live.LiveWeavingServiceImpl;
import org.glowroot.agent.live.LiveWeavingServiceImpl.PointcutClassName;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;
import org.glowroot.agent.weaving.ClassLoaders.LazyDefinedClass;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Versions;

import static com.google.common.base.Preconditions.checkNotNull;

public class AdviceCache {

    private static final Logger logger = LoggerFactory.getLogger(AdviceCache.class);

    private static final AtomicInteger jarFileCounter = new AtomicInteger();

    private final ImmutableList<Advice> pluginAdvisors;
    private final ImmutableList<ShimType> shimTypes;
    private final ImmutableList<MixinType> mixinTypes;
    private final @Nullable Instrumentation instrumentation;
    private final File tmpDir;

    private volatile ImmutableList<Advice> reweavableAdvisors;
    private volatile ImmutableSet<String> reweavableConfigVersions;

    private volatile ImmutableList<Advice> allAdvisors;

    public AdviceCache(List<PluginDescriptor> pluginDescriptors,
            List<InstrumentationConfig> reweavableConfigs,
            @Nullable Instrumentation instrumentation, File tmpDir) throws Exception {

        List<Advice> pluginAdvisors = Lists.newArrayList();
        List<ShimType> shimTypes = Lists.newArrayList();
        List<MixinType> mixinTypes = Lists.newArrayList();
        Map<Advice, LazyDefinedClass> lazyAdvisors = Maps.newHashMap();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            for (String aspect : pluginDescriptor.aspects()) {
                try {
                    Class<?> aspectClass =
                            Class.forName(aspect, false, AdviceCache.class.getClassLoader());
                    pluginAdvisors.addAll(getAdvisors(aspectClass));
                    shimTypes.addAll(getShimTypes(aspectClass));
                    mixinTypes.addAll(getMixinTypes(aspectClass));
                } catch (ClassNotFoundException e) {
                    logger.warn("aspect not found: {}", aspect, e);
                }
            }
            List<InstrumentationConfig> instrumentationConfigs =
                    pluginDescriptor.instrumentationConfigs();
            for (InstrumentationConfig instrumentationConfig : instrumentationConfigs) {
                instrumentationConfig.logValidationErrorsIfAny();
            }
            lazyAdvisors.putAll(AdviceGenerator.createAdvisors(instrumentationConfigs,
                    pluginDescriptor.id(), false));
        }
        for (Map.Entry<Advice, LazyDefinedClass> entry : lazyAdvisors.entrySet()) {
            pluginAdvisors.add(entry.getKey());
        }
        if (instrumentation == null) {
            // instrumentation is null when debugging with LocalContainer
            ClassLoader isolatedWeavingClassLoader = Thread.currentThread().getContextClassLoader();
            checkNotNull(isolatedWeavingClassLoader);
            ClassLoaders.defineClassesInClassLoader(lazyAdvisors.values(),
                    isolatedWeavingClassLoader);
        } else {
            ClassLoaders.createDirectoryOrCleanPreviousContentsWithPrefix(tmpDir,
                    "plugin-pointcuts.jar");
            if (!lazyAdvisors.isEmpty()) {
                File jarFile = new File(tmpDir, "plugin-pointcuts.jar");
                ClassLoaders.defineClassesInBootstrapClassLoader(lazyAdvisors.values(),
                        instrumentation, jarFile);
            }
        }
        this.pluginAdvisors = ImmutableList.copyOf(pluginAdvisors);
        this.shimTypes = ImmutableList.copyOf(shimTypes);
        this.mixinTypes = ImmutableList.copyOf(mixinTypes);
        this.instrumentation = instrumentation;
        this.tmpDir = tmpDir;
        reweavableAdvisors =
                createReweavableAdvisors(reweavableConfigs, instrumentation, tmpDir, true);
        reweavableConfigVersions = createReweavableConfigVersions(reweavableConfigs);
        allAdvisors = ImmutableList.copyOf(Iterables.concat(pluginAdvisors, reweavableAdvisors));
    }

    public Supplier<List<Advice>> getAdvisorsSupplier() {
        return new Supplier<List<Advice>>() {
            @Override
            public List<Advice> get() {
                return allAdvisors;
            }
        };
    }

    @VisibleForTesting
    public List<ShimType> getShimTypes() {
        return shimTypes;
    }

    @VisibleForTesting
    public List<MixinType> getMixinTypes() {
        return mixinTypes;
    }

    public void initialReweave(Class<?>[] initialLoadedClasses) {
        Set<PointcutClassName> pointcutClassNames = Sets.newHashSet();
        for (Advice advice : allAdvisors) {
            PointcutClassName pointcutClassName = getPointcutClassName(advice);
            // don't add Runnable/Callable subclasses to initial reweave, since they won't work
            // anyways since too late to add mixin interface
            // this is just an optimization, and (importantly) to keep class retransformation down
            // to a minimum since it has been known to have some problems on some JVMs
            if (pointcutClassName != null
                    && !advice.adviceType().getInternalName().startsWith(
                            "org/glowroot/agent/plugin/executor/ExecutorAspect$RunnableAdvice")
                    && !advice.adviceType().getInternalName().startsWith(
                            "org/glowroot/agent/plugin/executor/ExecutorAspect$CallableAdvice")) {
                pointcutClassNames.add(pointcutClassName);
            }
        }
        LiveWeavingServiceImpl.initialReweave(pointcutClassNames, initialLoadedClasses,
                checkNotNull(instrumentation));
    }

    public void updateAdvisors(List<InstrumentationConfig> reweavableConfigs) throws Exception {
        reweavableAdvisors =
                createReweavableAdvisors(reweavableConfigs, instrumentation, tmpDir, false);
        reweavableConfigVersions = createReweavableConfigVersions(reweavableConfigs);
        allAdvisors = ImmutableList.copyOf(Iterables.concat(pluginAdvisors, reweavableAdvisors));
    }

    public boolean isOutOfSync(List<InstrumentationConfig> reweavableConfigs) {
        Set<String> versions = Sets.newHashSet();
        for (InstrumentationConfig reweavableConfig : reweavableConfigs) {
            versions.add(Versions.getVersion(reweavableConfig.toProto()));
        }
        return !versions.equals(this.reweavableConfigVersions);
    }

    private static List<Advice> getAdvisors(Class<?> aspectClass) {
        List<Advice> advisors = Lists.newArrayList();
        for (Class<?> memberClass : aspectClass.getClasses()) {
            if (memberClass.isAnnotationPresent(Pointcut.class)) {
                try {
                    advisors.add(new AdviceBuilder(memberClass).build());
                } catch (Throwable t) {
                    logger.error("error creating advice: {}", memberClass.getName(), t);
                }
            }
        }
        return advisors;
    }

    private static List<ShimType> getShimTypes(Class<?> aspectClass) {
        List<ShimType> shimTypes = Lists.newArrayList();
        for (Class<?> memberClass : aspectClass.getClasses()) {
            Shim shim = memberClass.getAnnotation(Shim.class);
            if (shim != null) {
                shimTypes.add(ShimType.create(shim, memberClass));
            }
        }
        return shimTypes;
    }

    private static List<MixinType> getMixinTypes(Class<?> aspectClass) throws IOException {
        List<MixinType> mixinTypes = Lists.newArrayList();
        for (Class<?> memberClass : aspectClass.getClasses()) {
            Mixin mixin = memberClass.getAnnotation(Mixin.class);
            if (mixin != null) {
                mixinTypes.add(MixinType.create(mixin, memberClass));
            }
        }
        return mixinTypes;
    }

    private static ImmutableList<Advice> createReweavableAdvisors(
            List<InstrumentationConfig> reweavableConfigs,
            @Nullable Instrumentation instrumentation, File tmpDir, boolean cleanTmpDir)
            throws Exception {
        ImmutableMap<Advice, LazyDefinedClass> advisors =
                AdviceGenerator.createAdvisors(reweavableConfigs, null, true);
        if (instrumentation == null) {
            // instrumentation is null when debugging with LocalContainer
            ClassLoader isolatedWeavingClassLoader =
                    Thread.currentThread().getContextClassLoader();
            checkNotNull(isolatedWeavingClassLoader);
            ClassLoaders.defineClassesInClassLoader(advisors.values(), isolatedWeavingClassLoader);
        } else {
            if (cleanTmpDir) {
                ClassLoaders.createDirectoryOrCleanPreviousContentsWithPrefix(tmpDir,
                        "config-pointcuts");
            }
            if (!advisors.isEmpty()) {
                String suffix = "";
                int count = jarFileCounter.incrementAndGet();
                if (count > 1) {
                    suffix = "-" + count;
                }
                File jarFile = new File(tmpDir, "config-pointcuts" + suffix + ".jar");
                ClassLoaders.defineClassesInBootstrapClassLoader(advisors.values(), instrumentation,
                        jarFile);
            }
        }
        return advisors.keySet().asList();
    }

    private static ImmutableSet<String> createReweavableConfigVersions(
            List<InstrumentationConfig> reweavableConfigs) {
        Set<String> versions = Sets.newHashSet();
        for (InstrumentationConfig reweavableConfig : reweavableConfigs) {
            versions.add(Versions.getVersion(reweavableConfig.toProto()));
        }
        return ImmutableSet.copyOf(versions);
    }

    private static @Nullable PointcutClassName getPointcutClassName(Advice advice) {
        PointcutClassName subTypeRestrictionPointcutClassName = null;
        Pattern subTypeRestrictionPattern = advice.pointcutSubTypeRestrictionPattern();
        if (subTypeRestrictionPattern != null) {
            subTypeRestrictionPointcutClassName =
                    PointcutClassName.fromPattern(subTypeRestrictionPattern, null, false);
        } else {
            String subTypeRestriction = advice.pointcut().subTypeRestriction();
            if (!subTypeRestriction.isEmpty()) {
                subTypeRestrictionPointcutClassName =
                        PointcutClassName.fromNonPattern(subTypeRestriction, null, false);
            }
        }
        Pattern classNamePattern = advice.pointcutClassNamePattern();
        if (classNamePattern != null) {
            return PointcutClassName.fromPattern(classNamePattern,
                    subTypeRestrictionPointcutClassName,
                    advice.pointcut().methodName().equals("<init>"));
        }
        String className = advice.pointcut().className();
        if (!className.isEmpty()) {
            return PointcutClassName.fromNonPattern(className, subTypeRestrictionPointcutClassName,
                    advice.pointcut().methodName().equals("<init>"));
        }
        return null;
    }

    // this method exists because tests cannot use (sometimes) shaded guava Supplier
    @OnlyUsedByTests
    public List<Advice> getAdvisors() {
        return getAdvisorsSupplier().get();
    }
}
