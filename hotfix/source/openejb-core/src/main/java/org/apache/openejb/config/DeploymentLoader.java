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

import org.apache.openejb.ClassLoaderUtil;
import org.apache.openejb.OpenEJB;
import org.apache.openejb.OpenEJBException;
import org.apache.openejb.api.LocalClient;
import org.apache.openejb.api.RemoteClient;
import org.apache.openejb.jee.Application;
import org.apache.openejb.jee.ApplicationClient;
import org.apache.openejb.jee.Beans;
import org.apache.openejb.jee.Connector;
import org.apache.openejb.jee.EjbJar;
import org.apache.openejb.jee.FacesConfig;
import org.apache.openejb.jee.JavaWsdlMapping;
import org.apache.openejb.jee.JaxbJavaee;
import org.apache.openejb.jee.JspConfig;
import org.apache.openejb.jee.Module;
import org.apache.openejb.jee.ParamValue;
import org.apache.openejb.jee.Taglib;
import org.apache.openejb.jee.TldTaglib;
import org.apache.openejb.jee.WebApp;
import org.apache.openejb.jee.WebserviceDescription;
import org.apache.openejb.jee.Webservices;
import org.apache.openejb.jee.oejb3.OpenejbJar;
import org.apache.openejb.loader.FileUtils;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.util.AnnotationFinder;
import org.apache.openejb.util.JarExtractor;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.openejb.util.URLs;
import org.apache.openejb.util.UrlCache;
import org.apache.xbean.finder.IAnnotationFinder;
import org.apache.xbean.finder.ResourceFinder;
import org.apache.xbean.finder.UrlSet;
import org.apache.xbean.finder.archive.Archive;
import org.apache.xbean.finder.archive.JarArchive;
import org.xml.sax.SAXException;

import javax.xml.bind.JAXBException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.apache.openejb.config.NewLoaderLogic.applyBuiltinExcludes;
import static org.apache.openejb.util.URLs.toFile;

/**
 * @version $Revision: 1227133 $ $Date: 2012-01-04 04:23:01 -0800 (Wed, 04 Jan 2012) $
 */
public class DeploymentLoader implements DeploymentFilterable {
	public static final Logger logger = Logger.getInstance(LogCategory.OPENEJB_STARTUP_CONFIG, "org.apache.openejb.util.resources");
	public static final String OPENEJB_READ_ALL_PERSISTENCE_XML = "openejb.read-all.persistence.xml";
	private static final String OPENEJB_ALTDD_PREFIX = "openejb.altdd.prefix";
	private static final String ddDir = "META-INF/";
	private boolean scanManagedBeans = true;

	public AppModule load(File jarFile) throws OpenEJBException {
		// verify we have a valid file
		String jarPath;
		try {
			jarPath = jarFile.getCanonicalPath();
		} catch (IOException e) {
			throw new OpenEJBException("Invalid application file path " + jarFile, e);
		}

		URL baseUrl = getFileUrl(jarFile);

		// create a class loader to use for detection of module type
		// do not use this class loader for any other purposes... it is
		// non-temp class loader and usage will mess up JPA
		ClassLoader doNotUseClassLoader = null;// =
												// ClassLoaderUtil.createClassLoader(jarPath,
												// new URL[]{baseUrl},
												// OpenEJB.class.getClassLoader());
		File tmpFile = null;

		try {
			// determine the module type
			Class<? extends DeploymentModule> moduleClass;

			try {
				// TODO: ClassFinder is leaking file locks, so copy the jar to a
				// temp dir
				// when we have a possible ejb-jar file (only ejb-jars result in
				// a ClassFinder being used)
				URL tempURL = baseUrl;
				if (jarFile.isFile() && UrlCache.cacheDir != null && !jarFile.getName().endsWith(".ear") && !jarFile.getName().endsWith(".war") && !jarFile.getName().endsWith(".rar")) {
					try {
						tmpFile = File.createTempFile("AppModule-", "", UrlCache.cacheDir);
						JarExtractor.copy(URLs.toFile(baseUrl), tmpFile);
						tempURL = tmpFile.toURI().toURL();

						doNotUseClassLoader = ClassLoaderUtil.createClassLoader(tmpFile.getCanonicalPath(), new URL[] { baseUrl }, getOpenEJBClassLoader(baseUrl));

					} catch (Exception e) {
						throw new OpenEJBException(e);
					}
				} else {
					doNotUseClassLoader = ClassLoaderUtil.createClassLoader(jarPath, new URL[] { baseUrl }, getOpenEJBClassLoader(baseUrl));
				}

				moduleClass = discoverModuleType(tempURL, ClassLoaderUtil.createTempClassLoader(doNotUseClassLoader), true);
			} catch (Exception e) {
				throw new UnknownModuleTypeException("Unable to determine module type for jar: " + baseUrl.toExternalForm(), e);
			} finally {
				// Try delete here, but will not work if used in
				// doNotUseClassLoader
				if (tmpFile != null && !tmpFile.delete()) {
					tmpFile.deleteOnExit();
				}
			}

			// We always load AppModule, as it somewhat likes a wrapper module
			if (AppModule.class.equals(moduleClass)) {

				return createAppModule(jarFile, jarPath);
			}

			if (EjbModule.class.equals(moduleClass)) {
				ClassLoader classLoader = ClassLoaderUtil.createTempClassLoader(jarPath, new URL[] { baseUrl }, getOpenEJBClassLoader(baseUrl));

				AppModule appModule;
				Class<? extends DeploymentModule> o = EjbModule.class;
				EjbModule ejbModule = createEjbModule(baseUrl, jarPath, classLoader, getModuleId(jarFile));

				// wrap the EJB Module with an Application Module
				appModule = new AppModule(ejbModule);

				addPersistenceUnits(appModule, baseUrl);

				return appModule;
			}

			if (ClientModule.class.equals(moduleClass)) {
				String jarLocation = URLs.toFilePath(baseUrl);
				ClientModule clientModule = createClientModule(baseUrl, jarLocation, getOpenEJBClassLoader(baseUrl), getModuleId(jarFile));

				// Wrap the resource module with an Application Module
				return new AppModule(clientModule);
			}

			if (ConnectorModule.class.equals(moduleClass)) {
				String jarLocation = URLs.toFilePath(baseUrl);
				ConnectorModule connectorModule = createConnectorModule(jarLocation, jarLocation, getOpenEJBClassLoader(baseUrl), getModuleId(jarFile));

				// Wrap the resource module with an Application Module
				return new AppModule(connectorModule);
			}

			if (WebModule.class.equals(moduleClass)) {
				final File file = toFile(baseUrl);

				// Standalone Web Module

				AppModule appModule = new AppModule(getOpenEJBClassLoader(baseUrl), file.getAbsolutePath(), new Application(), true);
				addWebModule(appModule, baseUrl, getOpenEJBClassLoader(baseUrl), getContextRoot(), getModuleName());

				final Map<String, URL> otherDD;
				if (Boolean.getBoolean(OPENEJB_READ_ALL_PERSISTENCE_XML)) {
					WebModule webModule = appModule.getWebModules().iterator().next();
					final List<URL> urls = webModule.getScannableUrls();
					final ResourceFinder finder = new ResourceFinder("", urls.toArray(new URL[urls.size()]));
					otherDD = getDescriptors(finder, false);
				} else {
					otherDD = new HashMap<String, URL>();
				}

				addWebPersistenceDD("persistence.xml", file, baseUrl, otherDD, appModule);
				addWebPersistenceDD("persistence-fragment.xml", file, baseUrl, otherDD, appModule);
				addPersistenceUnits(appModule, baseUrl);
				return appModule;
			}

			if (PersistenceModule.class.equals(moduleClass)) {
				String jarLocation = URLs.toFilePath(baseUrl);
				ClassLoader classLoader = ClassLoaderUtil.createTempClassLoader(jarPath, new URL[] { baseUrl }, getOpenEJBClassLoader(baseUrl));

				// wrap the EJB Module with an Application Module
				AppModule appModule = new AppModule(classLoader, jarLocation);

				// Persistence Units
				addPersistenceUnits(appModule, baseUrl);

				return appModule;
			}

			throw new UnsupportedModuleTypeException("Unsupported module type: " + moduleClass.getSimpleName());

		} finally {
			// if the application was unpacked appId used to create this class
			// loader will be wrong
			// We can safely destroy this class loader in either case, as it was
			// not use by any modules
			if (null != doNotUseClassLoader) {
				ClassLoaderUtil.destroyClassLoader(doNotUseClassLoader);
				doNotUseClassLoader = null;

				// Really try an flush this classloader out
				System.gc();
			}

			// Try delete here, but will not work if used in doNotUseClassLoader
			if (tmpFile != null && !tmpFile.delete()) {
				tmpFile.deleteOnExit();
			}
		}
	}

