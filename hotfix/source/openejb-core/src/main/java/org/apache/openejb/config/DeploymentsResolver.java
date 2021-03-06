/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openejb.config;

import org.apache.openejb.config.sys.Deployments;
import org.apache.openejb.config.sys.JaxbOpenejb;
import org.apache.openejb.loader.FileUtils;
import org.apache.openejb.loader.Options;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.util.Logger;
import org.apache.xbean.finder.UrlSet;
import org.apache.xbean.finder.filter.Filters;
import org.apache.xbean.finder.filter.IncludeExcludeFilter;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.apache.openejb.util.URLs.toFile;

/**
 * @version $Rev: 1185391 $ $Date: 2011-10-17 14:50:33 -0700 (Mon, 17 Oct 2011) $
 */
public class DeploymentsResolver implements DeploymentFilterable {

    private static final Logger logger = DeploymentLoader.logger;

    public static void loadFrom(Deployments dep, FileUtils path, List<URL> jarList) {

        ////////////////////////////////
        //
        //  Expand the path of a jar
        //
        ////////////////////////////////
        if (dep.getDir() == null && dep.getJar() != null) {
            try {
                File jar = path.getFile(dep.getJar(), false);
                if (!jarList.contains(jar.getAbsolutePath())) {
                    jarList.add(jar.toURI().toURL());
                }
            } catch (Exception ignored) {
            }
            return;
        }

        File dir = null;
        try {
            dir = path.getFile(dep.getDir(), false);
        } catch (Exception ignored) {
        }

        if (dir == null || !dir.isDirectory()) return;

        ////////////////////////////////
        //
        //  Unpacked "Jar" directory with descriptor
        //
        ////////////////////////////////
        File ejbJarXml = new File(dir, "META-INF" + File.separator + "ejb-jar.xml");
        if (ejbJarXml.exists()) {
            try {
                if (!jarList.contains(dir.getAbsolutePath())) {
                    jarList.add(dir.toURI().toURL());
                }
            } catch (MalformedURLException ignore) {
            }
            return;
        }

        File appXml = new File(dir, "META-INF" + File.separator + "application.xml");
        if (appXml.exists()) {
            try {
                if (!jarList.contains(dir.getAbsolutePath())) {
                    jarList.add(dir.toURI().toURL());
                }
            } catch (MalformedURLException ignore) {
            }
            return;
        }

        File raXml = new File(dir, "META-INF" + File.separator + "ra.xml");
        if (raXml.exists()) {
            try {
                if (!jarList.contains(dir.getAbsolutePath())) {
                    jarList.add(dir.toURI().toURL());
                }
            } catch (MalformedURLException ignore) {
            }
            return;
        }

        ////////////////////////////////
        //
        //  Directory contains Jar files
        //
        ////////////////////////////////
        boolean hasNestedArchives = false;
        for (File file : dir.listFiles()) {
            try {
                if (file.getName().endsWith(".jar") || file.getName().endsWith(".war")|| file.getName().endsWith(".rar")|| file.getName().endsWith(".ear")) {
                    if (jarList.contains(file.getAbsolutePath())) continue;
                    jarList.add(file.toURI().toURL());
                    hasNestedArchives = true;
                } else if (new File(file, "META-INF").exists()){ // Unpacked ear or jar
                    jarList.add(file.toURI().toURL());
                    hasNestedArchives = true;
                } else if (new File(file, "WEB-INF").exists()){  // Unpacked webapp
                    jarList.add(file.toURI().toURL());
                    hasNestedArchives = true;
                }
            } catch (Exception ignore) {
            }
        }

        ////////////////////////////////
        //
        //  Unpacked "Jar" directory w/o descriptor
        //
        ////////////////////////////////
        if (!hasNestedArchives) {
            HashMap<String, URL> files = new HashMap<String, URL>();
            DeploymentLoader.scanDir(dir, files, "");
            for (String fileName : files.keySet()) {
                if (fileName.endsWith(".class")) {
                    try {
                        if (!jarList.contains(dir.getAbsolutePath())) {
                            jarList.add(dir.toURI().toURL());
                        }
                    } catch (MalformedURLException ignore) {
                    }
                    return;
                }
            }
        }
    }

