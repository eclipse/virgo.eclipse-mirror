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

import org.apache.openejb.loader.FileUtils;
import org.apache.openejb.loader.Options;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.util.Join;
import org.apache.openejb.util.Logger;
import org.apache.openejb.util.PerformanceTimer;
import org.apache.openejb.util.URLs;
import org.apache.xbean.finder.UrlSet;
import org.apache.xbean.finder.archive.Archive;
import org.apache.xbean.finder.archive.ClasspathArchive;
import org.apache.xbean.finder.archive.FilteredArchive;
import org.apache.xbean.finder.filter.Filter;
import org.apache.xbean.finder.filter.Filters;
import org.apache.xbean.finder.filter.IncludeExcludeFilter;
import org.apache.xbean.finder.filter.PatternFilter;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
* @version $Rev$ $Date$
*/
public class NewLoaderLogic {

    private static final Logger logger = DeploymentLoader.logger;
    
    public static UrlSet filterArchives(Filter filter, ClassLoader classLoader, UrlSet urlSet) {

        for (URL url : urlSet.getUrls()) {
            for (Archive archive : ClasspathArchive.archives(classLoader, url)) {

                FilteredArchive filtered = new FilteredArchive(archive, filter);

                if (!filtered.iterator().hasNext()) {
                    urlSet = urlSet.exclude(url);
                }

            }
        }

        return urlSet;
    }

    public static Set<String> callers() {

        final Set<String> callers = new LinkedHashSet<String>();

        final List<StackTraceElement> elements = new ArrayList<StackTraceElement>(Arrays.asList(new Exception().fillInStackTrace().getStackTrace()));

        // Yank out everything until we find a known ENTRY point
        // if we don't find one, so be it, this is only a convenience
        {
            // Entry points are the following:
            Filter start = Filters.classes("javax.ejb.embeddable.EJBContainer", "javax.naming.InitialContext");

            Iterator<StackTraceElement> iterator = elements.iterator();
            while (iterator.hasNext()) {
                StackTraceElement element = iterator.next();
                iterator.remove();

                // If we haven't yet reached an entry point, just keep going
                if (!start.accept(element.getClassName())) continue;

                // We found an entry point.
                // Fast-forward past this class
                while(iterator.hasNext()&&element.getClassName().equals(iterator.next().getClassName())) iterator.remove();

                // Ok, we have iterated up to the calling user class, so stop now
                break;
            }
        }


        // Now iterate till we find an END point
        // We don't want any of the classes after that
        {
            Filter end = Filters.packages(
                    "junit.",
                    "org.junit.",
                    "org.testng.",
                    "org.apache.maven.",
                    "org.eclipse.",
                    "com.intellij."
            );

            // Everything between here and the end is part
            // of the call chain in which we are interested
            Iterator<StackTraceElement> iterator = elements.iterator();
            while (iterator.hasNext()) {
                StackTraceElement element = iterator.next();

                if (end.accept(element.getClassName())) break;

                callers.add(element.getClassName());
            }
        }

        // We don't need this anymore
        elements.clear();

        // Finally filter out everything that we definitely don't want
        {
            Filter unwanted = Filters.packages(
                    "java.",
                    "javax.",
                    "sun.reflect."
            );

            Iterator<String> classes = callers.iterator();
            while (classes.hasNext()) {
                if (unwanted.accept(classes.next())) classes.remove();
            }
        }


        return callers;
    }