	private void addWebPersistenceDD(final String name, final File file, URL baseUrl, final Map<String, URL> otherDD, final AppModule appModule) {
		URL persistenceUrl = null;
		if (file.isDirectory()) {
			File persistenceXml = new File(file, "WEB-INF/classes/META-INF/" + name);
			if (persistenceXml.exists() && persistenceXml.isFile()) {
				try {
					persistenceUrl = persistenceXml.toURI().toURL();
				} catch (MalformedURLException e) {
					// no-op
				}
			}
		} else { // .war
			JarFile jf = null;
			try {
				jf = new JarFile(file);
				ZipEntry entry = jf.getEntry("WEB-INF/classes/META-INF/" + name);
				if (entry != null) {
					String base = baseUrl.toString();
					if (!base.startsWith("jar:")) {
						base = "jar:" + base;
						try {
							baseUrl = new URL(base + "!/WEB-INF/classes");
						} catch (MalformedURLException e) {
							// ignored
						}
					}

					try {
						persistenceUrl = new URL(base + "!/WEB-INF/classes/META-INF/" + name);
					} catch (MalformedURLException e) {
						// no-op
					}
				}
			} catch (IOException e) {
				// ignored
			} finally {
				if (jf != null) {
					try {
						jf.close();
					} catch(IOException e) {

					}
				}
			}
		}

		if (persistenceUrl != null || otherDD.containsKey(name)) {
			List<URL> persistenceUrls = (List<URL>) appModule.getAltDDs().get(name);
			if (persistenceUrls == null) {
				persistenceUrls = new ArrayList<URL>();
				appModule.getAltDDs().put(name, persistenceUrls);
			}

			if (persistenceUrl != null) {
				try {
					persistenceUrls.add(persistenceUrl);
				} catch (Exception e) {
					// no-op
				}
			}
			if (otherDD.containsKey(name)) {
				final URL otherUrl = otherDD.get(name);
				if (!persistenceUrls.contains(otherUrl)) {
					persistenceUrls.add(otherDD.get(name));
				}
			}
		}
	}

	protected ClassLoader getOpenEJBClassLoader(final URL url) {
		return OpenEJB.class.getClassLoader();
	}

	private String getModuleId(File file) {
		String filename = file.getName();
		return System.getProperty(filename + ".moduleId");
	}