    /**
     * The algorithm of OpenEJB deployments class-path inclusion and exclusion is implemented as follows:
     * 1- If the string value of the resource URL matches the include class-path pattern
     * Then load this resource
     * 2- If the string value of the resource URL matches the exclude class-path pattern
     * Then ignore this resource
     * 3- If the include and exclude class-path patterns are not defined
     * Then load this resource
     * <p/>
     * The previous steps are based on the following points:
     * 1- Include class-path pattern has the highest priority
     * This helps in case both patterns are defined using the same values.
     * This appears in step 1 and 2 of the above algorithm.
     * 2- Loading the resource is the default behaviour in case of not defining a value for any class-path pattern
     * This appears in step 3 of the above algorithm.
     */
    public static void loadFromClasspath(FileUtils base, List<URL> jarList, ClassLoader classLoader) {
        Options options = SystemInstance.get().getOptions();
        String include = options.get(CLASSPATH_INCLUDE, ".*");
        String exclude = options.get(CLASSPATH_EXCLUDE, "");
        Set<RequireDescriptors> requireDescriptors = options.getAll(CLASSPATH_REQUIRE_DESCRIPTOR, RequireDescriptors.CLIENT);
        boolean filterDescriptors = options.get(CLASSPATH_FILTER_DESCRIPTORS, false);
        boolean filterSystemApps = options.get(CLASSPATH_FILTER_SYSTEMAPPS, true);

        try {
            UrlSet urlSet = new UrlSet(classLoader);
            UrlSet includes = urlSet.matching(include);
            urlSet = urlSet.exclude(ClassLoader.getSystemClassLoader().getParent());
            urlSet = urlSet.excludeJavaExtDirs();
            urlSet = urlSet.excludeJavaEndorsedDirs();
            urlSet = urlSet.excludeJavaHome();
            urlSet = urlSet.excludePaths(System.getProperty("sun.boot.class.path", ""));
            urlSet = urlSet.exclude(".*/JavaVM.framework/.*");

            // save the prefiltered list of jars before excluding system apps
            // so that we can choose not to filter modules with descriptors on the full list
            UrlSet prefiltered = urlSet;

            // we should exclude system apps before and apply user properties after

            final IncludeExcludeFilter filter = new IncludeExcludeFilter(Filters.patterns(include), Filters.patterns(exclude));
            // filter using user parameters
            urlSet = urlSet.filter(filter);

            if (prefiltered.size() == urlSet.size()) {
                urlSet = NewLoaderLogic.applyBuiltinExcludes(urlSet);

                if (filterSystemApps){
                    urlSet = urlSet.exclude(".*/openejb-[^/]+(.(jar|ear|war)(!/)?|/target/(test-)?classes/?)");
                }
            }


            List<URL> urls = urlSet.getUrls();
            int size = urls.size();
            if (size == 0 && include.length() > 0) {
                logger.warning("No classpath URLs matched.  Current settings: " + CLASSPATH_EXCLUDE + "='" + exclude + "', " + CLASSPATH_INCLUDE + "='" + include + "'");
                return;
            } else if (size == 0 && (!filterDescriptors && prefiltered.getUrls().size() == 0)) {
                return;
            } else if (size < 20) {
                logger.debug("Inspecting classpath for applications: " + urls.size() + " urls.");
            } else {
                // Has the user allowed some module types to be discoverable via scraping?
                boolean willScrape = requireDescriptors.size() < RequireDescriptors.values().length;

                if (size < 50 && willScrape) {
                    logger.info("Inspecting classpath for applications: " + urls.size() + " urls. Consider adjusting your exclude/include.  Current settings: " + CLASSPATH_EXCLUDE + "='" + exclude + "', " + CLASSPATH_INCLUDE + "='" + include + "'");
                } else if (willScrape) {
                    logger.warning("Inspecting classpath for applications: " + urls.size() + " urls.");
                    logger.warning("ADJUST THE EXCLUDE/INCLUDE!!!.  Current settings: " + CLASSPATH_EXCLUDE + "='" + exclude + "', " + CLASSPATH_INCLUDE + "='" + include + "'");
                }
            }

            long begin = System.currentTimeMillis();
            processUrls(urls, classLoader, requireDescriptors, base, jarList);
            long end = System.currentTimeMillis();
            long time = end - begin;

            UrlSet unchecked = new UrlSet();
            if (!filterDescriptors){
                unchecked = prefiltered.exclude(urlSet);
                if (filterSystemApps){
                    unchecked = unchecked.exclude(".*/openejb-[^/]+(.(jar|ear|war)(./)?|/target/classes/?)");
                }
                processUrls(unchecked.getUrls(), classLoader, EnumSet.allOf(RequireDescriptors.class), base, jarList);
            }

            if (logger.isDebugEnabled()) {
                int urlCount = urlSet.getUrls().size() + unchecked.getUrls().size();
                logger.debug("URLs after filtering: "+ urlCount);
                for (URL url : urlSet.getUrls()) {
                    logger.debug("Annotations path: " + url);
                }
                for (URL url : unchecked.getUrls()) {
                    logger.debug("Descriptors path: " + url);
                }
            }

            if (urls.size() == 0) return;

            if (time < 1000) {
                logger.debug("Searched " + urls.size() + " classpath urls in " + time + " milliseconds.  Average " + (time / urls.size()) + " milliseconds per url.");
            } else if (time < 4000 || urls.size() < 3) {
                logger.info("Searched " + urls.size() + " classpath urls in " + time + " milliseconds.  Average " + (time / urls.size()) + " milliseconds per url.");
            } else if (time < 10000) {
                logger.warning("Searched " + urls.size() + " classpath urls in " + time + " milliseconds.  Average " + (time / urls.size()) + " milliseconds per url.");
                logger.warning("Consider adjusting your " + CLASSPATH_EXCLUDE + " and " + CLASSPATH_INCLUDE + " settings.  Current settings: exclude='" + exclude + "', include='" + include + "'");
            } else {
                logger.fatal("Searched " + urls.size() + " classpath urls in " + time + " milliseconds.  Average " + (time / urls.size()) + " milliseconds per url.  TOO LONG!");
                logger.fatal("ADJUST THE EXCLUDE/INCLUDE!!!.  Current settings: " + CLASSPATH_EXCLUDE + "='" + exclude + "', " + CLASSPATH_INCLUDE + "='" + include + "'");
                List<String> list = new ArrayList<String>();
                for (URL url : urls) {
                    list.add(url.toExternalForm());
                }
                Collections.sort(list);
                for (String url : list) {
                    logger.info("Matched: " + url);
                }
            }
        } catch (IOException e1) {
            e1.printStackTrace();
            logger.warning("Unable to search classpath for modules: Received Exception: " + e1.getClass().getName() + " " + e1.getMessage(), e1);
        }

    }