    public static UrlSet applyBuiltinExcludes(UrlSet urlSet) throws MalformedURLException {

        Filter filter = Filters.prefixes(
        		"activation-",
        		"activeio-",
        		"activemq-",
        		"aether-",
        		"antlr-",
        		"aopalliance-",
        		"ApacheJMeter",
        		"arquillian-common",
        		"arquillian-config-",
        		"arquillian-container-",
        		"arquillian-core-api-",
        		"arquillian-core-impl-base",
        		"arquillian-core-spi-",
        		"arquillian-junit-",
        		"arquillian-test-api",
        		"arquillian-test-impl-base",
        		"arquillian-test-spi",
        		"arquillian-tomee-",
        		"avalon-framework-",
        		"axis-",
        		"axis2-",
        		"access-bridge.jar",
        		"access-bridge-64.jar",
        		"annotations-api.jar",
        		"ant.jar",
        		"ant-junit*.jar",
        		"ant-launcher.jar",
        		"apple_provider.jar",
        		"AppleScriptEngine.jar",
        		"aspectj*.jar",
        		"bootstrap.jar",
        		"bcprov-",
        		"bval-core",
        		"bval-jsr",
        		"c3p0-",
        		"catalina.jar",
        		"catalina-",
        		"cglib-",
        		"commons-beanutils",
        		"commons-cli-",
        		"commons-codec-",
        		"commons-collections-",
        		"commons-daemon.jar",
        		"commons-dbcp",
        		"commons-dbcp-all-1.3-",
        		"commons-digester-",
        		"commons-fileupload",
        		"commons-discovery-",
        		"commons-httpclient-",
        		"commons-io-",
        		"commons-lang-",
        		"commons-lang3-",
        		"commons-logging-",
        		"commons-logging-api-",
        		"commons-math",
        		"commons-net-",
        		"commons-pool-",
        		"cssparser-",
        		"cxf-",
        		"CoreAudio.jar",
        		"dns_sd.jar",
        		"dnsns.jar",
        		"deploy.jar",
        		"derby-",
        		"derbyclient-",
        		"derbynet-",
        		"dom4j-",
        		"ecj-",
        		"eclipselink-",
        		"el-api.jar",
        		"jsp-api.jar",
        		"fusemq-leveldb-",
        		"geronimo-",
        		"google-",
        		"gragent.jar",
        		"gnome-java-bridge.jar",
        		"groovy-",
        		"gson",
        		"guice-",
        		"hamcrest-",
        		"hawtbuf-",
        		"hawtdispatch-",
        		"hawtjni-runtime",
        		"hibernate-",
        		"howl-",
        		"hsqldb-",
        		"htmlunit-",
        		"httpclient-",
        		"httpcore-",
        		"icu4j-",
        		"idb-",
        		"idea_rt.jar",
        		"jackson-mapper-asl-",
        		"jansi-",
        		"j3daudio.jar",
        		"j3dcore.jar",
        		"j3dutils.jar",
        		"jaccess.jar",
        		"jai_codec.jar",
        		"jai_core.jar",
        		"jasypt-",
        		"javaee-",
        		"javaee-api",
        		"javassist-",
        		"javaws.jar",
        		"javax.",
        		"jaxb-",
        		"jaxp-",
        		"jboss-",
        		"jasper.jar",
        		"jasper-el.jar",
        		"jhall.jar",
        		"jmx.jar",
        		"jmx-tools.jar",
        		"jstl.jar",
        		"jbossall-",
        		"jbosscx-",
        		"jbossjts-",
        		"jbosssx-",
        		"jcommander-",
        		"jersey-",
        		"jettison-",
        		"jetty",
        		"jline",
        		"jmdns-",
        		"joda-time-",
        		"jta",
        		"jsoup-",
        		"jsp-api",
        		"jsr299-",
        		"jsr311-",
        		"juli-",
        		"junit-",
        		"kahadb-",
        		"leveldb",
        		"log4j-",
        		"logkit-",
        		"lucene-analyzers-",
        		"lucene-core-",
        		"ldapsec.jar",
        		"localedata.jar",
        		"mail",
        		"maven-",
        		"mbean-annotation-api-",
        		"mimepull-",
        		"mina-",
        		"mqtt-client-",
        		"myfaces-api",
        		"myfaces-impl",
        		"MRJToolkit.jar",
        		"pulse-java.jar",
        		"servlet-api.jar",
        		"servlet-api.jar",
        		"neethi-",
        		"nekohtml-",
        		"openejb-api",
        		"openejb-client",
        		"openejb-core",
        		"openejb-cxf",
        		"openejb-cxf-bundle",
        		"openejb-cxf-rs",
        		"openejb-cxf-transport",
        		"openejb-derby",
        		"openejb-ejbd",
        		"openejb-hsql",
        		"openejb-http",
        		"openejb-javaagent",
        		"openejb-jee",
        		"openejb-jpa-integration",
        		"openejb-jsf",
        		"openejb-jstl",
        		"openejb-loader",
        		"openejb-openwebbeans",
        		"openejb-provisionning",
        		"openejb-rest",
        		"openejb-server",
        		"openejb-webservices",
        		"openjpa-",
        		"opensaml-",
        		"openwebbeans-",
        		"openws-",
        		"ops4j-",
        		"org.eclipse.",
        		"org.junit.",
        		"org.osgi.core-",
        		"oro-",
        		"pax-",
        		"plexus-",
        		"quartz-",
        		"rmock-",
        		"saaj-",
        		"sac-",
        		"scannotation-",
        		"serializer-",
        		"serp-",
        		"servlet-api-",
        		"shrinkwrap-",
        		"sisu-guice",
        		"sisu-inject",
        		"slf4j-",
        		"snappy-java-",
        		"spring-",
        		"sshd-",
        		"stax-api-",
        		"surefire-",
        		"swizzle-",
        		"testng-",
        		"tomcat-",
        		"tomee-",
        		"velocity-",
        		"wagon-",
        		"webbeans-ee",
        		"webbeans-ejb",
        		"webbeans-impl",
        		"webbeans-spi",
        		"woodstox-core-asl-",
        		"wsdl4j-",
        		"wss4j-",
        		"wstx-asl-",
        		"xalan-",
        		"xbean-",
        		"xercesImpl-",
        		"xml-apis-",
        		"xml-resolver-",
        		"xmlrpc-",
        		"xmlschema-",
        		"XmlSchema-",
        		"sunec.jar",
        		"sunjce_provider.jar",
        		"sunmscapi.jar",
        		"sunpkcs11.jar",
        		"tomcat-api.jar",
        		"tomcat-coyote.jar",
        		"tomcat-dbcp.jar",
        		"tomcat-i18n-en.jar",
        		"tomcat-i18n-es.jar",
        		"tomcat-i18n-fr.jar",
        		"tomcat-i18n-ja.jar",
        		"tomcat-jdbc.jar",
        		"tomcat-jni.jar",
        		"tomcat-spdy.jar",
        		"tomcat-juli.jar",
        		"tomcat-juli-adapters.jar",
        		"tomcat-util.jar",
        		"tools.jar",
        		"vecmath.jar",
        		"wsdl4j*.jar",
        		"xercesImpl.jar",
        		"xml-apis.jar",
        		"xmlParserAPIs.jar",
        		"zipfs.jar",
        		"xmlsec-",
        		"xmltooling-",
        		"xmlunit-",
        		"ziplock-"
        );

//        filter = Filters.optimize(filter, new PatternFilter(".*/openejb-.*"));
        List<URL> urls = urlSet.getUrls();
        Iterator<URL> iterator = urls.iterator();
        while (iterator.hasNext()) {
            URL url = iterator.next();
            File file = URLs.toFile(url);

            String name = filter(file).getName();
//            System.out.println("JAR "+name);
            if (filter.accept(name)) iterator.remove();
        }



        return new UrlSet(urls);
    }