	protected AppModule createAppModule(File jarFile, String jarPath) throws OpenEJBException {
		File appDir = unpack(jarFile);
		try {
			appDir = appDir.getCanonicalFile();
		} catch (IOException e) {
			throw new OpenEJBException("Invalid application directory " + appDir.getAbsolutePath());
		}

		URL appUrl = getFileUrl(appDir);

		String appId = appDir.getAbsolutePath();
		ClassLoader tmpClassLoader = ClassLoaderUtil.createTempClassLoader(appId, new URL[] { appUrl }, getOpenEJBClassLoader(appUrl));

		ResourceFinder finder = new ResourceFinder("", tmpClassLoader, appUrl);
		Map<String, URL> appDescriptors = getDescriptors(finder);

		try {

			//
			// Find all the modules using either the application xml or by
			// searching for all .jar, .war and .rar files.
			//

			Map<String, URL> ejbModules = new HashMap<String, URL>();
			Map<String, URL> clientModules = new HashMap<String, URL>();
			Map<String, URL> resouceModules = new HashMap<String, URL>();
			Map<String, URL> webModules = new HashMap<String, URL>();
			Map<String, String> webContextRoots = new HashMap<String, String>();

			URL applicationXmlUrl = appDescriptors.get("application.xml");

			Application application;
			if (applicationXmlUrl != null) {
				application = unmarshal(Application.class, "application.xml", applicationXmlUrl);
				for (Module module : application.getModule()) {
					try {
						if (module.getEjb() != null) {
							URL url = finder.find(module.getEjb().trim());
							ejbModules.put(module.getEjb(), url);
						} else if (module.getJava() != null) {
							URL url = finder.find(module.getJava().trim());
							clientModules.put(module.getConnector(), url);
						} else if (module.getConnector() != null) {
							URL url = finder.find(module.getConnector().trim());
							resouceModules.put(module.getConnector(), url);
						} else if (module.getWeb() != null) {
							URL url = finder.find(module.getWeb().getWebUri().trim());
							webModules.put(module.getWeb().getWebUri(), url);
							webContextRoots.put(module.getWeb().getWebUri(), module.getWeb().getContextRoot());
						}
					} catch (IOException e) {
						throw new OpenEJBException("Invalid path to module " + e.getMessage(), e);
					}
				}
			} else {
				application = new Application();
				HashMap<String, URL> files = new HashMap<String, URL>();
				scanDir(appDir, files, "");
				files.remove("META-INF/MANIFEST.MF");

				// todo we should also filter URLs here using
				// DeploymentsResolver.loadFromClasspath

				for (Map.Entry<String, URL> entry : files.entrySet()) {
					if (entry.getKey().startsWith("lib/"))
						continue;
					if (!entry.getKey().matches(".*\\.(jar|war|rar|ear)"))
						continue;

					try {
						ClassLoader moduleClassLoader = ClassLoaderUtil.createTempClassLoader(appId, new URL[] { entry.getValue() }, tmpClassLoader);

						Class<? extends DeploymentModule> moduleType = discoverModuleType(entry.getValue(), moduleClassLoader, true);

						if (EjbModule.class.equals(moduleType)) {
							ejbModules.put(entry.getKey(), entry.getValue());
						} else if (ClientModule.class.equals(moduleType)) {
							clientModules.put(entry.getKey(), entry.getValue());
						} else if (ConnectorModule.class.equals(moduleType)) {
							resouceModules.put(entry.getKey(), entry.getValue());
						} else if (WebModule.class.equals(moduleType)) {
							webModules.put(entry.getKey(), entry.getValue());
						}
					} catch (UnsupportedOperationException e) {
						// Ignore it as per the javaee spec EE.8.4.2 section
						// 1.d.iiilogger.info("Ignoring unknown module type: "+entry.getKey());
					} catch (Exception e) {
						throw new OpenEJBException("Unable to determine the module type of " + entry.getKey() + ": Exception: " + e.getMessage(), e);
					}
				}
			}

			//
			// Create a class loader for the application
			//

			// lib/*
			if (application.getLibraryDirectory() == null) {
				application.setLibraryDirectory("lib/");
			} else {
				String dir = application.getLibraryDirectory();
				if (!dir.endsWith("/"))
					application.setLibraryDirectory(dir + "/");
			}
			List<URL> extraLibs = new ArrayList<URL>();
			try {
				Map<String, URL> libs = finder.getResourcesMap(application.getLibraryDirectory());
				extraLibs.addAll(libs.values());
			} catch (IOException e) {
				logger.warning("Cannot load libs from '" + application.getLibraryDirectory() + "' : " + e.getMessage(), e);
			}

			// APP-INF/lib/*
			try {
				Map<String, URL> libs = finder.getResourcesMap("APP-INF/lib/");
				extraLibs.addAll(libs.values());
			} catch (IOException e) {
				logger.warning("Cannot load libs from 'APP-INF/lib/' : " + e.getMessage(), e);
			}

			// META-INF/lib/*
			try {
				Map<String, URL> libs = finder.getResourcesMap("META-INF/lib/");
				extraLibs.addAll(libs.values());
			} catch (IOException e) {
				logger.warning("Cannot load libs from 'META-INF/lib/' : " + e.getMessage(), e);
			}

			// All jars nested in the Resource Adapter
			HashMap<String, URL> rarLibs = new HashMap<String, URL>();
			for (Map.Entry<String, URL> entry : resouceModules.entrySet()) {
				try {
					// unpack the resource adapter archive
					File rarFile = toFile(entry.getValue());
					rarFile = unpack(rarFile);
					entry.setValue(rarFile.toURI().toURL());

					scanDir(appDir, rarLibs, "");
				} catch (MalformedURLException e) {
					throw new OpenEJBException("Malformed URL to app. " + e.getMessage(), e);
				}
			}
			for (Iterator<Map.Entry<String, URL>> iterator = rarLibs.entrySet().iterator(); iterator.hasNext();) {
				// remove all non jars from the rarLibs
				Map.Entry<String, URL> fileEntry = iterator.next();
				if (!fileEntry.getKey().endsWith(".jar"))
					continue;
				iterator.remove();
			}

			List<URL> classPath = new ArrayList<URL>();
			classPath.addAll(ejbModules.values());
			classPath.addAll(clientModules.values());
			classPath.addAll(rarLibs.values());
			classPath.addAll(extraLibs);
			URL[] urls = classPath.toArray(new URL[classPath.size()]);
			ClassLoader appClassLoader = ClassLoaderUtil.createTempClassLoader(appId, urls, getOpenEJBClassLoader(appUrl));

			//
			// Create the AppModule and all nested module objects
			//

			AppModule appModule = new AppModule(appClassLoader, appId, application, false);
			appModule.getAdditionalLibraries().addAll(extraLibs);
			appModule.getAltDDs().putAll(appDescriptors);
			appModule.getWatchedResources().add(appId);
			if (applicationXmlUrl != null) {
				appModule.getWatchedResources().add(URLs.toFilePath(applicationXmlUrl));
			}

			// EJB modules
			for (String moduleName : ejbModules.keySet()) {
				try {
					URL ejbUrl = ejbModules.get(moduleName);
					// we should try to use a reference to the temp classloader
					if (ClassLoaderUtil.isUrlCached(appModule.getJarLocation(), ejbUrl)) {
						try {
							ejbUrl = ClassLoaderUtil.getUrlCachedName(appModule.getJarLocation(), ejbUrl).toURI().toURL();

						} catch (MalformedURLException ignore) {
						}
					}
					File ejbFile = toFile(ejbUrl);
					String absolutePath = ejbFile.getAbsolutePath();

					EjbModule ejbModule = createEjbModule(ejbUrl, absolutePath, appClassLoader, moduleName);
					appModule.getEjbModules().add(ejbModule);
				} catch (OpenEJBException e) {
					logger.error("Unable to load EJBs from EAR: " + appId + ", module: " + moduleName + ". Exception: " + e.getMessage(), e);
				}
			}

			// Application Client Modules
			for (String moduleName : clientModules.keySet()) {
				try {
					URL clientUrl = clientModules.get(moduleName);
					// we should try to use a reference to the temp classloader
					if (ClassLoaderUtil.isUrlCached(appModule.getJarLocation(), clientUrl)) {
						try {
							clientUrl = ClassLoaderUtil.getUrlCachedName(appModule.getJarLocation(), clientUrl).toURI().toURL();

						} catch (MalformedURLException ignore) {
						}
					}
					File clientFile = toFile(clientUrl);
					String absolutePath = clientFile.getAbsolutePath();

					ClientModule clientModule = createClientModule(clientUrl, absolutePath, appClassLoader, moduleName);

					appModule.getClientModules().add(clientModule);
				} catch (Exception e) {
					logger.error("Unable to load App Client from EAR: " + appId + ", module: " + moduleName + ". Exception: " + e.getMessage(), e);
				}
			}

			// Resource modules
			for (String moduleName : resouceModules.keySet()) {
				try {
					URL rarUrl = resouceModules.get(moduleName);
					// we should try to use a reference to the temp classloader
					if (ClassLoaderUtil.isUrlCached(appModule.getJarLocation(), rarUrl)) {
						try {
							rarUrl = ClassLoaderUtil.getUrlCachedName(appModule.getJarLocation(), rarUrl).toURI().toURL();

						} catch (MalformedURLException ignore) {
						}
					}
					ConnectorModule connectorModule = createConnectorModule(appId, URLs.toFilePath(rarUrl), appClassLoader, moduleName);

					appModule.getConnectorModules().add(connectorModule);
				} catch (OpenEJBException e) {
					logger.error("Unable to load RAR: " + appId + ", module: " + moduleName + ". Exception: " + e.getMessage(), e);
				}
			}

			// Web modules
			for (String moduleName : webModules.keySet()) {
				try {
					URL warUrl = webModules.get(moduleName);
					addWebModule(appModule, warUrl, appClassLoader, webContextRoots.get(moduleName), moduleName);
				} catch (OpenEJBException e) {
					logger.error("Unable to load WAR: " + appId + ", module: " + moduleName + ". Exception: " + e.getMessage(), e);
				}
			}

			addBeansXmls(appModule);

			// Persistence Units
			Properties p = new Properties();
			p.put(appModule.getModuleId(), appModule.getJarLocation());
			FileUtils base = new FileUtils(appModule.getModuleId(), appModule.getModuleId(), p);
			List<URL> filteredUrls = new ArrayList<URL>();
			DeploymentsResolver.loadFromClasspath(base, filteredUrls, appModule.getClassLoader());
			addPersistenceUnits(appModule, filteredUrls.toArray(new URL[filteredUrls.size()]));

			return appModule;

		} catch (OpenEJBException e) {
			logger.error("Unable to load EAR: " + jarPath, e);
			throw e;
		}
	}

	protected ClientModule createClientModule(URL clientUrl, String absolutePath, ClassLoader appClassLoader, String moduleName) throws OpenEJBException {
		return createClientModule(clientUrl, absolutePath, appClassLoader, moduleName, true);
	}

	protected ClientModule createClientModule(URL clientUrl, String absolutePath, ClassLoader appClassLoader, String moduleName, boolean log) throws OpenEJBException {
		ResourceFinder clientFinder = new ResourceFinder(clientUrl);

		URL manifestUrl = null;
		try {
			manifestUrl = clientFinder.find("META-INF/MANIFEST.MF");
		} catch (IOException e) {
			//
		}

		String mainClass = null;
		if (manifestUrl != null) {
			try {
				InputStream is = manifestUrl.openStream();
				Manifest manifest = new Manifest(is);
				mainClass = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
			} catch (IOException e) {
				throw new OpenEJBException("Unable to determine Main-Class defined in META-INF/MANIFEST.MF file", e);
			}
		}

		// if (mainClass == null) throw new
		// IllegalStateException("No Main-Class defined in META-INF/MANIFEST.MF file");

		Map<String, URL> descriptors = getDescriptors(clientFinder, log);

		ApplicationClient applicationClient = null;
		URL clientXmlUrl = descriptors.get("application-client.xml");
		if (clientXmlUrl != null) {
			applicationClient = ReadDescriptors.readApplicationClient(clientXmlUrl);
		}

		ClientModule clientModule = new ClientModule(applicationClient, appClassLoader, absolutePath, mainClass, moduleName);

		clientModule.getAltDDs().putAll(descriptors);
		if (absolutePath != null) {
			clientModule.getWatchedResources().add(absolutePath);
		}
		if (clientXmlUrl != null && "file".equals(clientXmlUrl.getProtocol())) {
			clientModule.getWatchedResources().add(URLs.toFilePath(clientXmlUrl));
		}
		return clientModule;
	}