    /**
     * The regular expressions involved in filtering can be costly
     * In the normal case we will not scan anyway, so if not
     * no point in optimizing the list of urls in the classpath
     *
     * @param include
     * @param exclude
     * @param requireDescriptors
     * @return
     */
    private static boolean shouldFilter(String include, String exclude, Set<RequireDescriptors> requireDescriptors) {
        boolean includeNothing = include.equals("");
        boolean excludeEverything = exclude.equals(".*");

        //  If we are going to eliminate the entire classpath from
        //  scanning anyway, no sense in taking the time to do it
        //  bit by bit.  Return false
        if (includeNothing && excludeEverything) return false;

        //  If we are forcably requiring descriptors for all possible file types
        //  then there is also no scanning and no point in filtering the
        //  classpath down bit by bit.  Return false
        if (requireDescriptors.size() == RequireDescriptors.values().length) return false;

        return true;
    }

    private static UrlSet applyBuiltinExcludes(UrlSet urlSet) throws MalformedURLException {
        
        urlSet = urlSet.exclude(".*/activation(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/activeio-core(-[\\d.]+)?(-incubator)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/activemq-(core|ra)(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/annotations-api-6.[01].[\\d.]+.jar(!/)?");
        urlSet = urlSet.exclude(".*/asm-(all|commons|util|tree)?[\\d.]+.jar(!/)?");
        urlSet = urlSet.exclude(".*/avalon-framework(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/axis2-jaxws-api(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/backport-util-concurrent(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/bcprov-jdk15(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/catalina(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/cglib-(nodep-)?[\\d.]+.jar(!/)?");
        urlSet = urlSet.exclude(".*/com\\.ibm\\.ws\\.[^/]*.jar(!/)?");
        urlSet = urlSet.exclude(".*/commons-(logging|logging-api|cli|pool|lang|collections|dbcp|dbcp-all)(-[\\d.r-]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/cxf-bundle(-[\\d.]+)?(incubator)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/openejb-cxf-bundle(-[\\d.]+)?(incubator)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/derby(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/ejb31-api-experimental(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/geronimo-(connector|transaction)(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/geronimo-[^/]+_spec(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/geronimo-javamail_([\\d.]+)_mail(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/hibernate-(entitymanager|annotations)?(-[\\d.]+(ga)?)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/howl(-[\\d.-]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/hsqldb(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/idb(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/idea_rt.jar(!/)?");
        urlSet = urlSet.exclude(".*/javaee-api(-embedded)?-[\\d.-]+.jar(!/)?");
        urlSet = urlSet.exclude(".*/javassist[^/]*.jar(!/)?");
        urlSet = urlSet.exclude(".*/jaxb-(impl|api)(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/jboss-[^/]*.jar(!/)?");
        urlSet = urlSet.exclude(".*/jbossall-[^/]*.jar(!/)?");
        urlSet = urlSet.exclude(".*/jbosscx-[^/]*.jar(!/)?");
        urlSet = urlSet.exclude(".*/jbossjts-?[^/]*.jar(!/)?");
        urlSet = urlSet.exclude(".*/jbosssx-[^/]*.jar(!/)?");
        urlSet = urlSet.exclude(".*/jmdns(-[\\d.]+)?(-RC\\d)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/juli(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/junit(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/log4j(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/logkit(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/mail(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/neethi(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/org\\.eclipse\\.persistence\\.[^/]*.jar(!/)?");
        urlSet = urlSet.exclude(".*/org\\.junit_.[^/]*.jar(!/)?");
        urlSet = urlSet.exclude(".*/openjpa-(jdbc|kernel|lib|persistence|persistence-jdbc)(-5)?(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/openjpa(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/opensaml(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/quartz(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/saaj-impl(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/serp(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/servlet-api(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/slf4j-api(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/slf4j-jdk14(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/stax-api(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/swizzle-stream(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/sxc-(jaxb|runtime)(-[\\d.]+)?(-SNAPSHOT)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/wsdl4j(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/wss4j(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/wstx-asl(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/xbean-(reflect|naming|finder)-(shaded-)?[\\d.]+.jar(!/)?");
        urlSet = urlSet.exclude(".*/xmlParserAPIs(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/xmlunit(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/xmlsec(-[\\d.]+)?.jar(!/)?");
        urlSet = urlSet.exclude(".*/XmlSchema(-[\\d.]+)?.jar(!/)?");
        return urlSet;
    }