    private static File filter(File location) {
        List<String> invalid = new ArrayList<String>();
        invalid.add("classes");
        invalid.add("test-classes");
        invalid.add("target");
        invalid.add("build");
        invalid.add("dist");
        invalid.add("bin");

        while (invalid.contains(location.getName())) {
            location = location.getParentFile();
        }
        return location;
    }


    public static void _loadFromClasspath(FileUtils base, List<URL> jarList, ClassLoader classLoader) {

        PerformanceTimer timer = new PerformanceTimer();

        timer.event("create filters");
        Options options = SystemInstance.get().getOptions();
        String include = "";
        String exclude = "";
        PatternFilter classpathInclude = new PatternFilter(options.get(DeploymentFilterable.CLASSPATH_INCLUDE, ".*"));
        PatternFilter classpathExclude = new PatternFilter(options.get(DeploymentFilterable.CLASSPATH_EXCLUDE, ""));
        IncludeExcludeFilter classpathFilter = new IncludeExcludeFilter(classpathInclude, classpathExclude);


        PatternFilter packageInclude = new PatternFilter(options.get(DeploymentFilterable.PACKAGE_INCLUDE, ".*"));
        PatternFilter packageExclude = new PatternFilter(options.get(DeploymentFilterable.PACKAGE_EXCLUDE, ""));

        IncludeExcludeFilter packageFilter;
        if (classpathInclude.getPattern().pattern().equals(".*") && packageInclude.getPattern().pattern().equals(".*")) {

            timer.event("callers");

            Set<String> callers = callers();

            timer.event("parse packages");

            callers.size();

            Set<String> packages = new HashSet<String>();
            for (String caller : callers) {
                String[] parts = caller.split("\\.");
                if (parts.length > 2) {
                    parts = new String[]{parts[0], parts[1]};
                }
                packages.add(Join.join(".", parts));
            }

            Filter includes = Filters.packages(packages.toArray(new String[0]));

            packageFilter = new IncludeExcludeFilter(includes, packageExclude);

        } else {

            packageFilter = new IncludeExcludeFilter(packageInclude, packageExclude);

        }

        timer.event("urlset");

        Set<RequireDescriptors> requireDescriptors = options.getAll(DeploymentFilterable.CLASSPATH_REQUIRE_DESCRIPTOR, RequireDescriptors.CLIENT);
        boolean filterDescriptors = options.get(DeploymentFilterable.CLASSPATH_FILTER_DESCRIPTORS, false);
        boolean filterSystemApps = options.get(DeploymentFilterable.CLASSPATH_FILTER_SYSTEMAPPS, true);

        try {
            UrlSet urlSet = new UrlSet(classLoader);

            timer.event("exclude system urls");
            urlSet = urlSet.exclude(ClassLoader.getSystemClassLoader().getParent());
            urlSet = urlSet.excludeJavaExtDirs();
            urlSet = urlSet.excludeJavaEndorsedDirs();
            urlSet = urlSet.excludeJavaHome();
            urlSet = urlSet.excludePaths(System.getProperty("sun.boot.class.path", ""));
            urlSet = urlSet.exclude(".*/JavaVM.framework/.*");


            timer.event("classpath filter");

            UrlSet beforeFiltering = urlSet;

            urlSet = urlSet.filter(classpathFilter);


            // If the user filtered out too much, that's a problem
            if (urlSet.size() == 0) {
                final String message = String.format("Classpath Include/Exclude resulted in zero URLs.  There were %s possible URLs before filtering and 0 after: include=\"%s\", exclude=\"%s\"", beforeFiltering.size(), include, exclude);
                logger.error(message);
                logger.info("Eligible Classpath before filtering:");

                for (URL url : beforeFiltering) {
                    logger.info(String.format("   %s", url.toExternalForm()));
                }
//                throw new IllegalStateException(message);

            }

            // If they are the same size, than nothing was filtered
            // and we know the user did not take action to change the default
            final boolean userSuppliedClasspathFilter = beforeFiltering.size() != urlSet.size();

            if (!userSuppliedClasspathFilter) {

                logger.info("Applying buildin classpath excludes");
                timer.event("buildin excludes");
                urlSet = applyBuiltinExcludes(urlSet);

            }

            DeploymentsResolver.processUrls(urlSet.getUrls(), classLoader, EnumSet.allOf(RequireDescriptors.class), base, jarList);


            timer.event("package filter");

            urlSet = filterArchives(packageFilter, classLoader, urlSet);

            timer.event("process urls");

            // we should exclude system apps before and apply user properties after
//            if (filterSystemApps){
//                urlSet = urlSet.exclude(".*/openejb-[^/]+(.(jar|ear|war)(!/)?|/target/(test-)?classes/?)");
//            }

            List<URL> urls = urlSet.getUrls();
            int size = urls.size();
//            if (size == 0) {
//                logger.warning("No classpath URLs matched.  Current settings: " + CLASSPATH_EXCLUDE + "='" + exclude + "', " + CLASSPATH_INCLUDE + "='" + include + "'");
//                return;
//            } else if (size == 0 && (!filterDescriptors && prefiltered.getUrls().size() == 0)) {
//                return;
//            } else if (size < 20) {
//                logger.debug("Inspecting classpath for applications: " + urls.size() + " urls.");
//            } else {
//                // Has the user allowed some module types to be discoverable via scraping?
//                boolean willScrape = requireDescriptors.size() < RequireDescriptors.values().length;
//
//                if (size < 50 && willScrape) {
//                    logger.info("Inspecting classpath for applications: " + urls.size() + " urls. Consider adjusting your exclude/include.  Current settings: " + CLASSPATH_EXCLUDE + "='" + exclude + "', " + CLASSPATH_INCLUDE + "='" + include + "'");
//                } else if (willScrape) {
//                    logger.warning("Inspecting classpath for applications: " + urls.size() + " urls.");
//                    logger.warning("ADJUST THE EXCLUDE/INCLUDE!!!.  Current settings: " + CLASSPATH_EXCLUDE + "='" + exclude + "', " + CLASSPATH_INCLUDE + "='" + include + "'");
//                }
//            }

            long begin = System.currentTimeMillis();
            DeploymentsResolver.processUrls(urls, classLoader, requireDescriptors, base, jarList);
            long end = System.currentTimeMillis();
            long time = end - begin;

            timer.stop(System.out);

            UrlSet unchecked = new UrlSet();
//            if (!filterDescriptors){
//                unchecked = prefiltered.exclude(urlSet);
//                if (filterSystemApps){
//                    unchecked = unchecked.exclude(".*/openejb-[^/]+(.(jar|ear|war)(./)?|/target/classes/?)");
//                }
                DeploymentsResolver.processUrls(unchecked.getUrls(), classLoader, EnumSet.allOf(RequireDescriptors.class), base, jarList);
//            }

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
                logger.warning("Consider adjusting your " + DeploymentFilterable.CLASSPATH_EXCLUDE + " and " + DeploymentFilterable.CLASSPATH_INCLUDE + " settings.  Current settings: exclude='" + exclude + "', include='" + include + "'");
            } else {
                logger.fatal("Searched " + urls.size() + " classpath urls in " + time + " milliseconds.  Average " + (time / urls.size()) + " milliseconds per url.  TOO LONG!");
                logger.fatal("ADJUST THE EXCLUDE/INCLUDE!!!.  Current settings: " + DeploymentFilterable.CLASSPATH_EXCLUDE + "='" + exclude + "', " + DeploymentFilterable.CLASSPATH_INCLUDE + "='" + include + "'");
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
}