	protected EjbModule createEjbModule(URL baseUrl, String jarPath, ClassLoader classLoader, String moduleId) throws OpenEJBException {
		// read the ejb-jar.xml file
		Map<String, URL> descriptors;
		if (baseUrl != null) {
			descriptors = getDescriptors(baseUrl);
		} else {
			try {
				descriptors = getDescriptors(classLoader, null);
			} catch (IOException e) {
				descriptors = new HashMap<String, URL>();
			}
		}

		EjbJar ejbJar = null;
		URL ejbJarXmlUrl = descriptors.get("ejb-jar.xml");
		if (ejbJarXmlUrl != null) {
			ejbJar = ReadDescriptors.readEjbJar(ejbJarXmlUrl);
		}

		// create the EJB Module
		EjbModule ejbModule = new EjbModule(classLoader, moduleId, jarPath, ejbJar, null);
		ejbModule.getAltDDs().putAll(descriptors);
		if (jarPath != null) {
			ejbModule.getWatchedResources().add(jarPath);
		}
		if (ejbJarXmlUrl != null && "file".equals(ejbJarXmlUrl.getProtocol())) {
			ejbModule.getWatchedResources().add(URLs.toFilePath(ejbJarXmlUrl));
		}

		ejbModule.setClientModule(createClientModule(baseUrl, jarPath, classLoader, null, false));

		// load webservices descriptor
		addWebservices(ejbModule);
		return ejbModule;
	}

	protected void addWebModule(AppModule appModule, URL warUrl, ClassLoader parentClassLoader, String contextRoot, String moduleName) throws OpenEJBException {
		// create and add the WebModule
		String warPath = URLs.toFilePath(warUrl);
		WebModule webModule = createWebModule(appModule.getJarLocation(), warPath, parentClassLoader, contextRoot, moduleName);
		appModule.getWebModules().add(webModule);
		if (appModule.isStandaloneModule()) {
			appModule.getAdditionalLibraries().addAll(webModule.getUrls());
		}

		{
			List<URL> persistenceXmls = (List<URL>) appModule.getAltDDs().get("persistence.xml");
			if (persistenceXmls == null) {
				persistenceXmls = new ArrayList<URL>();
				appModule.getAltDDs().put("persistence.xml", persistenceXmls);
			}

			final Object o = webModule.getAltDDs().get("persistence.xml");

			if (o instanceof URL) {
				URL url = (URL) o;
				persistenceXmls.add(url);
			}

			if (o instanceof List) {
				List<URL> urls = (List<URL>) o;
				persistenceXmls.addAll(urls);
			}
		}

		// Per the Spec version of the Collapsed EAR there
		// aren't individual EjbModules inside a war.
		// The war itself is one big EjbModule if certain
		// conditions are met. These conditions are different
		// than an ear file, so the ear-style code we were previously
		// using doesn't exactly work anymore.
		//

		EjbModule webEjbModule = new EjbModule(webModule.getClassLoader(), webModule.getContextRoot(), webModule.getJarLocation(), null, null);
		webEjbModule.getAltDDs().putAll(webModule.getAltDDs());
		appModule.getEjbModules().add(webEjbModule);

		try {
			// TODO: Put our scanning ehnancements back, here
			IAnnotationFinder finder = FinderFactory.createFinder(webModule);
			webModule.setFinder(finder);
			webEjbModule.setFinder(finder);
		} catch (Exception e) {
			throw new OpenEJBException("Unable to create annotation scanner for web module " + webModule.getModuleId(), e);
		}

		addWebservices(webEjbModule);
	}

	private Properties getContextParams(List<ParamValue> contextParam) {
		Properties properties = new Properties();
		for (ParamValue paramValue : contextParam) {
			properties.put(paramValue.getParamName(), paramValue.getParamValue());
		}
		return properties;
	}

	protected WebModule createWebModule(String appId, String warPath, ClassLoader parentClassLoader, String contextRoot, String moduleName) throws OpenEJBException {
		File warFile = new File(warPath);
		warFile = unpack(warFile);

		// read web.xml file
		Map<String, URL> descriptors;
		try {
			descriptors = getWebDescriptors(warFile);
		} catch (IOException e) {
			throw new OpenEJBException("Unable to collect descriptors in web module: " + contextRoot, e);
		}

		WebApp webApp;
		URL webXmlUrl = descriptors.get("web.xml");
		if (webXmlUrl != null) {
			webApp = ReadDescriptors.readWebApp(webXmlUrl);
		} else {
			// no-web.xml webapp - possible since Servlet 3.0
			webApp = new WebApp();
		}

		// determine war class path
		URL[] webUrls = getWebappUrls(warFile);
		ClassLoader warClassLoader = ClassLoaderUtil.createTempClassLoader(appId, webUrls, parentClassLoader);

		// create web module
		WebModule webModule = new WebModule(webApp, contextRoot, warClassLoader, warFile.getAbsolutePath(), moduleName);
		webModule.setUrls(Arrays.asList(webUrls));
		webModule.setScannableUrls(filterWebappUrls(webUrls));
		webModule.getAltDDs().putAll(descriptors);
		webModule.getWatchedResources().add(warPath);
		webModule.getWatchedResources().add(warFile.getAbsolutePath());
		if (webXmlUrl != null && "file".equals(webXmlUrl.getProtocol())) {
			webModule.getWatchedResources().add(URLs.toFilePath(webXmlUrl));
		}

		// If webModule object is loaded by ejbModule or persitenceModule, no
		// need to load tag libraries, web service and JSF related staffs.
		addTagLibraries(webModule);

		// load webservices descriptor
		addWebservices(webModule);

		// load faces configuration files
		addFacesConfigs(webModule);

		addBeansXmls(webModule);

		return webModule;
	}

	private static List<URL> filterWebappUrls(URL[] webUrls) {
		UrlSet urls = new UrlSet(webUrls);
		try {
			urls = applyBuiltinExcludes(urls);
		} catch (MalformedURLException e) {
			return Arrays.asList(webUrls);
		}
		return urls.getUrls();
	}