    public static void processUrls(List<URL> urls, ClassLoader classLoader, Set<RequireDescriptors> requireDescriptors, FileUtils base, List<URL> jarList) {
        for (URL url : urls) {

            String urlProtocol = url.getProtocol();
            //Currently, we only support jar and file protocol
            boolean isValidURL = urlProtocol.equals("jar") || urlProtocol.equals("file");
            if (!isValidURL) {
                logger.warning("Unknown protocol " + urlProtocol);
                continue;
            }

            Deployments deployment;
            String path = "";
            try {
                
                DeploymentLoader deploymentLoader = new DeploymentLoader();
                
                Class<? extends DeploymentModule> moduleType = deploymentLoader.discoverModuleType(url, classLoader, requireDescriptors);
                if (AppModule.class.isAssignableFrom(moduleType) || EjbModule.class.isAssignableFrom(moduleType) || PersistenceModule.class.isAssignableFrom(moduleType) || ConnectorModule.class.isAssignableFrom(moduleType) || ClientModule.class.isAssignableFrom(moduleType)) {
                    deployment = JaxbOpenejb.createDeployments();
                    if (urlProtocol.equals("jar")) {
                        url = new URL(url.getFile().replaceFirst("!.*$", ""));
                        File file = toFile(url);
                        path = file.getAbsolutePath();
                        deployment.setJar(path);
                    } else if (urlProtocol.equals("file")) {
                        File file = toFile(url);
                        path = file.getAbsolutePath();
                        deployment.setDir(path);
                    }
                    logger.info("Found " + moduleType.getSimpleName() + " in classpath: " + path);

                    if (AppModule.class.isAssignableFrom(moduleType) || ConnectorModule.class.isAssignableFrom(moduleType)) {
                        loadFrom(deployment, base, jarList);
                    } else {
                        if (!jarList.contains(path)){
                            jarList.add(url);
                        }
                    }

                }
            } catch (IOException e) {
                logger.warning("Unable to determine the module type of " + url.toExternalForm() + ": Exception: " + e.getMessage(), e);
            } catch (UnknownModuleTypeException ignore) {
            }
        }
    }
}