	private void addBeansXmls(WebModule webModule) {
		final List<URL> urls = webModule.getScannableUrls();
		final URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[urls.size()]));

		final ArrayList<URL> xmls;
		try {
			xmls = Collections.list(loader.getResources("META-INF/beans.xml"));
			xmls.add((URL) webModule.getAltDDs().get("beans.xml"));
		} catch (IOException e) {

			return;
		}

		Beans complete = null;
		for (URL url : xmls) {
			if (url == null)
				continue;
			try {
				final Beans beans = ReadDescriptors.readBeans(url);

				if (complete == null) {
					complete = beans;
				} else {
					complete.getAlternativeClasses().addAll(beans.getAlternativeClasses());
					complete.getAlternativeStereotypes().addAll(beans.getAlternativeStereotypes());
					complete.getDecorators().addAll(beans.getDecorators());
					complete.getInterceptors().addAll(beans.getInterceptors());
				}
			} catch (OpenEJBException e) {
				logger.error("Unable to read beans.xml from :" + url.toExternalForm());
			}
		}

		webModule.getAltDDs().put("beans.xml", complete);
	}

	private void addBeansXmls(AppModule appModule) {
		final List<URL> urls = appModule.getAdditionalLibraries();
		final URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[urls.size()]));

		final ArrayList<URL> xmls;
		try {
			xmls = Collections.list(loader.getResources("META-INF/beans.xml"));
		} catch (IOException e) {

			return;
		}

		List<Archive> jars = new ArrayList<Archive>();

		Beans complete = null;
		for (URL url : xmls) {
			if (url == null)
				continue;
			try {

				final Beans beans = ReadDescriptors.readBeans(url);

				if (complete == null) {
					complete = beans;
				} else {
					complete.getAlternativeClasses().addAll(beans.getAlternativeClasses());
					complete.getAlternativeStereotypes().addAll(beans.getAlternativeStereotypes());
					complete.getDecorators().addAll(beans.getDecorators());
					complete.getInterceptors().addAll(beans.getInterceptors());
				}
				File file = URLs.toFile(url);
				jars.add(new JarArchive(appModule.getClassLoader(), url));
				// } catch (MalformedURLException e) {
				// logger.error("Unable to resolve jar path of beans.xml:"+
				// url.toExternalForm(), e);
			} catch (OpenEJBException e) {
				logger.error("Unable to read beans.xml from :" + url.toExternalForm(), e);
			}
		}

		if (complete == null)
			return;

		EjbModule ejbModule = new EjbModule(appModule.getClassLoader(), "ear-scoped-cdi-beans", new EjbJar(), new OpenejbJar());
		ejbModule.setBeans(complete);
		ejbModule.setFinder(new org.apache.xbean.finder.AnnotationFinder(new AggregatedArchive(appModule.getClassLoader(), xmls)));

		appModule.getEjbModules().add(ejbModule);
	}

	protected String getContextRoot() {
		return null;
	}

	protected String getModuleName() {
		return null;
	}

	public static URL[] getWebappUrls(File warFile) {
		List<URL> webClassPath = new ArrayList<URL>();
		File webInfDir = new File(warFile, "WEB-INF");
		try {
			webClassPath.add(new File(webInfDir, "classes").toURI().toURL());
		} catch (MalformedURLException e) {
			logger.warning("War path bad: " + new File(webInfDir, "classes"), e);
		}

		File libDir = new File(webInfDir, "lib");
		if (libDir.exists()) {
			for (File file : libDir.listFiles()) {
				if (file.getName().endsWith(".jar") || file.getName().endsWith(".zip")) {
					try {
						webClassPath.add(file.toURI().toURL());
					} catch (MalformedURLException e) {
						logger.warning("War path bad: " + file, e);
					}
				}
			}
		}

		// create the class loader
		URL[] webUrls = webClassPath.toArray(new URL[webClassPath.size()]);
		return webUrls;
	}

	private void addWebservices(WsModule wsModule) throws OpenEJBException {
		String webservicesEnabled = SystemInstance.get().getProperty(ConfigurationFactory.WEBSERVICES_ENABLED, "true");
		if (!Boolean.parseBoolean(webservicesEnabled)) {
			wsModule.getAltDDs().remove("webservices.xml");
			wsModule.setWebservices(null); // should be null already, but just
											// for good measure
			return;
		}

		// get location of webservices.xml file
		Object webservicesObject = wsModule.getAltDDs().get("webservices.xml");
		if (webservicesObject == null || !(webservicesObject instanceof URL)) {
			return;
		}
		URL webservicesUrl = (URL) webservicesObject;

		// determine the base url for this module (either file: or jar:)
		URL moduleUrl;
		try {
			File jarFile = new File(wsModule.getJarLocation());
			moduleUrl = jarFile.toURI().toURL();
			if (jarFile.isFile()) {
				moduleUrl = new URL("jar", "", -1, moduleUrl + "!/");
			}
		} catch (MalformedURLException e) {
			logger.warning("Invalid module location " + wsModule.getJarLocation());
			return;
		}

		// parse the webservices.xml file
		Map<URL, JavaWsdlMapping> jaxrpcMappingCache = new HashMap<URL, JavaWsdlMapping>();
		Webservices webservices = ReadDescriptors.readWebservices(webservicesUrl);
		wsModule.setWebservices(webservices);
		if ("file".equals(webservicesUrl.getProtocol())) {
			wsModule.getWatchedResources().add(URLs.toFilePath(webservicesUrl));
		}

		// parse any jaxrpc-mapping-files mentioned in the webservices.xml file
		for (WebserviceDescription webserviceDescription : webservices.getWebserviceDescription()) {
			String jaxrpcMappingFile = webserviceDescription.getJaxrpcMappingFile();
			if (jaxrpcMappingFile != null) {
				URL jaxrpcMappingUrl;
				try {
					jaxrpcMappingUrl = new URL(moduleUrl, jaxrpcMappingFile);
					JavaWsdlMapping jaxrpcMapping = jaxrpcMappingCache.get(jaxrpcMappingUrl);
					if (jaxrpcMapping == null) {
						jaxrpcMapping = ReadDescriptors.readJaxrpcMapping(jaxrpcMappingUrl);
						jaxrpcMappingCache.put(jaxrpcMappingUrl, jaxrpcMapping);
					}
					webserviceDescription.setJaxrpcMapping(jaxrpcMapping);
					if ("file".equals(jaxrpcMappingUrl.getProtocol())) {
						wsModule.getWatchedResources().add(URLs.toFilePath(jaxrpcMappingUrl));
					}
				} catch (MalformedURLException e) {
					logger.warning("Invalid jaxrpc-mapping-file location " + jaxrpcMappingFile);
				}
			}
		}

	}

	private void addTagLibraries(WebModule webModule) throws OpenEJBException {
		Set<URL> tldLocations = new HashSet<URL>();

		// web.xml contains tag lib locations in nested jsp config elements
		File warFile = new File(webModule.getJarLocation());
		WebApp webApp = webModule.getWebApp();
		if (webApp != null) {
			for (JspConfig jspConfig : webApp.getJspConfig()) {
				for (Taglib taglib : jspConfig.getTaglib()) {
					String location = taglib.getTaglibLocation();
					if (!location.startsWith("/")) {
						// this reproduces a tomcat bug
						location = "/WEB-INF/" + location;
					}
					try {
						File file = new File(warFile, location).getCanonicalFile().getAbsoluteFile();
						tldLocations.addAll(scanForTagLibs(file));
					} catch (IOException e) {
						logger.warning("JSP tag library location bad: " + location, e);
					}
				}
			}
		}

		// WEB-INF/**/*.tld except in WEB-INF/classes and WEB-INF/lib
		Set<URL> urls = scanWarForTagLibs(warFile);
		tldLocations.addAll(urls);

		// Search all libs
		ClassLoader parentClassLoader = webModule.getClassLoader().getParent();
		urls = scanClassLoaderForTagLibs(parentClassLoader);
		tldLocations.addAll(urls);

		// load the tld files
		for (URL location : tldLocations) {
			TldTaglib taglib = ReadDescriptors.readTldTaglib(location);
			webModule.getTaglibs().add(taglib);
			if ("file".equals(location.getProtocol())) {
				webModule.getWatchedResources().add(URLs.toFilePath(location));
			}
		}
	}

	/**
	 * Finds all faces configuration files and stores them in the WebModule
	 * 
	 * @param webModule
	 * @throws OpenEJBException
	 */
	private void addFacesConfigs(WebModule webModule) throws OpenEJBException {
		// *************************IMPORTANT*******************************************
		// TODO : kmalhi :: Add support to scrape META-INF/faces-config.xml in
		// jar files
		// look at section 10.4.2 of the JSF v1.2 spec, bullet 1 for details
		Set<URL> facesConfigLocations = new HashSet<URL>();

		// web.xml contains faces config locations in the context parameter
		// javax.faces.CONFIG_FILES
		File warFile = new File(webModule.getJarLocation());
		WebApp webApp = webModule.getWebApp();
		if (webApp != null) {
			String foundContextParam = webApp.contextParamsAsMap().get("javax.faces.CONFIG_FILES");
			if (foundContextParam != null && foundContextParam != null) {
				// the value is a comma separated list of config files
				String commaDelimitedListOfFiles = foundContextParam.trim();
				String[] configFiles = commaDelimitedListOfFiles.split(",");
				// trim any extra spaces in each file
				String[] trimmedConfigFiles = new String[configFiles.length];
				for (int i = 0; i < configFiles.length; i++) {
					trimmedConfigFiles[i] = configFiles[i].trim();
				}
				// convert each file to a URL and add it to facesConfigLocations
				for (String location : trimmedConfigFiles) {
					if (!location.startsWith("/"))
						logger.error("A faces configuration file should be context relative when specified in web.xml. Please fix the value of context parameter javax.faces.CONFIG_FILES for the file "
								+ location);
					try {
						File file = new File(warFile, location).getCanonicalFile().getAbsoluteFile();
						URL url = file.toURI().toURL();
						facesConfigLocations.add(url);

					} catch (IOException e) {
						logger.error("Faces configuration file location bad: " + location, e);
					}
				}
			} else if (foundContextParam == null) {
				logger.info("faces config file is null");
			}
		}

		// Search for WEB-INF/faces-config.xml
		File webInf = new File(warFile, "WEB-INF");
		if (webInf.isDirectory()) {
			File facesConfigFile = new File(webInf, "faces-config.xml");
			if (facesConfigFile.exists()) {
				try {
					facesConfigFile = facesConfigFile.getCanonicalFile().getAbsoluteFile();
					URL url = facesConfigFile.toURI().toURL();
					facesConfigLocations.add(url);
				} catch (IOException e) {
					// TODO: kmalhi:: Remove the printStackTrace after testing
					e.printStackTrace();
				}
			}
		}
		// load the faces configuration files
		// TODO:kmalhi:: Its good to have separate FacesConfig objects for
		// multiple configuration files, but what if there is a conflict where
		// the same
		// managebean is declared in two different files, which one wins? --
		// check the jsf spec, Hopefully JSF should be able to check for this
		// and
		// flag an error and not allow the application to be deployed.
		for (URL location : facesConfigLocations) {
			FacesConfig facesConfig = ReadDescriptors.readFacesConfig(location);
			webModule.getFacesConfigs().add(facesConfig);
			if ("file".equals(location.getProtocol())) {
				webModule.getWatchedResources().add(URLs.toFilePath(location));
			}
		}
	}

	private static Set<URL> scanClassLoaderForTagLibs(ClassLoader parentClassLoader) throws OpenEJBException {
		Set<URL> urls = new HashSet<URL>();
		if (parentClassLoader == null)
			return urls;

		UrlSet urlSet;
		try {
			urlSet = new UrlSet(parentClassLoader);
			urlSet = urlSet.excludeJavaEndorsedDirs();
			urlSet = urlSet.excludeJavaExtDirs();
			urlSet = urlSet.excludeJavaHome();
			urlSet = urlSet.exclude(ClassLoader.getSystemClassLoader());
		} catch (IOException e) {
			logger.warning("Error scanning class loader for JSP tag libraries", e);
			return urls;
		}

		for (URL url : urlSet.getUrls()) {
			if (url.getProtocol().equals("jar")) {
				try {
					String path = url.getPath();
					if (path.endsWith("!/")) {
						path = path.substring(0, path.length() - 2);
					}
					url = new URL(path);
				} catch (MalformedURLException e) {
					logger.warning("JSP tag library location bad: " + url.toExternalForm(), e);
					continue;
				}
			}

			if (!url.getProtocol().equals("file")) {
				continue;
			}

			File file = toFile(url);
			try {
				file = file.getCanonicalFile().getAbsoluteFile();
			} catch (IOException e) {
				logger.warning("JSP tag library location bad: " + file.getAbsolutePath(), e);
				continue;
			}

			urls.addAll(scanForTagLibs(file));
		}
		return urls;
	}

	private static Set<URL> scanWarForTagLibs(File war) {
		Set<URL> urls = new HashSet<URL>();

		File webInfDir = new File(war, "WEB-INF");
		if (!webInfDir.isDirectory())
			return urls;

		// skip the lib and classes dir in WEB-INF
		LinkedList<File> files = new LinkedList<File>();
		for (File file : webInfDir.listFiles()) {
			if ("lib".equals(file.getName()) || "classes".equals(file.getName())) {
				continue;
			}
			files.add(file);
		}

		if (files.isEmpty())
			return urls;

		// recursively scan the directories
		while (!files.isEmpty()) {
			File file = files.removeFirst();
			if (file.isDirectory()) {
				files.addAll(Arrays.asList(file.listFiles()));
			} else if (file.getName().endsWith(".tld")) {
				try {
					file = file.getCanonicalFile().getAbsoluteFile();
					urls.add(file.toURI().toURL());
				} catch (IOException e) {
					logger.warning("JSP tag library location bad: " + file.getAbsolutePath(), e);
				}
			}
		}

		return urls;
	}

	private static Set<URL> scanForTagLibs(File file) {
		Set<URL> tldLocations = new HashSet<URL>();
		String location = null;
		try {
			location = file.toURI().toURL().toExternalForm();

			if (location.endsWith(".jar")) {
				Set<URL> urls = scanJarForTagLibs(file);
				tldLocations.addAll(urls);
			} else if (file.getName().endsWith(".tld")) {
				URL url = file.toURI().toURL();
				tldLocations.add(url);
			}
		} catch (IOException e) {
			logger.warning("Error scanning for JSP tag libraries: " + file.getAbsolutePath(), e);
		}

		return tldLocations;
	}

	private static Set<URL> scanJarForTagLibs(File file) {
		Set<URL> urls = new HashSet<URL>();

		if (!file.isFile())
			return urls;

		JarFile jarFile = null;
		try {
			jarFile = new JarFile(file);

			URL jarFileUrl = new URL("jar", "", -1, file.toURI().toURL().toExternalForm() + "!/");
			for (JarEntry entry : Collections.list(jarFile.entries())) {
				String name = entry.getName();
				if (!name.startsWith("META-INF/") || !name.endsWith(".tld")) {
					continue;
				}
				URL url = new URL(jarFileUrl, name);
				urls.add(url);
			}
		} catch (IOException e) {
			logger.warning("Error scanning jar for JSP tag libraries: " + file.getAbsolutePath(), e);
		} finally {
			if (jarFile != null) {
				try {
					jarFile.close();
				} catch (IOException e) {
					// exception ignored
				}
			}
		}

		return urls;
	}

	protected static ConnectorModule createConnectorModule(String appId, String rarPath, ClassLoader parentClassLoader, String moduleId) throws OpenEJBException {
		URL baseUrl;// unpack the rar file
		File rarFile = new File(rarPath);
		rarFile = unpack(rarFile);
		baseUrl = getFileUrl(rarFile);

		// read the ra.xml file
		Map<String, URL> descriptors = getDescriptors(baseUrl);
		Connector connector = null;
		URL rarXmlUrl = descriptors.get("ra.xml");
		if (rarXmlUrl != null) {
			connector = ReadDescriptors.readConnector(rarXmlUrl);
		}

		// find the nested jar files
		HashMap<String, URL> rarLibs = new HashMap<String, URL>();
		scanDir(rarFile, rarLibs, "");
		for (Iterator<Map.Entry<String, URL>> iterator = rarLibs.entrySet().iterator(); iterator.hasNext();) {
			// remove all non jars from the rarLibs
			Map.Entry<String, URL> fileEntry = iterator.next();
			if (!fileEntry.getKey().endsWith(".jar")) {
				iterator.remove();
			}
		}

		// create the class loader
		List<URL> classPath = new ArrayList<URL>();
		classPath.addAll(rarLibs.values());
		URL[] urls = classPath.toArray(new URL[classPath.size()]);
		ClassLoader appClassLoader = ClassLoaderUtil.createTempClassLoader(appId, urls, parentClassLoader);

		// create the Resource Module
		ConnectorModule connectorModule = new ConnectorModule(connector, appClassLoader, rarPath, moduleId);
		connectorModule.getAltDDs().putAll(descriptors);
		connectorModule.getLibraries().addAll(classPath);
		connectorModule.getWatchedResources().add(rarPath);
		connectorModule.getWatchedResources().add(rarFile.getAbsolutePath());
		if (rarXmlUrl != null && "file".equals(rarXmlUrl.getProtocol())) {
			connectorModule.getWatchedResources().add(URLs.toFilePath(rarXmlUrl));
		}

		return connectorModule;
	}

	@SuppressWarnings({ "unchecked" })
	protected static void addPersistenceUnits(AppModule appModule, URL... urls) throws OpenEJBException {

		// OPENEJB-1059: Anything in the appModule.getAltDDs() map has already
		// been
		// processed by the altdd code, so anything in here should not cause
		// OPENEJB-1059
		List<URL> persistenceUrls = null;
		try {
			persistenceUrls = (List<URL>) appModule.getAltDDs().get("persistence.xml");
		} catch (ClassCastException e) {
			// That happens when we are trying to deploy an ear file.
			// lets try to get a single object instead
			final Object value = appModule.getAltDDs().get("persistence.xml");

			persistenceUrls = new ArrayList<URL>();
			persistenceUrls.add(URL.class.cast(value));

			appModule.getAltDDs().put("persistence.xml", persistenceUrls);
		}
		if (persistenceUrls == null) {
			persistenceUrls = new ArrayList<URL>();
			appModule.getAltDDs().put("persistence.xml", persistenceUrls);
		}

		List<URL> persistenceFragmentsUrls = (List<URL>) appModule.getAltDDs().get("persistence-fragment.xml");
		if (persistenceFragmentsUrls == null) {
			persistenceFragmentsUrls = new ArrayList<URL>();
			appModule.getAltDDs().put("persistence-fragment.xml", persistenceFragmentsUrls);
		}

		for (URL url : urls) {
			// OPENEJB-1059: looking for an altdd persistence.xml file in all
			// urls
			// delegates to xbean finder for going throughout the list
			ResourceFinder finder = new ResourceFinder("", appModule.getClassLoader(), url);
			Map<String, URL> descriptors = getDescriptors(finder, false);

			// if a persistence.xml has been found, just pull it to the list
			if (descriptors.containsKey("persistence.xml")) {
				URL descriptor = descriptors.get("persistence.xml");

				// don't add it if already present
				if (persistenceUrls.contains(descriptor))
					continue;

				// log if it is an altdd
				String urlString = descriptor.toExternalForm();
				if (!urlString.contains("META-INF/persistence.xml")) {
					logger.info("AltDD persistence.xml -> " + urlString);
				}

				persistenceUrls.add(descriptor);
			}
		}

		// look for persistence-fragment.xml
		for (URL url : urls) {
			// OPENEJB-1059: looking for an altdd persistence.xml file in all
			// urls
			// delegates to xbean finder for going throughout the list
			ResourceFinder finder = new ResourceFinder("", appModule.getClassLoader(), url);
			Map<String, URL> descriptors = getDescriptors(finder, false);

			// if a persistence.xml has been found, just pull it to the list
			if (descriptors.containsKey("persistence-fragment.xml")) {
				URL descriptor = descriptors.get("persistence-fragment.xml");

				if (persistenceFragmentsUrls.contains(descriptor)) {
					continue;
				}

				// log if it is an altdd
				String urlString = descriptor.toExternalForm();
				if (!urlString.contains("META-INF/persistence-fragment.xml")) {
					logger.info("AltDD persistence-fragment.xml -> " + urlString);
				}

				persistenceFragmentsUrls.add(descriptor);
			}
		}
	}

	public static Map<String, URL> getDescriptors(URL moduleUrl) throws OpenEJBException {

		ResourceFinder finder = new ResourceFinder(moduleUrl);
		return getDescriptors(finder);
	}

	private static Map<String, URL> getDescriptors(ResourceFinder finder) throws OpenEJBException {
		return getDescriptors(finder, true);
	}

	private static Map<String, URL> getDescriptors(ResourceFinder finder, boolean log) throws OpenEJBException {
		try {

			return altDDSources(mapDescriptors(finder), log);

		} catch (IOException e) {
			throw new OpenEJBException("Unable to determine descriptors in jar.", e);
		}
	}

	private static Map<String, URL> mapDescriptors(ResourceFinder finder) throws IOException {
		final Map<String, URL> map = finder.getResourcesMap(ddDir);

		if (map.size() == 0) {

			String[] known = { "web.xml", "ejb-jar.xml", "openejb-jar.xml", "env-entries.properties", "beans.xml", "ra.xml", "application.xml", "application-client.xml", "persistence-fragment.xml",
					"persistence.xml", "validation.xml" };
			for (String descriptor : known) {

				final URL url = finder.getResource(ddDir + descriptor);
				if (url != null)
					map.put(descriptor, url);
			}

		}
		return map;
	}

	/**
	 * Modifies the map passed in with all the alt dd URLs found
	 * 
	 * @param map
	 * @param log
	 * @return the same map instance updated with alt dds
	 */
	public static Map<String, URL> altDDSources(Map<String, URL> map, boolean log) {
		String prefixes = SystemInstance.get().getProperty(OPENEJB_ALTDD_PREFIX);

		if (prefixes == null || prefixes.length() <= 0)
			return map;

		List<String> list = new ArrayList<String>(Arrays.asList(prefixes.split(",")));
		Collections.reverse(list);

		Map<String, URL> alts = new HashMap<String, URL>();

		for (String prefix : list) {
			prefix = prefix.trim();
			if (!prefix.matches(".*[.-]$"))
				prefix += ".";

			for (Map.Entry<String, URL> entry : new HashMap<String, URL>(map).entrySet()) {
				String key = entry.getKey();
				URL value = entry.getValue();
				if (key.startsWith(prefix)) {
					key = key.substring(prefix.length());

					alts.put(key, value);
				}
			}
		}

		for (Map.Entry<String, URL> alt : alts.entrySet()) {
			String key = alt.getKey();
			URL value = alt.getValue();

			// don't add and log if the same key/value is already in the map
			if (value.equals(map.get(key)))
				continue;

			if (log)
				logger.info("AltDD " + key + " -> " + value.toExternalForm());
			map.put(key, value);
		}

		return map;
	}

	protected Map<String, URL> getWebDescriptors(File warFile) throws IOException {
		Map<String, URL> descriptors = new TreeMap<String, URL>();

		// xbean resource finder has a bug when you use any uri but "META-INF"
		// and the jar file does not contain a directory entry for the uri
		
		if (warFile.isFile()) {			
			URL jarURL = new URL("jar", "", -1, warFile.toURI().toURL() + "!/");
			JarFile jarFile = null;
			try {
				jarFile = new JarFile(warFile);
				for (JarEntry entry : Collections.list(jarFile.entries())) {
					String entryName = entry.getName();
					if (!entry.isDirectory() && entryName.startsWith("WEB-INF/") && entryName.indexOf('/', "WEB-INF/".length()) > 0) {
						descriptors.put(entryName, new URL(jarURL, entry.getName()));
					}
				}
			} catch (IOException e) {
				// most likely an invalid jar file
			} finally {
				if (jarFile != null) {
					try {
						jarFile.close();
					} catch(IOException e) {

					}
				}
			}
		} else if (warFile.isDirectory()) {
			File webInfDir = new File(warFile, "WEB-INF");
			if (webInfDir.isDirectory()) {
				for (File file : webInfDir.listFiles()) {
					if (!file.isDirectory()) {
						descriptors.put(file.getName(), file.toURI().toURL());
					}
				}
			}
		}

		return descriptors;
	}

	protected File getFile(URL warUrl) {
		if ("jar".equals(warUrl.getProtocol())) {
			String pathname = warUrl.getPath();

			// we only support file based jar urls
			if (!pathname.startsWith("file:")) {
				return null;
			}

			// strip off "file:"
			pathname = pathname.substring("file:".length());

			// file path has trailing !/ that must be stripped off
			pathname = pathname.substring(0, pathname.lastIndexOf('!'));

			pathname = URLDecoder.decode(pathname);
			return new File(pathname);
		} else if ("file".equals(warUrl.getProtocol())) {
			String pathname = warUrl.getPath();
			return new File(URLDecoder.decode(pathname));
		} else {
			return null;
		}
	}

	@SuppressWarnings({ "unchecked" })
	public static <T> T unmarshal(Class<T> type, String descriptor, URL url) throws OpenEJBException {
		try {
			return (T) JaxbJavaee.unmarshalJavaee(type, url.openStream());
		} catch (SAXException e) {
			throw new OpenEJBException("Cannot parse the " + descriptor + " file: " + url.toExternalForm(), e);
		} catch (JAXBException e) {
			throw new OpenEJBException("Cannot unmarshall the " + descriptor + " file: " + url.toExternalForm(), e);
		} catch (IOException e) {
			throw new OpenEJBException("Cannot read the " + descriptor + " file: " + url.toExternalForm(), e);
		} catch (Exception e) {
			throw new OpenEJBException("Encountered unknown error parsing the " + descriptor + " file: " + url.toExternalForm(), e);
		}
	}

	public static void scanDir(File dir, Map<String, URL> files, String path) {
		for (File file : dir.listFiles()) {
			if (file.isDirectory()) {
				scanDir(file, files, path + file.getName() + "/");
			} else {
				String name = file.getName();
				try {
					files.put(path + name, file.toURI().toURL());
				} catch (MalformedURLException e) {
					logger.warning("EAR path bad: " + path + name, e);
				}
			}
		}
	}

	public Class<? extends DeploymentModule> discoverModuleType(URL baseUrl, ClassLoader classLoader, boolean searchForDescriptorlessApplications) throws IOException, UnknownModuleTypeException {
		Set<RequireDescriptors> search = new HashSet<RequireDescriptors>();

		if (!searchForDescriptorlessApplications)
			search.addAll(Arrays.asList(RequireDescriptors.values()));

		return discoverModuleType(baseUrl, classLoader, search);
	}

	public Class<? extends DeploymentModule> discoverModuleType(URL baseUrl, ClassLoader classLoader, Set<RequireDescriptors> requireDescriptor) throws IOException, UnknownModuleTypeException {
		final boolean scanPotentialEjbModules = !requireDescriptor.contains(RequireDescriptors.EJB);
		final boolean scanPotentialClientModules = !requireDescriptor.contains(RequireDescriptors.CLIENT);

		URL pathToScanDescriptors = baseUrl;
		if (baseUrl != null) {
			final String baseURLString = baseUrl.toString();
			if (baseUrl.getProtocol().equals("file") && baseURLString.endsWith("WEB-INF/classes/")) {
				// EJB found in WAR/WEB-INF/classes, scan WAR for ejb-jar.xml
				pathToScanDescriptors = new URL(baseURLString.substring(0, baseURLString.lastIndexOf("WEB-INF/classes/")));
			}
		}

		Map<String, URL> descriptors = getDescriptors(classLoader, pathToScanDescriptors);

		String path;
		if (baseUrl != null) {
			path = baseUrl.getPath();
			path = URLDecoder.decode(path);
		} else {
			path = "";
		}

		if (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}

		if (descriptors.containsKey("application.xml") || path.endsWith(".ear")) {
			return AppModule.class;
		}

		if (descriptors.containsKey("application-client.xml")) {
			return ClientModule.class;
		}

		if (descriptors.containsKey("ra.xml") || path.endsWith(".rar")) {
			return ConnectorModule.class;
		}

		if (baseUrl != null) {
			Map<String, URL> webDescriptors = getWebDescriptors(getFile(baseUrl));
			if (webDescriptors.containsKey("web.xml") || webDescriptors.containsKey("web-fragment.xml") // descriptor
					|| path.endsWith(".war") || new File(path, "WEB-INF").exists() || isOSGiWebApp(path)) { // webapp specific files
				return WebModule.class;
			}
		}

		if (descriptors.containsKey("ejb-jar.xml") || descriptors.containsKey("beans.xml")) {
			return EjbModule.class;
		}

		URL manifestUrl = descriptors.get("MANIFEST.MF");
		if (scanPotentialClientModules && manifestUrl != null) {
			// In this case scanPotentialClientModules really means
			// "require application-client.xml"
			InputStream is = manifestUrl.openStream();
			Manifest manifest = new Manifest(is);
			String mainClass = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
			if (mainClass != null) {
				return ClientModule.class;
			}
		}

		Class<? extends DeploymentModule> cls = checkAnnotations(baseUrl, classLoader, scanPotentialEjbModules, scanPotentialClientModules);
		if (cls != null)
			return cls;

		if (descriptors.containsKey("persistence.xml") || descriptors.containsKey("persistence-fragment.xml")) {
			return PersistenceModule.class;
		}

		throw new UnknownModuleTypeException("Unknown module type: url=" + path); // baseUrl
																					// can
																					// be
																					// null
	}

	private Map<String, URL> getDescriptors(ClassLoader classLoader, URL pathToScanDescriptors) throws IOException {
		ResourceFinder finder = new ResourceFinder("", classLoader, pathToScanDescriptors);

		return altDDSources(mapDescriptors(finder), false);
	}

	private Class<? extends DeploymentModule> checkAnnotations(URL urls, ClassLoader classLoader, final boolean scanPotentialEjbModules, final boolean scanPotentialClientModules) {
		Class<? extends DeploymentModule> cls = null;
		if (scanPotentialEjbModules || scanPotentialClientModules) {
			AnnotationFinder classFinder = new AnnotationFinder(classLoader, urls);

			final Set<Class<? extends DeploymentModule>> otherTypes = new LinkedHashSet<Class<? extends DeploymentModule>>();

			AnnotationFinder.Filter filter = new AnnotationFinder.Filter() {
				final String packageName = LocalClient.class.getName().replace("LocalClient", "");

				public boolean accept(String annotationName) {
					if (scanPotentialEjbModules) {
						if (annotationName.startsWith("javax.ejb.")) {
							if ("javax.ejb.Stateful".equals(annotationName))
								return true;
							if ("javax.ejb.Stateless".equals(annotationName))
								return true;
							if ("javax.ejb.Singleton".equals(annotationName))
								return true;
							if ("javax.ejb.MessageDriven".equals(annotationName))
								return true;
						} else if (scanManagedBeans && "javax.annotation.ManagedBean".equals(annotationName)) {
							return true;
						}
					} else if (scanPotentialClientModules && annotationName.startsWith(packageName)) {
						if (LocalClient.class.getName().equals(annotationName))
							otherTypes.add(ClientModule.class);
						if (RemoteClient.class.getName().equals(annotationName))
							otherTypes.add(ClientModule.class);
					}
					return false;
				}
			};

			if (classFinder.find(filter)) {
				cls = EjbModule.class;
			}

			if (otherTypes.size() > 0) {
				// We may want some ordering/sorting if we add more type
				// scanning
				cls = otherTypes.iterator().next();
			}
		}
		return cls;
	}

	protected static File unpack(File jarFile) throws OpenEJBException {
		if (jarFile.isDirectory()) {
			return jarFile;
		}

		String name = jarFile.getName();
		if (name.endsWith(".jar") || name.endsWith(".ear") || name.endsWith(".zip") || name.endsWith(".war") || name.endsWith(".rar")) {
			name = name.replaceFirst("....$", "");
		} else {
			name += ".unpacked";
		}

		try {
			return JarExtractor.extract(jarFile, name);
		} catch (IOException e) {
			throw new OpenEJBException("Unable to extract jar. " + e.getMessage(), e);
		}
	}

	protected static URL getFileUrl(File jarFile) throws OpenEJBException {
		URL baseUrl;
		try {
			baseUrl = jarFile.toURI().toURL();
		} catch (MalformedURLException e) {
			throw new OpenEJBException("Malformed URL to app. " + e.getMessage(), e);
		}
		return baseUrl;
	}

	private boolean isOSGiWebApp(String path) {
		JarFile webApp = null;
		if ((path.endsWith(".jar")) || (path.endsWith("bundlefile"))) {
			try {
				webApp = new JarFile(path);
				Manifest manifest = webApp.getManifest();
				if ((manifest != null) && (manifest.getMainAttributes().getValue("Web-ContextPath") != null))
					return true;
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				if (webApp != null) {
					try {
						webApp.close();
					} catch (IOException e) {

					}
				}
			}
		}
		return false;
	}

}